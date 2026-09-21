package io.jfr2grafana.agent.config;

import java.util.List;
import java.util.Map;

/**
 * All the metrics derived from one JFR event type.
 *
 * @param event   fully qualified JFR event name, e.g. {@code "jdk.GCHeapSummary"}
 * @param enable  recording settings for this event
 * @param filter  field/value pairs that must all match or the event is skipped. Needed for
 *                events emitted in pairs, e.g. {@code jdk.GCHeapSummary} fires with
 *                {@code when="Before GC"} and {@code when="After GC"} and only one is wanted.
 *                Values are compared as strings.
 * @param metrics the metrics this event produces
 */
public record EventRule(
        String event,
        EnableSpec enable,
        Map<String, String> filter,
        List<MetricSpec> metrics) {

    public EventRule {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("rule.event is required");
        }
        if (metrics == null || metrics.isEmpty()) {
            throw new IllegalArgumentException("rule for '" + event + "' declares no metrics");
        }
        enable = enable == null ? EnableSpec.defaults() : enable;
        filter = filter == null ? Map.of() : Map.copyOf(filter);
        metrics = List.copyOf(metrics);
    }
}
