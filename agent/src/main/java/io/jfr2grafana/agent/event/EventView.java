package io.jfr2grafana.agent.event;

import java.time.Duration;
import java.time.Instant;

/**
 * A read-only view over a single JFR event.
 *
 * <p>This interface exists so the mapping engine never touches
 * {@code jdk.jfr.consumer.RecordedEvent} directly. {@code RecordedEvent} has no public
 * constructor, so rule logic tested against it would need a real recording; against this
 * interface a test can supply a plain map.
 *
 * <p><b>Field paths</b> may be dotted to reach into nested JFR structs, e.g.
 * {@code "heapSpace.committedSize"} on {@code jdk.GCHeapSummary}. This is verified to work
 * on the real {@code RecordedEvent} API.
 *
 * <p><b>Missing fields never throw.</b> Accessors return the documented empty value when the
 * path is absent, so a mapping pack referring to a field that does not exist on the running
 * JDK degrades to "no sample" rather than killing the stream thread.
 */
public interface EventView {

    /** Fully qualified JFR event name, e.g. {@code "jdk.GarbageCollection"}. */
    String eventName();

    /** True if {@code path} resolves to a present field on this event. */
    boolean hasField(String path);

    /** @return the string value, or {@code null} if absent. */
    String getString(String path);

    /** @return the numeric value widened to long, or {@code 0} if absent. */
    long getLong(String path);

    /** @return the numeric value widened to double, or {@code 0.0} if absent. */
    double getDouble(String path);

    /** @return {@code false} if absent. */
    boolean getBoolean(String path);

    /**
     * Reads a JFR {@code @Timespan} field as a real duration.
     *
     * <p>Required for TICKS-encoded fields such as {@code duration} and {@code sumOfPauses}:
     * {@code getLong("duration")} returns raw ticks, which is meaningless as a wall-clock value.
     *
     * @return the duration, or {@code null} if absent.
     */
    Duration getDuration(String path);

    /** Resolves a {@code RecordedClass} field to its class name, or {@code null} if absent. */
    String getClassName(String path);

    /** Resolves a {@code RecordedThread} field to its Java thread name, or {@code null} if absent. */
    String getThreadName(String path);

    /** Event start timestamp. */
    Instant startTime();
}
