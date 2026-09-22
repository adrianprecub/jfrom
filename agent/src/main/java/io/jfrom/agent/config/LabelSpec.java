package io.jfrom.agent.config;

/**
 * One label on a metric.
 *
 * @param name  the Prometheus label name
 * @param field dotted path into the event supplying the value
 * @param kind  how to render the field as text
 */
public record LabelSpec(String name, String field, LabelKind kind) {

    public LabelSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("label.name is required");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("label.field is required for label '" + name + "'");
        }
        if (kind == null) {
            kind = LabelKind.STRING;
        }
    }
}
