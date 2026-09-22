package io.jfrom.agent.config;

import java.time.Duration;

/** Target unit for a {@link ValueKind#DURATION} value. Prometheus convention is seconds. */
public enum DurationUnit {
    SECONDS(1_000_000_000d),
    MILLISECONDS(1_000_000d),
    NANOSECONDS(1d);

    private final double nanosPerUnit;

    DurationUnit(double nanosPerUnit) {
        this.nanosPerUnit = nanosPerUnit;
    }

    public double convert(Duration duration) {
        return duration.toNanos() / nanosPerUnit;
    }
}
