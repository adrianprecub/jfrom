package io.jfrom.agent.config;

/**
 * Which event field supplies a metric's value, and how to read it.
 *
 * @param field dotted path into the event, e.g. {@code "heapUsed"} or {@code "heapSpace.committedSize"}
 * @param kind  how to interpret the field
 * @param unit  target unit when {@code kind} is {@link ValueKind#DURATION}; ignored otherwise
 */
public record ValueSpec(String field, ValueKind kind, DurationUnit unit) {

    public ValueSpec {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("value.field is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("value.kind is required for field '" + field + "'");
        }
        if (kind == ValueKind.DURATION && unit == null) {
            unit = DurationUnit.SECONDS;
        }
    }
}
