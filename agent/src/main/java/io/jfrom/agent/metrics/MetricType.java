package io.jfrom.agent.metrics;

/**
 * How a mapped value becomes a metric.
 *
 * <p>The {@link #COUNTER} / {@link #COUNTER_ABSOLUTE} distinction is the most error-prone part
 * of a mapping pack: several JFR events report a value the JVM has <em>already accumulated</em>
 * since startup. Treating one of those as a delta produces a rate curve that looks plausible
 * and is completely wrong, so every rule must state which it is.
 */
public enum MetricType {

    /** Last value wins for the label set. For periodic snapshot events. */
    GAUGE,

    /** Add the extracted value to the running total. With no value spec, adds 1 (an event rate). */
    COUNTER,

    /**
     * The field is already a JVM cumulative total, so <em>set</em> rather than add.
     * Applies to {@code ClassLoadingStatistics.loadedClassCount},
     * {@code CompilerStatistics.compileCount}, {@code ExceptionStatistics.throwables}, and
     * {@code JavaThreadStatistics.accumulatedCount}.
     */
    COUNTER_ABSOLUTE,

    /** Observe the value into fixed buckets; exposes {@code _bucket}, {@code _sum}, {@code _count}. */
    HISTOGRAM
}
