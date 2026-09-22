package io.jfrom.agent.event;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A plain-{@code Map}-backed {@link EventView} test double.
 *
 * <p>Lets rule/mapping tests exercise the same accessor contract as {@link RecordedEventView}
 * without a real JFR recording. Field paths may be dotted (e.g. {@code "heapSpace.committedSize"})
 * exactly as on a real event; this class treats the dotted string as an opaque map key rather
 * than walking a nested structure, which is sufficient because callers always address a field by
 * its full path.
 *
 * <p>Use {@link #builder(String)} to construct one. A field that was never set behaves as
 * "missing" (every accessor returns its documented empty value); {@link Builder#nullField(String)}
 * marks a field as present-but-null, mirroring a real JFR object field (e.g. a
 * {@code RecordedThread}) that exists but carries no value.
 */
public final class MapEventView implements EventView {

    private final String eventName;
    private final Instant startTime;
    private final Map<String, Object> fields;

    private MapEventView(String eventName, Instant startTime, Map<String, Object> fields) {
        this.eventName = eventName;
        this.startTime = startTime;
        this.fields = fields;
    }

    public static Builder builder(String eventName) {
        return new Builder(eventName);
    }

    @Override
    public String eventName() {
        return eventName;
    }

    @Override
    public boolean hasField(String path) {
        return fields.containsKey(path);
    }

    private Object raw(String path) {
        return fields.get(path);
    }

    @Override
    public String getString(String path) {
        Object v = raw(path);
        return v instanceof String s ? s : null;
    }

    @Override
    public long getLong(String path) {
        Object v = raw(path);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    public double getDouble(String path) {
        Object v = raw(path);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    @Override
    public boolean getBoolean(String path) {
        Object v = raw(path);
        return v instanceof Boolean b && b;
    }

    @Override
    public Duration getDuration(String path) {
        Object v = raw(path);
        return v instanceof Duration d ? d : null;
    }

    @Override
    public String getClassName(String path) {
        Object v = raw(path);
        if (v instanceof ClassRef c) {
            return c.name();
        }
        return v instanceof String s ? s : null;
    }

    @Override
    public String getThreadName(String path) {
        Object v = raw(path);
        if (v instanceof ThreadRef t) {
            return t.javaName() != null ? t.javaName() : t.osName();
        }
        return v instanceof String s ? s : null;
    }

    @Override
    public Instant startTime() {
        return startTime;
    }

    /** Stand-in for a {@code RecordedClass}-valued field. */
    public record ClassRef(String name) {}

    /**
     * Stand-in for a {@code RecordedThread}-valued field. {@code javaName} may be {@code null}
     * to model a VM-internal thread, in which case {@link MapEventView#getThreadName} falls back
     * to {@code osName}, matching {@link RecordedEventView}.
     */
    public record ThreadRef(String javaName, String osName) {}

    public static final class Builder {
        private final String eventName;
        private Instant startTime = Instant.now();
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder(String eventName) {
            this.eventName = Objects.requireNonNull(eventName, "eventName");
        }

        public Builder startTime(Instant startTime) {
            this.startTime = Objects.requireNonNull(startTime, "startTime");
            return this;
        }

        /** Sets an arbitrary field value; prefer the typed helpers below when one fits. */
        public Builder field(String path, Object value) {
            fields.put(path, value);
            return this;
        }

        public Builder string(String path, String value) {
            return field(path, value);
        }

        public Builder number(String path, long value) {
            return field(path, value);
        }

        public Builder number(String path, double value) {
            return field(path, value);
        }

        public Builder bool(String path, boolean value) {
            return field(path, value);
        }

        public Builder duration(String path, Duration value) {
            return field(path, value);
        }

        /** Sets a class-valued field, resolved by {@link #getClassName} to {@code name}. */
        public Builder className(String path, String name) {
            return field(path, new ClassRef(name));
        }

        /** Sets a thread-valued field whose Java name and OS name are the same. */
        public Builder threadName(String path, String javaName) {
            return field(path, new ThreadRef(javaName, javaName));
        }

        /** Sets a thread-valued field with distinct Java/OS names, or a null Java name. */
        public Builder thread(String path, String javaName, String osName) {
            return field(path, new ThreadRef(javaName, osName));
        }

        /** Marks {@code path} as present but null (e.g. an unset object field). */
        public Builder nullField(String path) {
            fields.put(path, null);
            return this;
        }

        public MapEventView build() {
            return new MapEventView(eventName, startTime, new LinkedHashMap<>(fields));
        }
    }
}
