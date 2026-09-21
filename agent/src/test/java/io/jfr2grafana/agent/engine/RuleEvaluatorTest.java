package io.jfr2grafana.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.DurationUnit;
import io.jfr2grafana.agent.config.EnableSpec;
import io.jfr2grafana.agent.config.EventRule;
import io.jfr2grafana.agent.config.LabelKind;
import io.jfr2grafana.agent.config.LabelSpec;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.config.ValueKind;
import io.jfr2grafana.agent.config.ValueSpec;
import io.jfr2grafana.agent.event.MapEventView;
import io.jfr2grafana.agent.metrics.DefaultMetricRegistry;
import io.jfr2grafana.agent.metrics.MetricRegistry;
import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTest {

    private String exposition(MetricRegistry registry) throws IOException {
        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        return sb.toString();
    }

    @Test
    void filterMismatchProducesNoSample() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_heap_used_bytes", MetricType.GAUGE, null,
                new ValueSpec("heapUsed", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("jdk.GCHeapSummary", EnableSpec.defaults(),
                Map.of("when", "After GC"), List.of(metric));

        MapEventView view = MapEventView.builder("jdk.GCHeapSummary")
                .string("when", "Before GC")
                .number("heapUsed", 999L)
                .build();

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, view, registry);

        assertThat(exposition(registry)).doesNotContain("jfr_heap_used_bytes");
    }

    @Test
    void filterMatchProducesSample() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_heap_used_bytes", MetricType.GAUGE, null,
                new ValueSpec("heapUsed", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("jdk.GCHeapSummary", EnableSpec.defaults(),
                Map.of("when", "After GC"), List.of(metric));

        MapEventView view = MapEventView.builder("jdk.GCHeapSummary")
                .string("when", "After GC")
                .number("heapUsed", 999L)
                .build();

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, view, registry);

        assertThat(exposition(registry)).contains("jfr_heap_used_bytes 999");
    }

    @Test
    void multiMetricRuleProducesEachMetric() throws IOException {
        MetricSpec heap = new MetricSpec("jfr_heap_used_bytes", MetricType.GAUGE, null,
                new ValueSpec("heapUsed", ValueKind.NUMBER, null), List.of(), null, 0);
        MetricSpec events = new MetricSpec("jfr_heap_events_total", MetricType.COUNTER, null,
                null, List.of(), null, 0);
        EventRule rule = new EventRule("jdk.GCHeapSummary", EnableSpec.defaults(), Map.of(),
                List.of(heap, events));

        MapEventView view = MapEventView.builder("jdk.GCHeapSummary").number("heapUsed", 42L).build();

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, view, registry);

        String text = exposition(registry);
        assertThat(text).contains("jfr_heap_used_bytes 42").contains("jfr_heap_events_total 1");
    }

    @Test
    void gaugeUsesSetGaugeSemantics() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_x", MetricType.GAUGE, null,
                new ValueSpec("v", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").number("v", 10L).build(), registry);
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").number("v", 3L).build(), registry);

        // Last value wins.
        assertThat(exposition(registry)).contains("jfr_x 3");
    }

    @Test
    void counterAddsValueAcrossEvents() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_x_total", MetricType.COUNTER, null,
                new ValueSpec("v", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").number("v", 10L).build(), registry);
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").number("v", 3L).build(), registry);

        assertThat(exposition(registry)).contains("jfr_x_total 13");
    }

    @Test
    void counterWithNoValueSpecCountsEvents() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_x_events_total", MetricType.COUNTER, null,
                null, List.of(), null, 0);
        EventRule rule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").build(), registry);
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").build(), registry);
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").build(), registry);

        assertThat(exposition(registry)).contains("jfr_x_events_total 3");
    }

    @Test
    void counterAbsoluteSetsRatherThanAdds() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_loaded_classes_total", MetricType.COUNTER_ABSOLUTE, null,
                new ValueSpec("loadedClassCount", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("jdk.ClassLoadingStatistics", EnableSpec.defaults(), Map.of(),
                List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule,
                MapEventView.builder("jdk.ClassLoadingStatistics").number("loadedClassCount", 500L).build(),
                registry);
        RuleEvaluator.evaluate(rule,
                MapEventView.builder("jdk.ClassLoadingStatistics").number("loadedClassCount", 600L).build(),
                registry);

        assertThat(exposition(registry)).contains("jfr_loaded_classes_total 600");
    }

    @Test
    void counterAbsoluteIgnoresDecreasingValues() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_loaded_classes_total", MetricType.COUNTER_ABSOLUTE, null,
                new ValueSpec("loadedClassCount", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("jdk.ClassLoadingStatistics", EnableSpec.defaults(), Map.of(),
                List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule,
                MapEventView.builder("jdk.ClassLoadingStatistics").number("loadedClassCount", 600L).build(),
                registry);
        RuleEvaluator.evaluate(rule,
                MapEventView.builder("jdk.ClassLoadingStatistics").number("loadedClassCount", 500L).build(),
                registry);

        assertThat(exposition(registry)).contains("jfr_loaded_classes_total 600");
    }

    @Test
    void histogramObservesValueWithLabels() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_gc_pause_seconds", MetricType.HISTOGRAM, null,
                new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS),
                List.of(new LabelSpec("gc", "name", LabelKind.STRING)),
                List.of(0.01, 0.1, 1.0), 0);
        EventRule rule = new EventRule("jdk.GarbageCollection", EnableSpec.defaults(), Map.of(),
                List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, MapEventView.builder("jdk.GarbageCollection")
                .string("name", "G1New")
                .duration("duration", Duration.ofMillis(5))
                .build(), registry);

        String text = exposition(registry);
        assertThat(text).contains("jfr_gc_pause_seconds_count{gc=\"G1New\"} 1");
        assertThat(text).contains("jfr_gc_pause_seconds_sum{gc=\"G1New\"} 0.005");
    }

    @Test
    void ruleWithAbsentValueFieldProducesNoSample() throws IOException {
        MetricSpec metric = new MetricSpec("jfr_missing_bytes", MetricType.GAUGE, null,
                new ValueSpec("doesNotExist", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(metric));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").build(), registry);

        assertThat(exposition(registry)).doesNotContain("jfr_missing_bytes");
    }

    @Test
    void oneMetricsAbsentFieldDoesNotBlockAnotherMetricInTheSameRule() throws IOException {
        MetricSpec present = new MetricSpec("jfr_present", MetricType.GAUGE, null,
                new ValueSpec("v", ValueKind.NUMBER, null), List.of(), null, 0);
        MetricSpec absent = new MetricSpec("jfr_absent", MetricType.GAUGE, null,
                new ValueSpec("nope", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule rule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(present, absent));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        RuleEvaluator.evaluate(rule, MapEventView.builder("evt").number("v", 7L).build(), registry);

        String text = exposition(registry);
        assertThat(text).contains("jfr_present 7");
        assertThat(text).doesNotContain("jfr_absent");
    }
}
