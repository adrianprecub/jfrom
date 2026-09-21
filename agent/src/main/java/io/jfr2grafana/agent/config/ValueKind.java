package io.jfr2grafana.agent.config;

/** How to read a value out of an event field. */
public enum ValueKind {
    /** A plain numeric field, read via {@code getDouble}. Supports dotted nested paths. */
    NUMBER,
    /**
     * A JFR {@code @Timespan} field, read via {@code getDuration} and converted per
     * {@link DurationUnit}. Required for TICKS-encoded fields like {@code duration};
     * reading those as a raw long yields ticks, not time.
     */
    DURATION
}
