package io.jfr2grafana.agent.config;

import java.time.Duration;

/**
 * How to switch the event on in the JFR recording.
 *
 * <p>The agent enables events explicitly rather than relying on a {@code .jfc} profile, so a
 * mapping pack is self-describing: the rule that consumes an event also states the settings it
 * needs.
 *
 * @param period     for periodic events: a duration, or the literals {@code "everyChunk"} /
 *                   {@code "beginChunk"} / {@code "endChunk"}. {@code null} leaves the default.
 * @param threshold  for duration events: only emit when the event lasts at least this long.
 *                   {@code null} leaves the default.
 * @param stackTrace whether to capture stack traces. Defaults to {@code false}: this project
 *                   produces time series only and never reads a stack, and capturing them is
 *                   the dominant cost of high-frequency events such as {@code JavaMonitorEnter}.
 */
public record EnableSpec(String period, Duration threshold, boolean stackTrace) {

    public static EnableSpec defaults() {
        return new EnableSpec(null, null, false);
    }
}
