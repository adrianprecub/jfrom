package io.jfr2grafana.agent.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.EventRule;
import io.jfr2grafana.agent.config.LabelKind;
import io.jfr2grafana.agent.config.MappingConfig;
import io.jfr2grafana.agent.config.MappingLoader;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.engine.RuleEvaluator;
import io.jfr2grafana.agent.event.RecordedEventView;
import io.jfr2grafana.agent.metrics.DefaultMetricRegistry;
import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the CPU &amp; Threads mapping pack ({@code mappings/cpu-threads.yaml}, T8).
 *
 * <p>Three layers, matching the project's testing strategy: (1) the pack parses and declares the
 * expected metric inventory, (2) replaying the committed {@code sample.jfr} fixture through the
 * real engine pipeline produces sane values, and (3) every metric name in the pack stays inside
 * this pack's namespace ({@code jfr_cpu_}/{@code jfr_thread_}/{@code jfr_threads_}), which is the
 * only thing standing between four concurrently-authored packs and a duplicate-metric-name
 * load failure.
 */
class CpuThreadsPackTest {

    private static final List<String> ALLOWED_PREFIXES = List.of("jfr_cpu_", "jfr_thread_", "jfr_threads_");

    private static MappingConfig config;
    private static List<RecordedEvent> fixtureEvents;

    @BeforeAll
    static void loadPackAndFixture() throws IOException, URISyntaxException {
        config = MappingLoader.loadResource("mappings/cpu-threads.yaml");

        Path fixture = Path.of(CpuThreadsPackTest.class.getResource("/fixtures/sample.jfr").toURI());
        fixtureEvents = RecordingFile.readAllEvents(fixture);
    }

    // ------------------------------------------------------------------
    // 1. The pack parses, with the expected metric names, types and labels.
    // ------------------------------------------------------------------

    @Test
    void packDeclaresThreeRulesForTheThreeCoveredEvents() {
        assertThat(config.rules()).hasSize(3);
        assertThat(config.rules().stream().map(EventRule::event)).containsExactly(
                "jdk.CPULoad", "jdk.JavaThreadStatistics", "jdk.ThreadCPULoad");
    }

    @Test
    void cpuLoadRuleMapsAllThreeFractionsAsPlainGaugesOnAOneSecondPeriod() {
        EventRule rule = ruleFor("jdk.CPULoad");
        assertThat(rule.enable().period()).isEqualTo("1s");
        assertThat(rule.enable().stackTrace()).isFalse();

        assertMetric(rule, "jfr_cpu_jvm_user_ratio", MetricType.GAUGE, "jvmUser");
        assertMetric(rule, "jfr_cpu_jvm_system_ratio", MetricType.GAUGE, "jvmSystem");
        assertMetric(rule, "jfr_cpu_machine_total_ratio", MetricType.GAUGE, "machineTotal");

        // Trap #1: these are fractions (0..1), not percentages - no *100 scaling anywhere in the
        // pack. Nothing to assert directly against the YAML (there is no "multiply" concept in
        // the schema), but the fixture-replay test below pins machineTotal strictly below 1.0,
        // which a stray *100 would violate.
    }

    @Test
    void javaThreadStatisticsRuleTreatsAccumulatedCountAsCounterAbsoluteAndRestAsGauges() {
        EventRule rule = ruleFor("jdk.JavaThreadStatistics");

        assertMetric(rule, "jfr_threads_active", MetricType.GAUGE, "activeCount");
        assertMetric(rule, "jfr_threads_daemon", MetricType.GAUGE, "daemonCount");
        assertMetric(rule, "jfr_threads_peak", MetricType.GAUGE, "peakCount");

        // Trap #2: accumulatedCount is a cumulative JVM total, so it MUST be counter_absolute,
        // never counter - a counter would double-count on every periodic sample.
        assertMetric(rule, "jfr_thread_accumulated_total", MetricType.COUNTER_ABSOLUTE, "accumulatedCount");
    }

    @Test
    void threadCpuLoadRuleLabelsByThreadWithAConservativeCardinalityCap() {
        EventRule rule = ruleFor("jdk.ThreadCPULoad");

        MetricSpec userMetric = metricFor(rule, "jfr_thread_cpu_user_ratio");
        assertThat(userMetric.type()).isEqualTo(MetricType.GAUGE);
        assertThat(userMetric.value().field()).isEqualTo("user");
        assertThat(userMetric.labels()).hasSize(1);
        assertThat(userMetric.labels().get(0).name()).isEqualTo("thread");
        assertThat(userMetric.labels().get(0).field()).isEqualTo("eventThread");
        assertThat(userMetric.labels().get(0).kind()).isEqualTo(LabelKind.THREAD);
        // Trap #3: eventThread is unbounded cardinality; a cap is required, not optional.
        assertThat(userMetric.maxCardinality()).isEqualTo(20);

        MetricSpec systemMetric = metricFor(rule, "jfr_thread_cpu_system_ratio");
        assertThat(systemMetric.value().field()).isEqualTo("system");
        assertThat(systemMetric.maxCardinality()).isEqualTo(20);
    }

    @Test
    void everyRuleDisablesStackTraces() {
        for (EventRule rule : config.rules()) {
            assertThat(rule.enable().stackTrace()).as("stackTrace for %s", rule.event()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 2. Replaying the committed fixture through the real pipeline yields sane values.
    // ------------------------------------------------------------------

    @Test
    void fixtureContainsTheExpectedEventCounts() {
        assertThat(countEvents("jdk.CPULoad")).isEqualTo(4);
        assertThat(countEvents("jdk.JavaThreadStatistics")).isEqualTo(7);
        assertThat(countEvents("jdk.ThreadCPULoad")).isEqualTo(12);
    }

    @Test
    void replayingTheFixtureProducesSaneCpuAndThreadSeries() throws IOException {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();

        for (RecordedEvent event : fixtureEvents) {
            RecordedEventView view = new RecordedEventView(event);
            for (EventRule rule : config.rules()) {
                if (rule.event().equals(view.eventName())) {
                    RuleEvaluator.evaluate(rule, view, registry);
                }
            }
        }

        String body = exposition(registry);

        // machineTotal is a real fraction on every platform (unlike jvmUser/jvmSystem, see
        // below), so pin it strictly between 0 and 1: a stray *100 would push it into the tens,
        // and a broken extractor would leave it at exactly 0.
        double machineTotal = unlabelledGaugeValue(body, "jfr_cpu_machine_total_ratio");
        assertThat(machineTotal).isGreaterThan(0.0).isLessThan(1.0);

        // Deliberately NOT asserted: jfr_cpu_jvm_user_ratio > 0. Design doc addendum #6:
        // jvmUser/jvmSystem report 0.0 on macOS/aarch64, which is where this suite runs. The
        // metric must still be present and non-negative.
        assertThat(body).contains("jfr_cpu_jvm_user_ratio");
        assertThat(body).contains("jfr_cpu_jvm_system_ratio");
        assertThat(unlabelledGaugeValue(body, "jfr_cpu_jvm_user_ratio")).isGreaterThanOrEqualTo(0.0);
        assertThat(unlabelledGaugeValue(body, "jfr_cpu_jvm_system_ratio")).isGreaterThanOrEqualTo(0.0);

        double activeThreads = unlabelledGaugeValue(body, "jfr_threads_active");
        assertThat(activeThreads).isGreaterThan(0.0);

        double daemonThreads = unlabelledGaugeValue(body, "jfr_threads_daemon");
        assertThat(daemonThreads).isGreaterThanOrEqualTo(0.0);

        double peakThreads = unlabelledGaugeValue(body, "jfr_threads_peak");
        assertThat(peakThreads).isGreaterThanOrEqualTo(activeThreads);

        // Cumulative total; must be present, non-negative, and (since it counts every thread
        // ever started, not just live ones) at least as large as the peak live count.
        double accumulated = unlabelledGaugeValue(body, "jfr_thread_accumulated_total");
        assertThat(accumulated).isGreaterThanOrEqualTo(peakThreads);

        // Per-thread CPU load: at least one labelled series for a real thread name, and every
        // value is a plausible fraction (never negative, never above 1 for a single core).
        assertThat(body).contains("jfr_thread_cpu_user_ratio{thread=");
        assertThat(body).contains("jfr_thread_cpu_system_ratio{thread=");
        for (double v : labelledGaugeValues(body, "jfr_thread_cpu_user_ratio")) {
            assertThat(v).isBetween(0.0, 1.0);
        }
        for (double v : labelledGaugeValues(body, "jfr_thread_cpu_system_ratio")) {
            assertThat(v).isBetween(0.0, 1.0);
        }
    }

    // ------------------------------------------------------------------
    // 3. Namespace guard: every metric in this pack stays inside jfr_cpu_/jfr_thread_/jfr_threads_.
    // ------------------------------------------------------------------

    @Test
    void everyMetricNameStartsWithAnAllowedPrefix() {
        for (EventRule rule : config.rules()) {
            for (MetricSpec metric : rule.metrics()) {
                boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(metric.name()::startsWith);
                assertThat(allowed)
                        .as("metric '%s' (from %s) must start with one of %s", metric.name(), rule.event(),
                                ALLOWED_PREFIXES)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static EventRule ruleFor(String event) {
        return config.rules().stream()
                .filter(r -> r.event().equals(event))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule for " + event));
    }

    private static MetricSpec metricFor(EventRule rule, String metricName) {
        return rule.metrics().stream()
                .filter(m -> m.name().equals(metricName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric '" + metricName + "' in rule for " + rule.event()));
    }

    private static void assertMetric(EventRule rule, String metricName, MetricType type, String field) {
        MetricSpec metric = metricFor(rule, metricName);
        assertThat(metric.type()).isEqualTo(type);
        assertThat(metric.value()).isNotNull();
        assertThat(metric.value().field()).isEqualTo(field);
    }

    private static long countEvents(String eventName) {
        return fixtureEvents.stream().filter(e -> e.getEventType().getName().equals(eventName)).count();
    }

    private static String exposition(DefaultMetricRegistry registry) throws IOException {
        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        return sb.toString();
    }

    /** Extracts the value of an unlabelled metric sample, e.g. {@code "jfr_threads_active 7.0"}. */
    private static double unlabelledGaugeValue(String body, String metricName) {
        for (String line : body.split("\n")) {
            if (line.startsWith(metricName + " ")) {
                return Double.parseDouble(line.substring(metricName.length()).trim());
            }
        }
        throw new AssertionError("no unlabelled sample for '" + metricName + "' found in:\n" + body);
    }

    /** Extracts every sample value for a labelled metric, e.g. {@code jfr_thread_cpu_user_ratio{thread="x"} 0.1}. */
    private static List<Double> labelledGaugeValues(String body, String metricName) {
        List<Double> values = new java.util.ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith(metricName + "{")) {
                int lastSpace = line.lastIndexOf(' ');
                values.add(Double.parseDouble(line.substring(lastSpace + 1).trim()));
            }
        }
        assertThat(values).as("no labelled samples for '%s' found in:\n%s", metricName, body).isNotEmpty();
        return values;
    }
}
