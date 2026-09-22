package io.jfrom.agent.engine;

import io.jfrom.agent.config.ValueKind;
import io.jfrom.agent.config.ValueSpec;
import io.jfrom.agent.event.EventView;
import java.time.Duration;
import java.util.OptionalDouble;

/**
 * Turns a {@link ValueSpec} plus an {@link EventView} into a numeric sample.
 *
 * <p>A field that is absent (or, for {@link ValueKind#DURATION}, present but not actually a
 * timespan) yields {@link OptionalDouble#empty()} rather than {@code 0.0}. Recording a bogus zero
 * for an absent field would produce a convincing but wrong graph (e.g. a GC pause that "really"
 * lasted zero seconds), so callers must treat "no sample" and "a sample of zero" as distinct and
 * simply skip recording when this returns empty.
 */
public final class ValueExtractor {

    private ValueExtractor() {
    }

    public static OptionalDouble extract(ValueSpec spec, EventView view) {
        String field = spec.field();
        if (!view.hasField(field)) {
            return OptionalDouble.empty();
        }
        return switch (spec.kind()) {
            case NUMBER -> OptionalDouble.of(view.getDouble(field));
            case DURATION -> {
                // Never read a @Timespan field as a raw number: jdk.GarbageCollection.duration
                // (and friends) is TICKS-encoded, and getLong() on it returns raw ticks, not a
                // wall-clock value. getDuration() is the only correct accessor.
                Duration duration = view.getDuration(field);
                yield duration == null
                        ? OptionalDouble.empty()
                        : OptionalDouble.of(spec.unit().convert(duration));
            }
        };
    }
}
