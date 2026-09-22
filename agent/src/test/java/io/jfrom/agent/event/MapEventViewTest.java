package io.jfrom.agent.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Standalone unit tests for the {@link MapEventView} test double, independent of any fixture. */
class MapEventViewTest {

    @Test
    void unsetFieldIsMissing() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").build();

        assertThat(view.hasField("duration")).isFalse();
        assertThat(view.getString("duration")).isNull();
        assertThat(view.getLong("duration")).isZero();
        assertThat(view.getDouble("duration")).isZero();
        assertThat(view.getBoolean("duration")).isFalse();
        assertThat(view.getDuration("duration")).isNull();
        assertThat(view.getClassName("duration")).isNull();
        assertThat(view.getThreadName("duration")).isNull();
    }

    @Test
    void nullFieldIsPresentButEmpty() {
        MapEventView view = MapEventView.builder("jdk.JavaMonitorEnter").nullField("previousOwner").build();

        assertThat(view.hasField("previousOwner")).isTrue();
        assertThat(view.getString("previousOwner")).isNull();
        assertThat(view.getThreadName("previousOwner")).isNull();
    }

    @Test
    void dottedPathIsAnOpaqueKey() {
        MapEventView view =
                MapEventView.builder("jdk.GCHeapSummary").number("heapSpace.committedSize", 1024L).build();

        assertThat(view.hasField("heapSpace.committedSize")).isTrue();
        assertThat(view.getLong("heapSpace.committedSize")).isEqualTo(1024L);
        assertThat(view.hasField("heapSpace")).isFalse();
    }

    @Test
    void typedHelpersRoundTrip() {
        Duration duration = Duration.ofMillis(7);
        MapEventView view = MapEventView.builder("jdk.JavaMonitorEnter")
                .string("cause", "System.gc()")
                .number("weight", 42L)
                .number("fraction", 0.5)
                .bool("timedOut", true)
                .duration("duration", duration)
                .className("monitorClass", "java.lang.Object")
                .threadName("eventThread", "worker-1")
                .build();

        assertThat(view.getString("cause")).isEqualTo("System.gc()");
        assertThat(view.getLong("weight")).isEqualTo(42L);
        assertThat(view.getDouble("fraction")).isEqualTo(0.5);
        assertThat(view.getBoolean("timedOut")).isTrue();
        assertThat(view.getDuration("duration")).isEqualTo(duration);
        assertThat(view.getClassName("monitorClass")).isEqualTo("java.lang.Object");
        assertThat(view.getThreadName("eventThread")).isEqualTo("worker-1");
    }

    @Test
    void threadWithNullJavaNameFallsBackToOsName() {
        MapEventView view =
                MapEventView.builder("jdk.SafepointBegin").thread("eventThread", null, "VM Thread").build();

        assertThat(view.getThreadName("eventThread")).isEqualTo("VM Thread");
    }

    @Test
    void eventNameAndStartTimeAreCarriedThrough() {
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        MapEventView view =
                MapEventView.builder("jdk.GarbageCollection").startTime(now).build();

        assertThat(view.eventName()).isEqualTo("jdk.GarbageCollection");
        assertThat(view.startTime()).isEqualTo(now);
    }
}
