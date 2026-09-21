package io.jfr2grafana.agent.config;

import io.jfr2grafana.agent.metrics.MetricType;
import java.util.List;

/**
 * One metric produced from one event.
 *
 * @param name           metric name; mapping packs prefix these {@code jfr_} so they cannot
 *                       collide with Micrometer/Actuator's own {@code jvm_*} series
 * @param type           how the value accumulates
 * @param help           HELP text for the exposition output
 * @param value          which field supplies the value; {@code null} is legal only for
 *                       {@link MetricType#COUNTER}, meaning "count events"
 * @param labels         label set, may be empty
 * @param buckets        histogram upper bounds in ascending order; required for
 *                       {@link MetricType#HISTOGRAM}, ignored otherwise
 * @param maxCardinality cap on distinct label combinations before collapsing to
 *                       {@code __other__}
 */
public record MetricSpec(
        String name,
        MetricType type,
        String help,
        ValueSpec value,
        List<LabelSpec> labels,
        List<Double> buckets,
        int maxCardinality) {

    public static final int DEFAULT_MAX_CARDINALITY = 100;

    public MetricSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metric.name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("metric.type is required for '" + name + "'");
        }
        labels = labels == null ? List.of() : List.copyOf(labels);

        if (type == MetricType.HISTOGRAM) {
            if (buckets == null || buckets.isEmpty()) {
                throw new IllegalArgumentException(
                        "metric '" + name + "' is a histogram and requires non-empty 'buckets'");
            }
            buckets = List.copyOf(buckets);
            for (int i = 1; i < buckets.size(); i++) {
                if (buckets.get(i) <= buckets.get(i - 1)) {
                    throw new IllegalArgumentException(
                            "metric '" + name + "' buckets must be strictly ascending, but "
                                    + buckets.get(i - 1) + " is followed by " + buckets.get(i));
                }
            }
        } else {
            buckets = buckets == null ? null : List.copyOf(buckets);
        }

        if (value == null && type != MetricType.COUNTER) {
            throw new IllegalArgumentException(
                    "metric '" + name + "' of type " + type + " requires a 'value'; "
                            + "only a counter may omit it (to count events)");
        }
        if (help == null || help.isBlank()) {
            help = name;
        }
        if (maxCardinality <= 0) {
            maxCardinality = DEFAULT_MAX_CARDINALITY;
        }
    }
}
