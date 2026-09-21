package io.jfr2grafana.agent.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.DurationUnit;
import io.jfr2grafana.agent.config.EventRule;
import io.jfr2grafana.agent.config.LabelKind;
import io.jfr2grafana.agent.config.LabelSpec;
import io.jfr2grafana.agent.config.MappingConfig;
import io.jfr2grafana.agent.config.MappingLoader;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.config.ValueKind;
import io.jfr2grafana.agent.event.RecordedEventView;
import io.jfr2grafana.agent.engine.RuleEvaluator;
import io.jfr2grafana.agent.metrics.DefaultMetricRegistry;
import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Locks &amp; Safepoints mapping pack ({@code mappings/locks-safepoints.yaml}):
 *
 * <ol>
 *   <li>it parses, with the expected metric names, types and label sets;
 *   <li>replayed against the committed {@code fixtures/sample.jfr}, every metric produces
 *       plausible-magnitude series (seconds, not raw ticks);
 *   <li>every metric name stays inside this pack's namespace ({@code jfr_monitor_},
 *       {@code jfr_park_}, {@code jfr_safepoint_}, {@code jfr_vmop_}) - the collision guard the
 *       four concurrently-authored packs rely on;
 *   <li>the cardinality caps this pack needs (on the class-labelled metrics) are actually set.
 * </ol>
 */
class LocksSafepointsPackTest {

    private static final List<String> ALLOWED_PREFIXES =
            List.of("jfr_monitor_", "jfr_park_", "jfr_safepoint_", "jfr_vmop_");

    private static MappingConfig config;

    @BeforeAll
    static void loadPack() {
        config = MappingLoader.loadResource("mappings/locks-safepoints.yaml");
    }

    private static EventRule ruleFor(String eventName) {
        List<EventRule> matches = config.rules().stream()
                .filter(r -> r.event().equals(eventName))
                .toList();
        assertThat(matches).as("rule for event '%s'", eventName).hasSize(1);
        return matches.get(0);
    }

    private static MetricSpec metricNamed(EventRule rule, String metricName) {
        List<MetricSpec> matches = rule.metrics().stream()
                .filter(m -> m.name().equals(metricName))
                .toList();
        assertThat(matches).as("metric '%s' on rule '%s'", metricName, rule.event()).hasSize(1);
        return matches.get(0);
    }

    // ------------------------------------------------------------------
    // 1. Loads and declares the expected shape
    // ------------------------------------------------------------------

    @Test
    void packParsesWithFiveRules() {
        assertThat(config.rules()).hasSize(5);
        assertThat(config.rules().stream().map(EventRule::event)).containsExactlyInAnyOrder(
                "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark",
                "jdk.SafepointBegin", "jdk.ExecuteVMOperation");
    }

    @Test
    void javaMonitorEnterProducesBlockedSecondsHistogramLabelledByMonitorClass() {
        EventRule rule = ruleFor("jdk.JavaMonitorEnter");
        assertThat(rule.enable().threshold()).isEqualTo(Duration.ofMillis(1));
        assertThat(rule.enable().stackTrace()).isFalse();

        MetricSpec metric = metricNamed(rule, "jfr_monitor_blocked_seconds");
        assertThat(metric.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(metric.value().field()).isEqualTo("duration");
        assertThat(metric.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(metric.value().unit()).isEqualTo(DurationUnit.SECONDS);
        assertThat(metric.labels()).containsExactly(
                new LabelSpec("monitor_class", "monitorClass", LabelKind.CLASS));
        assertThat(metric.maxCardinality()).isEqualTo(20);
        assertThat(metric.buckets()).isNotEmpty().isSorted();
    }

    @Test
    void javaMonitorWaitProducesWaitSecondsHistogramLabelledByClassAndTimedOut() {
        EventRule rule = ruleFor("jdk.JavaMonitorWait");
        assertThat(rule.enable().threshold()).isEqualTo(Duration.ofMillis(1));
        assertThat(rule.enable().stackTrace()).isFalse();

        MetricSpec metric = metricNamed(rule, "jfr_monitor_wait_seconds");
        assertThat(metric.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(metric.value().field()).isEqualTo("duration");
        assertThat(metric.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(metric.value().unit()).isEqualTo(DurationUnit.SECONDS);
        assertThat(metric.labels()).containsExactly(
                new LabelSpec("monitor_class", "monitorClass", LabelKind.CLASS),
                new LabelSpec("timed_out", "timedOut", LabelKind.BOOLEAN));
        assertThat(metric.maxCardinality()).isEqualTo(20);
    }

    @Test
    void threadParkProducesParkSecondsHistogramLabelledByParkedClass() {
        EventRule rule = ruleFor("jdk.ThreadPark");
        assertThat(rule.enable().threshold()).isEqualTo(Duration.ofMillis(1));
        assertThat(rule.enable().stackTrace()).isFalse();

        MetricSpec metric = metricNamed(rule, "jfr_park_seconds");
        assertThat(metric.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(metric.value().field()).isEqualTo("duration");
        assertThat(metric.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(metric.labels()).containsExactly(
                new LabelSpec("parked_class", "parkedClass", LabelKind.CLASS));
        assertThat(metric.maxCardinality()).isEqualTo(20);
    }

    @Test
    void safepointBeginProducesPauseHistogramAndThreadCountGauge() {
        EventRule rule = ruleFor("jdk.SafepointBegin");
        assertThat(rule.enable().threshold()).isEqualTo(Duration.ZERO);
        assertThat(rule.enable().stackTrace()).isFalse();
        assertThat(rule.metrics()).hasSize(2);

        MetricSpec pause = metricNamed(rule, "jfr_safepoint_pause_seconds");
        assertThat(pause.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(pause.value().field()).isEqualTo("duration");
        assertThat(pause.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(pause.value().unit()).isEqualTo(DurationUnit.SECONDS);
        // Deliberately unlabelled: safepointId is unique per event (unbounded, no grouping value).
        assertThat(pause.labels()).isEmpty();

        MetricSpec threads = metricNamed(rule, "jfr_safepoint_threads");
        assertThat(threads.type()).isEqualTo(MetricType.GAUGE);
        assertThat(threads.value().field()).isEqualTo("totalThreadCount");
        assertThat(threads.value().kind()).isEqualTo(ValueKind.NUMBER);
        assertThat(threads.labels()).isEmpty();
    }

    @Test
    void executeVmOperationProducesDurationHistogramLabelledByOperationAndSafepoint() {
        EventRule rule = ruleFor("jdk.ExecuteVMOperation");
        assertThat(rule.enable().threshold()).isEqualTo(Duration.ZERO);
        assertThat(rule.enable().stackTrace()).isFalse();

        MetricSpec metric = metricNamed(rule, "jfr_vmop_duration_seconds");
        assertThat(metric.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(metric.value().field()).isEqualTo("duration");
        assertThat(metric.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(metric.value().unit()).isEqualTo(DurationUnit.SECONDS);
        assertThat(metric.labels()).containsExactly(
                new LabelSpec("operation", "operation", LabelKind.STRING),
                new LabelSpec("safepoint", "safepoint", LabelKind.BOOLEAN));
        // operation/safepoint are both genuinely low-cardinality (a small fixed set of VM
        // operation names x boolean); left under the metric-wide default cap.
        assertThat(metric.maxCardinality()).isEqualTo(MetricSpec.DEFAULT_MAX_CARDINALITY);
    }

    // ------------------------------------------------------------------
    // 2. Namespace guard
    // ------------------------------------------------------------------

    @Test
    void everyMetricNameStaysInsideThisPacksAllowedPrefixes() {
        for (EventRule rule : config.rules()) {
            for (MetricSpec metric : rule.metrics()) {
                assertThat(ALLOWED_PREFIXES.stream().anyMatch(metric.name()::startsWith))
                        .as("metric '%s' (from rule '%s') must start with one of %s",
                                metric.name(), rule.event(), ALLOWED_PREFIXES)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Cardinality caps are actually set where the pack says they are needed
    // ------------------------------------------------------------------

    @Test
    void classLabelledMetricsHaveAConservativeCardinalityCap() {
        assertThat(metricNamed(ruleFor("jdk.JavaMonitorEnter"), "jfr_monitor_blocked_seconds")
                .maxCardinality()).isEqualTo(20);
        assertThat(metricNamed(ruleFor("jdk.JavaMonitorWait"), "jfr_monitor_wait_seconds")
                .maxCardinality()).isEqualTo(20);
        assertThat(metricNamed(ruleFor("jdk.ThreadPark"), "jfr_park_seconds")
                .maxCardinality()).isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // 4. Fixture replay: plausible values, not raw ticks
    // ------------------------------------------------------------------

    @Test
    void replayingTheFixtureProducesPlausibleSecondsNotRawTicks() throws IOException, URISyntaxException {
        Path fixture = Path.of(LocksSafepointsPackTest.class.getResource("/fixtures/sample.jfr").toURI());
        List<RecordedEvent> events = RecordingFile.readAllEvents(fixture);

        Map<String, List<EventRule>> byEvent = new LinkedHashMap<>();
        for (EventRule rule : config.rules()) {
            byEvent.computeIfAbsent(rule.event(), k -> new ArrayList<>()).add(rule);
        }

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        int applied = 0;
        for (RecordedEvent event : events) {
            String eventName = event.getEventType().getName();
            List<EventRule> rules = byEvent.get(eventName);
            if (rules == null) {
                continue;
            }
            RecordedEventView view = new RecordedEventView(event);
            for (EventRule rule : rules) {
                RuleEvaluator.evaluate(rule, view, registry);
                applied++;
            }
        }
        assertThat(applied).as("at least one of our five events must be present in the fixture")
                .isGreaterThan(0);

        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        String body = sb.toString();

        // All five metrics produced at least one sample.
        assertThat(body)
                .contains("jfr_monitor_blocked_seconds_count")
                .contains("jfr_monitor_wait_seconds_count")
                .contains("jfr_park_seconds_count")
                .contains("jfr_safepoint_pause_seconds_count")
                .contains("jfr_safepoint_threads")
                .contains("jfr_vmop_duration_seconds_count");

        // Every duration histogram's _sum is a plausible number of seconds (well under a
        // minute for this tiny fixture workload) and strictly positive for at least one series
        // - not the raw-tick value (which on this JDK/platform is numerically ~= nanoseconds,
        // i.e. many orders of magnitude larger, e.g. millions, if duration had been read via
        // kind: number instead of kind: duration).
        assertPlausibleSecondsSum(body, "jfr_monitor_blocked_seconds_sum");
        assertPlausibleSecondsSum(body, "jfr_monitor_wait_seconds_sum");
        assertPlausibleSecondsSum(body, "jfr_park_seconds_sum");
        assertPlausibleSecondsSum(body, "jfr_safepoint_pause_seconds_sum");
        assertPlausibleSecondsSum(body, "jfr_vmop_duration_seconds_sum");

        // jfr_safepoint_threads is a small integer thread count, not a duration - sanity
        // bound it well below any tick-scale accident too.
        double threadCount = sumLinesStartingWith(body, "jfr_safepoint_threads");
        assertThat(threadCount).isGreaterThan(0).isLessThan(10_000);

        // Total event counts recorded per metric are bounded by the fixture's known population
        // (JavaMonitorEnter(5), JavaMonitorWait(5), ThreadPark(2), SafepointBegin(4),
        // ExecuteVMOperation(9)) - the fixture was recorded without this pack's threshold
        // settings, so replay sees the full population regardless of enable.threshold.
        assertThat(sumLinesStartingWith(body, "jfr_monitor_blocked_seconds_count")).isEqualTo(5);
        assertThat(sumLinesStartingWith(body, "jfr_monitor_wait_seconds_count")).isEqualTo(5);
        assertThat(sumLinesStartingWith(body, "jfr_park_seconds_count")).isEqualTo(2);
        assertThat(sumLinesStartingWith(body, "jfr_safepoint_pause_seconds_count")).isEqualTo(4);
        assertThat(sumLinesStartingWith(body, "jfr_vmop_duration_seconds_count")).isEqualTo(9);
    }

    private static void assertPlausibleSecondsSum(String body, String metricLinePrefix) {
        double total = sumLinesStartingWith(body, metricLinePrefix);
        assertThat(total)
                .as("%s total", metricLinePrefix)
                .isGreaterThan(0)
                .isLessThan(60.0);
    }

    /** Sums the trailing numeric value of every exposition line whose metric name is exactly
     * {@code metricLinePrefix} (optionally followed by a {@code {...}} label block). */
    private static double sumLinesStartingWith(String body, String metricLinePrefix) {
        double total = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith(metricLinePrefix + " ") || line.startsWith(metricLinePrefix + "{")) {
                int lastSpace = line.lastIndexOf(' ');
                total += Double.parseDouble(line.substring(lastSpace + 1).trim());
            }
        }
        return total;
    }

    /** Sanity check referenced by the class javadoc: no metric name collides across packs. */
    @Test
    void metricNamesAreAllDistinct() {
        Set<String> names = new java.util.HashSet<>();
        for (EventRule rule : config.rules()) {
            for (MetricSpec metric : rule.metrics()) {
                assertThat(names.add(metric.name())).as("duplicate metric name '%s'", metric.name()).isTrue();
            }
        }
    }
}
