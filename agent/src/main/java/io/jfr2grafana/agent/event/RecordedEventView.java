package io.jfr2grafana.agent.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * A thin, defensive adapter of {@link EventView} over {@link RecordedEvent}.
 *
 * <p>{@code RecordedEvent}'s own accessors ({@code getLong}, {@code getString}, ...) throw
 * {@link IllegalArgumentException} whenever a field path is absent, or when the requested
 * conversion is not legal for the field's actual type (for example {@code getLong} on a
 * {@code float} field). Since a mapping pack authored against one JDK's event schema may run
 * against a JVM where a field has moved, been renamed, or never existed, every accessor here
 * guards both cases and degrades to the documented empty value instead of propagating the
 * exception. The JFR consumer stream calls back on a single dedicated thread; letting any of
 * these accessors throw would kill event processing entirely.
 *
 * <p>Dotted nested paths (e.g. {@code "heapSpace.committedSize"}) are handled natively by
 * {@code RecordedEvent}/{@code RecordedObject} - confirmed by direct probing against a live
 * recording on JDK 25.0.1 - so this class does not walk nested structs itself.
 */
public final class RecordedEventView implements EventView {

    private final RecordedEvent event;

    public RecordedEventView(RecordedEvent event) {
        this.event = Objects.requireNonNull(event, "event");
    }

    @Override
    public String eventName() {
        return event.getEventType().getName();
    }

    @Override
    public boolean hasField(String path) {
        try {
            return event.hasField(path);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public String getString(String path) {
        if (!hasField(path)) {
            return null;
        }
        try {
            return event.getString(path);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public long getLong(String path) {
        if (!hasField(path)) {
            return 0L;
        }
        try {
            return event.getLong(path);
        } catch (RuntimeException ex) {
            // getLong() rejects narrowing/incompatible conversions (e.g. a float field) even
            // though the value is legitimately numeric. Widen via getDouble() instead of
            // treating a valid-but-differently-typed field as absent.
            try {
                return (long) event.getDouble(path);
            } catch (RuntimeException ex2) {
                return 0L;
            }
        }
    }

    @Override
    public double getDouble(String path) {
        if (!hasField(path)) {
            return 0.0;
        }
        try {
            return event.getDouble(path);
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    @Override
    public boolean getBoolean(String path) {
        if (!hasField(path)) {
            return false;
        }
        try {
            return event.getBoolean(path);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public Duration getDuration(String path) {
        if (!hasField(path)) {
            return null;
        }
        try {
            return event.getDuration(path);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public String getClassName(String path) {
        if (!hasField(path)) {
            return null;
        }
        try {
            RecordedClass recordedClass = event.getClass(path);
            return recordedClass == null ? null : recordedClass.getName();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public String getThreadName(String path) {
        if (!hasField(path)) {
            return null;
        }
        try {
            RecordedThread thread = event.getThread(path);
            if (thread == null) {
                return null;
            }
            String javaName = thread.getJavaName();
            return javaName != null ? javaName : thread.getOSName();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public Instant startTime() {
        return event.getStartTime();
    }
}
