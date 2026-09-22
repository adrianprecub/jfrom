package io.jfrom.agent.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable, order-stable set of label name/value pairs, used as a map key per time series.
 *
 * <p>Insertion order is preserved so exposition output is deterministic and diffable.
 */
public final class Labels {

    private static final Labels EMPTY = new Labels(Collections.emptyMap());

    private final Map<String, String> values;
    private final int hash;

    private Labels(Map<String, String> values) {
        this.values = values;
        this.hash = values.hashCode();
    }

    public static Labels empty() {
        return EMPTY;
    }

    public static Labels of(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        return new Labels(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /** @return a copy with {@code name} set to {@code value}, preserving order. */
    public Labels with(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.put(name, value);
        return new Labels(Collections.unmodifiableMap(copy));
    }

    public Map<String, String> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Labels other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
