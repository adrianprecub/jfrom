package io.jfr2grafana.agent.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.LabelKind;
import io.jfr2grafana.agent.config.LabelSpec;
import io.jfr2grafana.agent.config.MappingConfig;
import io.jfr2grafana.agent.config.MappingLoader;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.config.ValueKind;
import io.jfr2grafana.agent.engine.RuleEvaluator;
import io.jfr2grafana.agent.event.RecordedEventView;
import io.jfr2grafana.agent.metrics.DefaultMetricRegistry;
import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Memory &amp; GC mapping pack ({@code mappings/memory-gc.yaml}):
 *
 * <ol>
 *   <li>the pack parses and declares the expected metrics, types and labels;
 *   <li>replaying the committed {@code fixtures/sample.jfr} recording through
 *       {@link RuleEvaluator} into a {@link DefaultMetricRegistry} produces real, sane-valued
 *       series;
 *   <li>every metric name stays inside this pack's namespace slice ({@code jfr_heap_},
 *       {@code jfr_gc_}, {@code jfr_metaspace_}, {@code jfr_allocation_}), guarding the
 *       cross-pack duplicate-name contract enforced by {@link MappingConfig}.
 * </ol>
 */
class MemoryGcPackTest {

    private static final Set<String> ALLOWED_PREFIXES =
            Set.of("jfr_heap_", "jfr_gc_", "jfr_metaspace_", "jfr_allocation_");

    private static MappingConfig config;
    private static List<RecordedEvent> fixtureEvents;

    @BeforeAll
    static void loadPackAndFixture() throws IOException, URISyntaxException {
        config = MappingLoader.loadResource("mappings/memory-gc.yaml");

        Path fixture = Path.of(MemoryGcPackTest.class.getResource("/fixtures/sample.jfr").toURI());
        fixtureEvents = RecordingFile.readAllEvents(fixture);
    }

    // ------------------------------------------------------------------
    // 1. The pack parses with the expected shape.
    // ------------------------------------------------------------------

    @Test
    void packDeclaresFiveRulesCoveringAllFiveTargetEvents() {
        assertThat(config.rules()).hasSize(5);
        List<String> events = config.rules().stream().map(io.jfr2grafana.agent.config.EventRule::event).toList();
        assertThat(events).containsExactlyInAnyOrder(
                "jdk.GCHeapSummary",
                "jdk.GarbageCollection",
                "jdk.GCPhasePause",
                "jdk.MetaspaceSummary",
                "jdk.ObjectAllocationSample");
    }

    @Test
    void heapSummaryRuleFiltersToAfterGcAndReportsUsedAndCommitted() {
        var rule = ruleFor("jdk.GCHeapSummary");
        assertThat(rule.filter()).containsExactly(java.util.Map.entry("when", "After GC"));

        MetricSpec used = metric(rule, "jfr_heap_used_bytes");
        assertThat(used.type()).isEqualTo(MetricType.GAUGE);
        assertThat(used.value().field()).isEqualTo("heapUsed");
        assertThat(used.value().kind()).isEqualTo(ValueKind.NUMBER);

        MetricSpec committed = metric(rule, "jfr_heap_committed_bytes");
        assertThat(committed.type()).isEqualTo(MetricType.GAUGE);
        assertThat(committed.value().field()).isEqualTo("heapSpace.committedSize");
    }

    @Test
    void garbageCollectionRuleUsesDurationKindForAllTimespanFields() {
        var rule = ruleFor("jdk.GarbageCollection");

        MetricSpec pause = metric(rule, "jfr_gc_pause_seconds");
        assertThat(pause.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(pause.value().field()).isEqualTo("duration");
        assertThat(pause.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(pause.buckets()).isNotEmpty().isSorted();
        assertThat(pause.labels()).containsExactly(
                new LabelSpec("gc", "name", LabelKind.STRING),
                new LabelSpec("cause", "cause", LabelKind.STRING));

        MetricSpec count = metric(rule, "jfr_gc_count_total");
        assertThat(count.type()).isEqualTo(MetricType.COUNTER);
        assertThat(count.value()).isNull(); // counts events

        MetricSpec longest = metric(rule, "jfr_gc_longest_pause_seconds");
        assertThat(longest.type()).isEqualTo(MetricType.GAUGE);
        assertThat(longest.value().field()).isEqualTo("longestPause");
        assertThat(longest.value().kind()).isEqualTo(ValueKind.DURATION);

        MetricSpec sumOfPauses = metric(rule, "jfr_gc_sum_of_pauses_seconds_total");
        assertThat(sumOfPauses.type()).isEqualTo(MetricType.COUNTER);
        assertThat(sumOfPauses.value().field()).isEqualTo("sumOfPauses");
        assertThat(sumOfPauses.value().kind()).isEqualTo(ValueKind.DURATION);
    }

    @Test
    void gcPhasePauseRuleUsesDurationKindAndLabelsByPhaseName() {
        var rule = ruleFor("jdk.GCPhasePause");
        MetricSpec phase = metric(rule, "jfr_gc_phase_pause_seconds");
        assertThat(phase.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(phase.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(phase.labels()).containsExactly(new LabelSpec("phase", "name", LabelKind.STRING));
    }

    @Test
    void metaspaceRuleFiltersToAfterGcAndUsesDottedNestedPaths() {
        var rule = ruleFor("jdk.MetaspaceSummary");
        assertThat(rule.filter()).containsExactly(java.util.Map.entry("when", "After GC"));

        assertThat(metric(rule, "jfr_metaspace_used_bytes").value().field()).isEqualTo("metaspace.used");
        assertThat(metric(rule, "jfr_metaspace_committed_bytes").value().field()).isEqualTo("metaspace.committed");
        assertThat(metric(rule, "jfr_metaspace_class_used_bytes").value().field()).isEqualTo("classSpace.used");
        assertThat(metric(rule, "jfr_metaspace_class_committed_bytes").value().field())
                .isEqualTo("classSpace.committed");
    }

    @Test
    void allocationSampleRuleSumsEstimatedWeightWithACardinalityGuard() {
        var rule = ruleFor("jdk.ObjectAllocationSample");
        MetricSpec bytes = metric(rule, "jfr_allocation_bytes_total");
        assertThat(bytes.type()).isEqualTo(MetricType.COUNTER);
        assertThat(bytes.value().field()).isEqualTo("weight");
        assertThat(bytes.labels()).containsExactly(new LabelSpec("object_class", "objectClass", LabelKind.CLASS));
        assertThat(bytes.maxCardinality()).isEqualTo(50);
    }

    // ------------------------------------------------------------------
    // 2. Replay the fixture and assert sane values.
    // ------------------------------------------------------------------

    @Test
    void replayingTheFixtureProducesSaneValuesForEveryMetric() throws IOException {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        replayFixtureInto(registry);

        String body = exposition(registry);

        // Heap: used > 0 and committed >= used, on a real heap snapshot.
        double heapUsed = sampleValue(body, "jfr_heap_used_bytes");
        double heapCommitted = sampleValue(body, "jfr_heap_committed_bytes");
        assertThat(heapUsed).isGreaterThan(0);
        assertThat(heapCommitted).isGreaterThanOrEqualTo(heapUsed);

        // GC: the fixture has 2 jdk.GarbageCollection events, both "G1Full" / "System.gc()".
        assertThat(sampleValue(body, "jfr_gc_count_total")).isEqualTo(2.0);
        assertThat(sampleValue(body, "jfr_gc_pause_seconds_count")).isEqualTo(2.0);

        // A GC pause is microseconds-to-seconds. 10^9 would mean ticks were read as a raw number.
        double pauseSum = sampleValue(body, "jfr_gc_pause_seconds_sum");
        assertThat(pauseSum).isGreaterThan(0).isLessThan(5.0);

        double longestPause = sampleValue(body, "jfr_gc_longest_pause_seconds");
        assertThat(longestPause).isGreaterThan(0).isLessThan(5.0);

        double sumOfPauses = sampleValue(body, "jfr_gc_sum_of_pauses_seconds_total");
        assertThat(sumOfPauses).isGreaterThan(0).isLessThan(5.0);

        // GCPhasePause: 2 events in the fixture, each a plausible sub-second pause.
        assertThat(sampleValue(body, "jfr_gc_phase_pause_seconds_count")).isEqualTo(2.0);
        double phasePauseSum = sampleValue(body, "jfr_gc_phase_pause_seconds_sum");
        assertThat(phasePauseSum).isGreaterThan(0).isLessThan(5.0);

        // Metaspace: used > 0 and committed >= used.
        double metaspaceUsed = sampleValue(body, "jfr_metaspace_used_bytes");
        double metaspaceCommitted = sampleValue(body, "jfr_metaspace_committed_bytes");
        assertThat(metaspaceUsed).isGreaterThan(0);
        assertThat(metaspaceCommitted).isGreaterThanOrEqualTo(metaspaceUsed);
        assertThat(sampleValue(body, "jfr_metaspace_class_used_bytes")).isGreaterThan(0);
        assertThat(sampleValue(body, "jfr_metaspace_class_committed_bytes")).isGreaterThan(0);

        // Allocation: 30 ObjectAllocationSample events in the fixture, summed weight > 0.
        assertThat(bodyContains(body, "jfr_allocation_bytes_total")).isTrue();
        assertThat(totalAcrossSeries(body, "jfr_allocation_bytes_total")).isGreaterThan(0);
    }

    @Test
    void everyMetricNameStaysInsideThisPacksNamespace() {
        for (var rule : config.rules()) {
            for (MetricSpec metric : rule.metrics()) {
                assertThat(ALLOWED_PREFIXES.stream().anyMatch(metric.name()::startsWith))
                        .as("metric '%s' from rule '%s' must start with one of %s",
                                metric.name(), rule.event(), ALLOWED_PREFIXES)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static io.jfr2grafana.agent.config.EventRule ruleFor(String event) {
        return config.rules().stream()
                .filter(r -> r.event().equals(event))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule for event '" + event + "'"));
    }

    private static MetricSpec metric(io.jfr2grafana.agent.config.EventRule rule, String name) {
        return rule.metrics().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("rule for '" + rule.event() + "' has no metric '" + name + "'"));
    }

    private static void replayFixtureInto(DefaultMetricRegistry registry) {
        for (RecordedEvent event : fixtureEvents) {
            String eventName = event.getEventType().getName();
            List<io.jfr2grafana.agent.config.EventRule> matching = new ArrayList<>();
            for (var rule : config.rules()) {
                if (rule.event().equals(eventName)) {
                    matching.add(rule);
                }
            }
            if (matching.isEmpty()) {
                continue;
            }
            RecordedEventView view = new RecordedEventView(event);
            for (var rule : matching) {
                RuleEvaluator.evaluate(rule, view, registry);
            }
        }
    }

    private static String exposition(DefaultMetricRegistry registry) throws IOException {
        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        return sb.toString();
    }

    private static boolean bodyContains(String body, String metricPrefix) {
        return body.lines().anyMatch(l -> l.startsWith(metricPrefix + " ") || l.startsWith(metricPrefix + "{"));
    }

    /** Returns the first sample line's value for {@code metricName} (bare, no labels, or the sole series). */
    private static double sampleValue(String body, String metricName) {
        for (String line : body.split("\n")) {
            if (line.startsWith(metricName + " ") || line.startsWith(metricName + "{")) {
                return parseTrailingValue(line);
            }
        }
        throw new AssertionError("no sample for '" + metricName + "' found in:\n" + body);
    }

    /** Sums every series' value for a (possibly multi-labelled) metric. */
    private static double totalAcrossSeries(String body, String metricName) {
        double total = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith(metricName + " ") || line.startsWith(metricName + "{")) {
                total += parseTrailingValue(line);
            }
        }
        return total;
    }

    private static double parseTrailingValue(String line) {
        int lastSpace = line.lastIndexOf(' ');
        return Double.parseDouble(line.substring(lastSpace + 1).trim());
    }
}
