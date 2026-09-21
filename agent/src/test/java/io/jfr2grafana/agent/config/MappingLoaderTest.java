package io.jfr2grafana.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingLoaderTest {

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void roundTripsARealisticMultiRuleDocument() {
        MappingConfig config = MappingLoader.loadResource("config/valid-multi-rule.yaml");

        assertThat(config.rules()).hasSize(2);

        EventRule heapRule = config.rules().get(0);
        assertThat(heapRule.event()).isEqualTo("jdk.GCHeapSummary");
        assertThat(heapRule.enable().period()).isEqualTo("everyChunk");
        assertThat(heapRule.enable().stackTrace()).isFalse();
        assertThat(heapRule.enable().threshold()).isNull();
        assertThat(heapRule.filter()).containsExactly(Map.entry("when", "After GC"));
        assertThat(heapRule.metrics()).hasSize(1);

        MetricSpec heapMetric = heapRule.metrics().get(0);
        assertThat(heapMetric.name()).isEqualTo("jfr_heap_used_bytes");
        assertThat(heapMetric.type()).isEqualTo(MetricType.GAUGE);
        assertThat(heapMetric.help()).isEqualTo("Heap bytes in use after GC");
        assertThat(heapMetric.value().field()).isEqualTo("heapUsed");
        assertThat(heapMetric.value().kind()).isEqualTo(ValueKind.NUMBER);
        assertThat(heapMetric.value().unit()).isNull();
        assertThat(heapMetric.labels()).containsExactly(new LabelSpec("gc", "name", LabelKind.STRING));
        assertThat(heapMetric.maxCardinality()).isEqualTo(50);
        assertThat(heapMetric.buckets()).isNull();

        EventRule gcRule = config.rules().get(1);
        assertThat(gcRule.event()).isEqualTo("jdk.GarbageCollection");
        assertThat(gcRule.enable().period()).isNull();
        assertThat(gcRule.enable().threshold()).isEqualTo(Duration.ZERO);
        assertThat(gcRule.enable().stackTrace()).isFalse();
        assertThat(gcRule.filter()).isEmpty();

        MetricSpec gcMetric = gcRule.metrics().get(0);
        assertThat(gcMetric.name()).isEqualTo("jfr_gc_pause_seconds");
        assertThat(gcMetric.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(gcMetric.value().field()).isEqualTo("duration");
        assertThat(gcMetric.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(gcMetric.value().unit()).isEqualTo(DurationUnit.SECONDS);
        assertThat(gcMetric.labels()).containsExactly(
                new LabelSpec("gc", "name", LabelKind.STRING),
                new LabelSpec("cause", "cause", LabelKind.STRING));
        assertThat(gcMetric.buckets()).containsExactly(
                0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1d, 2.5, 5d, 10d);
        assertThat(gcMetric.maxCardinality()).isEqualTo(MetricSpec.DEFAULT_MAX_CARDINALITY);
    }

    @Test
    void loadsFromAFilesystemPath(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("filesystem-pack.yaml");
        Files.writeString(file, """
                rules:
                  - event: jdk.CPULoad
                    metrics:
                      - name: jfr_cpu_events_total
                        type: counter
                """);

        MappingConfig config = MappingLoader.loadPath(file);

        assertThat(config.rules()).hasSize(1);
        MetricSpec metric = config.rules().get(0).metrics().get(0);
        assertThat(metric.name()).isEqualTo("jfr_cpu_events_total");
        assertThat(metric.value()).isNull(); // a bare counter counts events
    }

    @Test
    void loadBundledPacksMergesEveryShippedPackWithoutCollision() {
        // Originally this asserted an empty result, because no packs existed yet. Now that they
        // do, the property worth guarding is the one that actually breaks: the shipped packs are
        // authored independently, and MappingConfig rejects a metric name declared by two of
        // them. Merging them all here is what catches that at build time rather than at startup.
        MappingConfig config = MappingLoader.loadBundledPacks();

        assertThat(config.rules()).isNotEmpty();
        assertThat(config.rules())
                .allSatisfy(rule -> assertThat(rule.event()).startsWith("jdk."));

        List<String> metricNames = config.rules().stream()
                .flatMap(rule -> rule.metrics().stream())
                .map(MetricSpec::name)
                .toList();

        // Duplicates would already have thrown inside merge; this pins the reason if it ever does.
        assertThat(metricNames).doesNotHaveDuplicates();
        assertThat(metricNames).allSatisfy(name -> assertThat(name).startsWith("jfr_"));
    }

    @Test
    void loadResourceOfMissingFileThrowsHelpfulMessage() {
        assertThatThrownBy(() -> MappingLoader.loadResource("mappings/does-not-exist.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("mappings/does-not-exist.yaml")
                .hasMessageContaining("not found");
    }

    // ------------------------------------------------------------------
    // Durations
    // ------------------------------------------------------------------

    @Test
    void everyDurationUnitAndZeroParseCorrectly() {
        assertThat(thresholdOf("10ns")).isEqualTo(Duration.ofNanos(10));
        assertThat(thresholdOf("500us")).isEqualTo(Duration.ofNanos(500_000));
        assertThat(thresholdOf("10ms")).isEqualTo(Duration.ofMillis(10));
        assertThat(thresholdOf("0ms")).isEqualTo(Duration.ZERO);
        assertThat(thresholdOf("1s")).isEqualTo(Duration.ofSeconds(1));
        assertThat(thresholdOf("2m")).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void durationValueDefaultsUnitToSeconds() {
        String yaml = """
                rules:
                  - event: jdk.GarbageCollection
                    metrics:
                      - name: jfr_gc_x_seconds
                        type: histogram
                        value: { field: duration, kind: duration }
                        buckets: [0.1, 0.2]
                """;
        MappingConfig config = MappingLoader.load(new StringReader(yaml), "unit-default.yaml");
        assertThat(config.rules().get(0).metrics().get(0).value().unit()).isEqualTo(DurationUnit.SECONDS);
    }

    private static Duration thresholdOf(String threshold) {
        String yaml = """
                rules:
                  - event: jdk.GarbageCollection
                    enable: { threshold: "%s" }
                    metrics:
                      - name: jfr_x
                        type: counter
                """.formatted(threshold);
        MappingConfig config = MappingLoader.load(new StringReader(yaml), "duration-test.yaml");
        return config.rules().get(0).enable().threshold();
    }

    // ------------------------------------------------------------------
    // Case-insensitive enums and defaults
    // ------------------------------------------------------------------

    @Test
    void enumsAreCaseInsensitiveAndDefaultsApplyWhenOmitted() {
        String yaml = """
                rules:
                  - event: jdk.CPULoad
                    metrics:
                      - name: jfr_cpu_defaults
                        type: GAUGE
                        value: { field: jvmUser, kind: NuMbEr }
                        labels:
                          - { name: core, field: core }
                """;
        MappingConfig config = MappingLoader.load(new StringReader(yaml), "case-test.yaml");

        EventRule rule = config.rules().get(0);
        assertThat(rule.enable()).isEqualTo(EnableSpec.defaults());
        assertThat(rule.filter()).isEmpty();

        MetricSpec metric = rule.metrics().get(0);
        assertThat(metric.type()).isEqualTo(MetricType.GAUGE);
        assertThat(metric.help()).isEqualTo("jfr_cpu_defaults"); // default: help falls back to name
        assertThat(metric.maxCardinality()).isEqualTo(MetricSpec.DEFAULT_MAX_CARDINALITY);
        assertThat(metric.value().kind()).isEqualTo(ValueKind.NUMBER);
        assertThat(metric.labels().get(0).kind()).isEqualTo(LabelKind.STRING); // default label kind
    }

    @Test
    void metricTypeIsCaseInsensitiveForCounterAbsolute() {
        String yaml = """
                rules:
                  - event: jdk.ClassLoadingStatistics
                    metrics:
                      - name: jfr_classes_loaded_total
                        type: Counter_Absolute
                        value: { field: loadedClassCount, kind: number }
                """;
        MappingConfig config = MappingLoader.load(new StringReader(yaml), "counter-absolute.yaml");
        assertThat(config.rules().get(0).metrics().get(0).type()).isEqualTo(MetricType.COUNTER_ABSOLUTE);
    }

    // ------------------------------------------------------------------
    // Merging
    // ------------------------------------------------------------------

    @Test
    void mergesMultipleDocuments() {
        MappingConfig a = MappingLoader.loadResource("config/pack-a.yaml");
        MappingConfig b = MappingLoader.loadResource("config/pack-b.yaml");

        MappingConfig merged = MappingConfig.merge(List.of(a, b));

        assertThat(merged.rules()).hasSize(2);
        assertThat(merged.rules().stream().map(EventRule::event))
                .containsExactlyInAnyOrder("jdk.CPULoad", "jdk.JavaThreadStatistics");
    }

    @Test
    void duplicateMetricNamesAcrossMergedDocumentsFail() {
        MappingConfig a = MappingLoader.loadResource("config/dup-a.yaml");
        MappingConfig b = MappingLoader.loadResource("config/dup-b.yaml");

        assertThatThrownBy(() -> MappingConfig.merge(List.of(a, b)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jfr_shared_metric_total")
                .hasMessageContaining("jdk.CompilerStatistics")
                .hasMessageContaining("jdk.ClassLoadingStatistics");
    }

    // ------------------------------------------------------------------
    // Malformed configs: every error must name the file, the event/metric, and what was wrong.
    // ------------------------------------------------------------------

    @Test
    void unknownMetricTypeFailsWithFileEventAndMetricContext() {
        String yaml = """
                rules:
                  - event: jdk.CPULoad
                    metrics:
                      - name: jfr_cpu_bad_type
                        type: bogus
                        value: { field: jvmUser, kind: number }
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "bad-type.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("bad-type.yaml")
                .hasMessageContaining("jdk.CPULoad")
                .hasMessageContaining("jfr_cpu_bad_type")
                .hasMessageContaining("bogus")
                .hasMessageContaining("unknown value");
    }

    @Test
    void unknownValueKindFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.CPULoad
                    metrics:
                      - name: jfr_cpu_bad_kind
                        type: gauge
                        value: { field: jvmUser, kind: weird }
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "bad-kind.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("bad-kind.yaml")
                .hasMessageContaining("jfr_cpu_bad_kind")
                .hasMessageContaining("weird");
    }

    @Test
    void unknownLabelKindFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.CPULoad
                    metrics:
                      - name: jfr_cpu_bad_label_kind
                        type: gauge
                        value: { field: jvmUser, kind: number }
                        labels:
                          - { name: core, field: core, kind: nope }
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "bad-label-kind.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("bad-label-kind.yaml")
                .hasMessageContaining("jfr_cpu_bad_label_kind")
                .hasMessageContaining("nope");
    }

    @Test
    void histogramMissingBucketsFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.GarbageCollection
                    metrics:
                      - name: jfr_gc_missing_buckets_seconds
                        type: histogram
                        value: { field: duration, kind: duration }
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "missing-buckets.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("missing-buckets.yaml")
                .hasMessageContaining("jdk.GarbageCollection")
                .hasMessageContaining("jfr_gc_missing_buckets_seconds")
                .hasMessageContaining("requires non-empty 'buckets'");
    }

    @Test
    void nonAscendingBucketsFailWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.GarbageCollection
                    metrics:
                      - name: jfr_gc_bad_buckets_seconds
                        type: histogram
                        value: { field: duration, kind: duration }
                        buckets: [0.1, 0.05]
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "bad-buckets.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("bad-buckets.yaml")
                .hasMessageContaining("jfr_gc_bad_buckets_seconds")
                .hasMessageContaining("strictly ascending");
    }

    @Test
    void missingMetricNameFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.CPULoad
                    metrics:
                      - type: gauge
                        value: { field: jvmUser, kind: number }
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "missing-name.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("missing-name.yaml")
                .hasMessageContaining("jdk.CPULoad")
                .hasMessageContaining("metric.name is required");
    }

    @Test
    void missingEventFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - metrics:
                      - name: jfr_x
                        type: counter
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "missing-event.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("missing-event.yaml")
                .hasMessageContaining("rule.event is required");
    }

    @Test
    void ruleWithNoMetricsFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.CPULoad
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "no-metrics.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("no-metrics.yaml")
                .hasMessageContaining("jdk.CPULoad")
                .hasMessageContaining("declares no metrics");
    }

    @Test
    void malformedDurationFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.GarbageCollection
                    enable: { threshold: "not-a-duration" }
                    metrics:
                      - name: jfr_x
                        type: counter
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "bad-duration.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("bad-duration.yaml")
                .hasMessageContaining("jdk.GarbageCollection")
                .hasMessageContaining("not a valid duration")
                .hasMessageContaining("not-a-duration");
    }

    @Test
    void malformedEnablePeriodFailsWithHelpfulMessage() {
        String yaml = """
                rules:
                  - event: jdk.GCHeapSummary
                    enable: { period: "whenever" }
                    metrics:
                      - name: jfr_x
                        type: counter
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "bad-period.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("bad-period.yaml")
                .hasMessageContaining("whenever")
                .hasMessageContaining("everyChunk");
    }

    @Test
    void yamlThatIsNotAMappingFailsWithHelpfulMessage() {
        String yaml = """
                - 1
                - 2
                """;
        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "not-a-mapping.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("not-a-mapping.yaml")
                .hasMessageContaining("expected the document to be a YAML mapping");
    }

    @Test
    void unsafeYamlTypeInstantiationAttemptIsRejected() {
        String yaml = "!!java.net.URL [\"http://example.com\"]";

        assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml), "unsafe.yaml"))
                .isInstanceOf(MappingLoader.MappingLoadException.class)
                .hasMessageContaining("unsafe.yaml")
                .hasMessageContaining("failed to parse YAML");
    }
}
