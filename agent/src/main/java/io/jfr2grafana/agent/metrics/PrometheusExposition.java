package io.jfr2grafana.agent.metrics;

import java.util.Locale;
import java.util.Map;

/**
 * Formatting helpers for the Prometheus text exposition format.
 *
 * <p>Stateless and package-private: {@link DefaultMetricRegistry} owns the actual traversal of
 * metric state and calls into these helpers only for the mechanical parts (escaping, numeric
 * formatting, label rendering) that are easy to get subtly wrong.
 */
final class PrometheusExposition {

    private PrometheusExposition() {}

    /** HELP text escaping: {@code \} -> {@code \\}, newline -> {@code \n}. */
    static String escapeHelp(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /** Label value escaping: {@code \} -> {@code \\}, {@code "} -> {@code \"}, newline -> {@code \n}. */
    static String escapeLabelValue(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Renders {@code {k1="v1",k2="v2"}}, or {@code ""} when there are no labels. */
    static String renderLabels(Labels labels) {
        Map<String, String> values = labels.asMap();
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append("=\"").append(escapeLabelValue(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    /**
     * Formats a sample value. {@code NaN}/{@code +Inf}/{@code -Inf} spelled exactly as Prometheus
     * requires; otherwise a locale-independent plain decimal (or scientific for very large/small
     * magnitudes, which Prometheus also accepts).
     */
    static String formatValue(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%d", (long) v);
        }
        return String.format(Locale.ROOT, "%s", Double.toString(v));
    }

    /** Maps a {@link MetricType} to its Prometheus {@code # TYPE} token. */
    static String typeName(MetricType type) {
        return switch (type) {
            case GAUGE -> "gauge";
            case COUNTER, COUNTER_ABSOLUTE -> "counter";
            case HISTOGRAM -> "histogram";
        };
    }
}
