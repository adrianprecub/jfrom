package io.jfr2grafana.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.DurationUnit;
import io.jfr2grafana.agent.config.ValueKind;
import io.jfr2grafana.agent.config.ValueSpec;
import io.jfr2grafana.agent.event.MapEventView;
import java.time.Duration;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ValueExtractorTest {

    @Test
    void numberKindReadsViaGetDouble() {
        MapEventView view = MapEventView.builder("jdk.GCHeapSummary").number("heapUsed", 12345L).build();
        ValueSpec spec = new ValueSpec("heapUsed", ValueKind.NUMBER, null);

        assertThat(ValueExtractor.extract(spec, view)).isEqualTo(OptionalDouble.of(12345.0));
    }

    @Test
    void numberKindWidensFloatFieldsLikeCpuLoadJvmUser() {
        // jdk.CPULoad.jvmUser is a float; getLong() throws on RecordedEvent for this field, so
        // NUMBER must go through getDouble(), never getLong().
        MapEventView view = MapEventView.builder("jdk.CPULoad").number("jvmUser", 0.42).build();
        ValueSpec spec = new ValueSpec("jvmUser", ValueKind.NUMBER, null);

        OptionalDouble result = ValueExtractor.extract(spec, view);
        assertThat(result).isPresent();
        assertThat(result.getAsDouble()).isEqualTo(0.42);
    }

    @Test
    void durationKindConvertsViaGetDurationNotRawNumber() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection")
                .duration("duration", Duration.ofMillis(250))
                .build();
        ValueSpec spec = new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS);

        assertThat(ValueExtractor.extract(spec, view)).isEqualTo(OptionalDouble.of(0.25));
    }

    @Test
    void durationKindHonoursUnit() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection")
                .duration("duration", Duration.ofMillis(250))
                .build();
        ValueSpec spec = new ValueSpec("duration", ValueKind.DURATION, DurationUnit.MILLISECONDS);

        assertThat(ValueExtractor.extract(spec, view).getAsDouble()).isEqualTo(250.0);
    }

    @Test
    void missingNumberFieldYieldsNoSampleRatherThanZero() {
        MapEventView view = MapEventView.builder("jdk.GCHeapSummary").build();
        ValueSpec spec = new ValueSpec("heapUsed", ValueKind.NUMBER, null);

        assertThat(ValueExtractor.extract(spec, view)).isEmpty();
    }

    @Test
    void missingDurationFieldYieldsNoSample() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").build();
        ValueSpec spec = new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS);

        assertThat(ValueExtractor.extract(spec, view)).isEmpty();
    }

    @Test
    void presentButWrongTypedDurationFieldYieldsNoSample() {
        // Field is present (hasField true) but not a real Duration - mirrors a field that
        // exists but isn't a @Timespan on the running JDK.
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").number("duration", 5).build();
        ValueSpec spec = new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS);

        assertThat(ValueExtractor.extract(spec, view)).isEmpty();
    }

    @Test
    void legitimateZeroValueIsStillASample() {
        MapEventView view = MapEventView.builder("jdk.GCHeapSummary").number("heapUsed", 0L).build();
        ValueSpec spec = new ValueSpec("heapUsed", ValueKind.NUMBER, null);

        assertThat(ValueExtractor.extract(spec, view)).isEqualTo(OptionalDouble.of(0.0));
    }
}
