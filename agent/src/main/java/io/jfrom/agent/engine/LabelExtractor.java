package io.jfrom.agent.engine;

import io.jfrom.agent.config.LabelSpec;
import io.jfrom.agent.event.EventView;
import io.jfrom.agent.metrics.Labels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link LabelSpec} plus an {@link EventView} into a label value.
 *
 * <p>Unlike a missing metric value, a missing label never drops the sample: it renders as an
 * empty string. A metric with an absent-label dimension is still a real, useful data point (for
 * example a {@code jdk.SafepointBegin} on a VM-internal thread whose Java name is null); dropping
 * it entirely would silently hide events instead of just under-labelling them.
 */
public final class LabelExtractor {

    private LabelExtractor() {
    }

    /** @return the label's text value, or {@code ""} if the field is absent or resolves to null. */
    public static String extract(LabelSpec spec, EventView view) {
        String field = spec.field();
        if (!view.hasField(field)) {
            return "";
        }
        String value = switch (spec.kind()) {
            case STRING -> view.getString(field);
            case CLASS -> view.getClassName(field);
            case THREAD -> view.getThreadName(field);
            case BOOLEAN -> Boolean.toString(view.getBoolean(field));
            case INT -> Long.toString(view.getLong(field));
        };
        return value == null ? "" : value;
    }

    /** Builds a full {@link Labels} set for a metric from its {@link LabelSpec} list. */
    public static Labels buildLabels(List<LabelSpec> specs, EventView view) {
        if (specs.isEmpty()) {
            return Labels.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (LabelSpec spec : specs) {
            values.put(spec.name(), extract(spec, view));
        }
        return Labels.of(values);
    }
}
