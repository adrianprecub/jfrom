package io.jfr2grafana.agent.engine;

import io.jfr2grafana.agent.config.EventRule;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.event.EventView;
import io.jfr2grafana.agent.metrics.Labels;
import io.jfr2grafana.agent.metrics.MetricRegistry;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Applies one {@link EventRule} to one {@link EventView}.
 *
 * <p>Evaluation is two steps: first {@link EventRule#filter()} - every entry must match, compared
 * as text, or the whole rule is skipped for this event - then each of the rule's metrics is
 * produced independently. A metric whose value field is absent on this particular event simply
 * produces no sample; it does not prevent the rule's other metrics from firing.
 *
 * <p>This class never throws for "normal" reasons (filter mismatch, missing field); it is,
 * however, not itself wrapped in a try/catch, so a caller processing a live event stream must
 * still guard against unexpected exceptions from user-supplied specs - see
 * {@link MappingEngine}'s per-rule containment.
 */
public final class RuleEvaluator {

    private RuleEvaluator() {
    }

    public static void evaluate(EventRule rule, EventView view, MetricRegistry registry) {
        if (!filterMatches(rule.filter(), view)) {
            return;
        }
        for (MetricSpec metric : rule.metrics()) {
            applyMetric(metric, view, registry);
        }
    }

    private static boolean filterMatches(Map<String, String> filter, EventView view) {
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            if (!fieldEquals(view, entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean fieldEquals(EventView view, String field, String expected) {
        if (!view.hasField(field)) {
            return false;
        }
        String actual = view.getString(field);
        if (actual == null) {
            // The field exists but isn't natively a String (e.g. a numeric enum-like value).
            // Filter values are authored as plain YAML scalars and compared as text.
            actual = Long.toString(view.getLong(field));
        }
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static void applyMetric(MetricSpec metric, EventView view, MetricRegistry registry) {
        Labels labels = LabelExtractor.buildLabels(metric.labels(), view);
        switch (metric.type()) {
            case GAUGE -> extract(metric, view)
                    .ifPresent(v -> registry.setGauge(metric, labels, v));
            case COUNTER -> {
                if (metric.value() == null) {
                    // No value spec: count events, i.e. an event rate.
                    registry.addCounter(metric, labels, 1.0);
                } else {
                    extract(metric, view).ifPresent(v -> registry.addCounter(metric, labels, v));
                }
            }
            case COUNTER_ABSOLUTE -> extract(metric, view)
                    .ifPresent(v -> registry.setCounterAbsolute(metric, labels, v));
            case HISTOGRAM -> extract(metric, view)
                    .ifPresent(v -> registry.observeHistogram(metric, labels, v));
        }
    }

    private static OptionalDouble extract(MetricSpec metric, EventView view) {
        return ValueExtractor.extract(metric.value(), view);
    }
}
