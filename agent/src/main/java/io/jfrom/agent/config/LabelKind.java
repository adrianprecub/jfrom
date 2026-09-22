package io.jfrom.agent.config;

/** How to render an event field as a label value. */
public enum LabelKind {
    STRING,
    /** A {@code RecordedClass} field, rendered as its class name. */
    CLASS,
    /** A {@code RecordedThread} field, rendered as its Java thread name. */
    THREAD,
    BOOLEAN,
    INT
}
