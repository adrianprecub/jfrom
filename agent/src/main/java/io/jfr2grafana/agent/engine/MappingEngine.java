package io.jfr2grafana.agent.engine;

import io.jfr2grafana.agent.config.EnableSpec;
import io.jfr2grafana.agent.config.EventRule;
import io.jfr2grafana.agent.config.LabelKind;
import io.jfr2grafana.agent.config.LabelSpec;
import io.jfr2grafana.agent.config.MappingConfig;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.event.RecordedEventView;
import io.jfr2grafana.agent.metrics.Labels;
import io.jfr2grafana.agent.metrics.MetricRegistry;
import io.jfr2grafana.agent.metrics.MetricType;
import jdk.jfr.EventSettings;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the {@link RecordingStream}: enables the events a {@link MappingConfig} needs, dispatches
 * each event to every {@link EventRule} that targets it, and feeds the results into a
 * {@link MetricRegistry}.
 *
 * <p><b>Thread ownership.</b> {@link RecordingStream#startAsync()} would hand thread ownership to
 * an internal, non-daemon JFR thread that this class has no way to mark as a daemon (the public
 * API exposes no {@code setDaemon}). Instead, {@link #start()} calls the blocking
 * {@link RecordingStream#start()} from inside a thread this class creates and explicitly marks
 * daemon, so the host JVM can always exit even if {@link #close()} is never called.
 *
 * <p><b>Failure containment.</b> Each event callback is wrapped so nothing it does can propagate
 * back into the stream: a per-rule try/catch means one malformed rule cannot stop other rules on
 * the same event, or any other event, from being processed; an outer try/catch on the whole
 * callback is a second line of defence. Every failure increments
 * {@value #EVENTS_DROPPED_METRIC} and is logged at most occasionally (the first occurrence, then
 * every power-of-ten occurrence) per distinct failing event, so one noisy rule cannot flood
 * stderr.
 */
public final class MappingEngine implements AutoCloseable {

    public static final String EVENTS_PROCESSED_METRIC = "jfr2grafana_events_processed_total";
    public static final String EVENTS_DROPPED_METRIC = "jfr2grafana_events_dropped_total";

    private static final LabelSpec EVENT_LABEL = new LabelSpec("event", "event", LabelKind.STRING);
    private static final LabelSpec REASON_LABEL = new LabelSpec("reason", "reason", LabelKind.STRING);

    /** Self-observability counter: events that reached a handler and were processed. */
    public static final MetricSpec EVENTS_PROCESSED_SPEC = new MetricSpec(
            EVENTS_PROCESSED_METRIC,
            MetricType.COUNTER,
            "JFR events successfully processed by the mapping engine, by event name",
            null,
            List.of(EVENT_LABEL),
            null,
            0);

    /** Self-observability counter: rule applications that failed and were contained. */
    public static final MetricSpec EVENTS_DROPPED_SPEC = new MetricSpec(
            EVENTS_DROPPED_METRIC,
            MetricType.COUNTER,
            "Rule applications dropped after an unexpected failure, by reason",
            null,
            List.of(REASON_LABEL),
            null,
            0);

    private static final Set<String> PERIOD_LITERALS = Set.of("everychunk", "beginchunk", "endchunk");
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ns|us|ms|s|m)\\s*$", Pattern.CASE_INSENSITIVE);

    private final MappingConfig config;
    private final MetricRegistry registry;
    private final Consumer<String> log;
    private final ConcurrentHashMap<String, AtomicLong> failureCounts = new ConcurrentHashMap<>();

    private RecordingStream stream;
    private Thread streamThread;
    private volatile boolean started;

    public MappingEngine(MappingConfig config, MetricRegistry registry) {
        this(config, registry, message -> System.err.println("jfr2grafana: " + message));
    }

    public MappingEngine(MappingConfig config, MetricRegistry registry, Consumer<String> log) {
        this.config = config;
        this.registry = registry;
        this.log = log;
    }

    /** Builds and starts the recording stream on its own daemon thread. Idempotent-unsafe: call once. */
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("MappingEngine already started");
        }
        Map<String, List<EventRule>> byEvent = groupByEvent(config.rules());

        RecordingStream rs = new RecordingStream();
        for (Map.Entry<String, List<EventRule>> entry : byEvent.entrySet()) {
            String eventName = entry.getKey();
            List<EventRule> rules = entry.getValue();
            configureEnable(rs, eventName, rules);
            rs.onEvent(eventName, jfrEvent -> dispatch(eventName, rules, jfrEvent));
        }

        this.stream = rs;
        Thread thread = new Thread(() -> runStream(rs), "jfr2grafana-stream");
        thread.setDaemon(true);
        this.streamThread = thread;
        this.started = true;
        thread.start();
    }

    @Override
    public synchronized void close() {
        if (stream != null) {
            stream.close();
        }
        if (streamThread != null) {
            try {
                streamThread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------
    // Dispatch, with failure containment
    // ------------------------------------------------------------------

    private void dispatch(String eventName, List<EventRule> rules, jdk.jfr.consumer.RecordedEvent jfrEvent) {
        dispatchEvent(eventName, rules, new RecordedEventView(jfrEvent));
    }

    /**
     * The actual per-event dispatch logic, decoupled from {@code RecordedEvent} so it can be
     * exercised directly against {@code MapEventView} or a fixture-derived
     * {@code RecordedEventView} in tests, without a live {@code RecordingStream}.
     *
     * <p>Package-private for tests.
     */
    void dispatchEvent(String eventName, List<EventRule> rules, io.jfr2grafana.agent.event.EventView view) {
        try {
            for (EventRule rule : rules) {
                try {
                    RuleEvaluator.evaluate(rule, view, registry);
                } catch (Throwable t) {
                    onFailure(eventName, t);
                }
            }
            registry.addCounter(EVENTS_PROCESSED_SPEC, Labels.of(Map.of("event", eventName)), 1.0);
        } catch (Throwable t) {
            // Defence in depth: nothing above should reach here (each rule is already guarded),
            // but the stream thread must never see an exception escape this callback regardless.
            onFailure(eventName, t);
        }
    }

    private void onFailure(String eventName, Throwable t) {
        registry.addCounter(EVENTS_DROPPED_SPEC, Labels.of(Map.of("reason", reasonFor(t))), 1.0);
        long count = failureCounts.computeIfAbsent(eventName, k -> new AtomicLong()).incrementAndGet();
        if (isPowerOfTen(count)) {
            log.accept("rule failure #" + count + " while processing '" + eventName + "': "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String reasonFor(Throwable t) {
        return t.getClass().getSimpleName();
    }

    private static boolean isPowerOfTen(long n) {
        if (n <= 0) {
            return false;
        }
        long v = 1;
        while (v < n) {
            v *= 10;
        }
        return v == n;
    }

    private static Map<String, List<EventRule>> groupByEvent(List<EventRule> rules) {
        Map<String, List<EventRule>> byEvent = new LinkedHashMap<>();
        for (EventRule rule : rules) {
            byEvent.computeIfAbsent(rule.event(), k -> new ArrayList<>()).add(rule);
        }
        return byEvent;
    }

    // ------------------------------------------------------------------
    // Stream lifecycle
    // ------------------------------------------------------------------

    private void runStream(RecordingStream rs) {
        try {
            rs.start(); // blocks the calling (daemon) thread until close()/stop() is invoked
        } catch (Throwable t) {
            log.accept("recording stream terminated unexpectedly: " + t);
        }
    }

    // ------------------------------------------------------------------
    // enable()/withPeriod()/withThreshold()/withStackTrace() reconciliation
    // ------------------------------------------------------------------

    /**
     * Applies the combined {@link EnableSpec} of every rule targeting {@code eventName}.
     *
     * <p>JFR keys its recording settings by event name, so if several rules enable the same
     * event, the last {@code with...} call for a given setting wins - simply applying each
     * rule's spec in turn could silently let one rule's threshold/stack-trace choice clobber
     * another's. Instead the specs are merged first: the lowest threshold (so no rule's events
     * are filtered out by JFR itself), the first non-null period, and stack traces on if any
     * rule asked for them.
     */
    private static void configureEnable(RecordingStream rs, String eventName, List<EventRule> rules) {
        EventSettings settings = rs.enable(eventName);

        Duration minThreshold = null;
        String period = null;
        boolean anyStackTrace = false;
        for (EventRule rule : rules) {
            EnableSpec enable = rule.enable();
            if (enable.threshold() != null
                    && (minThreshold == null || enable.threshold().compareTo(minThreshold) < 0)) {
                minThreshold = enable.threshold();
            }
            if (period == null && enable.period() != null) {
                period = enable.period();
            }
            anyStackTrace = anyStackTrace || enable.stackTrace();
        }

        if (period != null) {
            applyPeriod(settings, period);
        }
        if (minThreshold != null) {
            settings.withThreshold(minThreshold);
        }
        if (anyStackTrace) {
            settings.withStackTrace();
        } else {
            settings.withoutStackTrace();
        }
    }

    private static void applyPeriod(EventSettings settings, String period) {
        String lower = period.trim().toLowerCase(Locale.ROOT);
        if (PERIOD_LITERALS.contains(lower)) {
            settings.with("period", lower);
        } else {
            settings.withPeriod(parseDuration(period));
        }
    }

    /** Mirrors {@code MappingLoader}'s duration grammar ("10ms", "1s", "500us", "2m", "0ms"). */
    private static Duration parseDuration(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "enable.period '" + text + "' is not 'everyChunk'/'beginChunk'/'endChunk' or a duration");
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        double nanos = switch (unit) {
            case "ns" -> amount;
            case "us" -> amount * 1_000d;
            case "ms" -> amount * 1_000_000d;
            case "s" -> amount * 1_000_000_000d;
            case "m" -> amount * 60_000_000_000d;
            default -> throw new IllegalStateException("unreachable: unit '" + unit + "'");
        };
        return Duration.ofNanos(Math.round(nanos));
    }
}
