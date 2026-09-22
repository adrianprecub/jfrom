package io.jfrom.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jfrom.agent.config.DurationUnit;
import io.jfrom.agent.config.MetricSpec;
import io.jfrom.agent.config.ValueKind;
import io.jfrom.agent.config.ValueSpec;
import io.jfrom.agent.metrics.Labels;
import io.jfrom.agent.metrics.MetricType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the frozen contract that every parallel task compiles against. If a worker changes one
 * of these behaviours, it breaks the other tasks in the same wave, so these assertions are
 * deliberately about the contract rather than about any implementation.
 */
class ContractTest {

    @Test
    void histogramRequiresAscendingBuckets() {
        assertThatThrownBy(() -> new MetricSpec(
                        "jfr_x_seconds", MetricType.HISTOGRAM, null,
                        new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS),
                        List.of(), List.of(0.1, 0.05), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly ascending");

        assertThatThrownBy(() -> new MetricSpec(
                        "jfr_x_seconds", MetricType.HISTOGRAM, null,
                        new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS),
                        List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires non-empty 'buckets'");
    }

    @Test
    void onlyACounterMayOmitItsValue() {
        // A bare counter counts events.
        MetricSpec counter = new MetricSpec(
                "jfr_events_total", MetricType.COUNTER, null, null, List.of(), null, 0);
        assertThat(counter.value()).isNull();
        assertThat(counter.help()).isEqualTo("jfr_events_total");
        assertThat(counter.maxCardinality()).isEqualTo(MetricSpec.DEFAULT_MAX_CARDINALITY);

        assertThatThrownBy(() -> new MetricSpec(
                        "jfr_heap_bytes", MetricType.GAUGE, null, null, List.of(), null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a 'value'");
    }

    @Test
    void durationValueDefaultsToSeconds() {
        ValueSpec spec = new ValueSpec("duration", ValueKind.DURATION, null);
        assertThat(spec.unit()).isEqualTo(DurationUnit.SECONDS);
        assertThat(spec.unit().convert(Duration.ofMillis(250))).isEqualTo(0.25);
        assertThat(DurationUnit.MILLISECONDS.convert(Duration.ofMillis(250))).isEqualTo(250.0);
    }

    @Test
    void labelsAreValueTypesAndPreserveOrder() {
        Labels a = Labels.of(Map.of("gc", "G1Full")).with("cause", "System.gc()");
        Labels b = Labels.of(Map.of("gc", "G1Full")).with("cause", "System.gc()");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.asMap().keySet()).containsExactly("gc", "cause");
        assertThat(Labels.empty().isEmpty()).isTrue();
    }
}
