package io.jfrom.agent.metrics;

import io.jfrom.agent.config.LabelKind;
import io.jfrom.agent.config.LabelSpec;
import io.jfrom.agent.config.MetricSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe {@link MetricRegistry} backed by {@link ConcurrentHashMap} and lock-free adders.
 *
 * <p><b>Concurrency.</b> The hot path — updating a metric whose label set has already been seen
 * — never takes a lock: it is a single {@code ConcurrentHashMap.get} followed by an atomic
 * add/set. A lock is only taken on the rare, bounded-number-of-times path where a genuinely new
 * label combination is first seen for a metric (at most {@code maxCardinality + 1} times per
 * metric, ever), to decide atomically whether it fits under the cardinality cap or must collapse
 * into the shared overflow series.
 */
public final class DefaultMetricRegistry implements MetricRegistry {

    private static final LabelSpec DROPPED_METRIC_LABEL =
            new LabelSpec("metric", "metric", LabelKind.STRING);

    /**
     * Spec for the self-observability counter. Cardinality here is bounded by the number of
     * distinct metric names ever registered (a small, config-driven number), not by unbounded
     * JFR field values, so it is exempt from the cardinality guard: otherwise, if this metric
     * itself ever overflowed, recording the drop would recursively try to record another drop.
     */
    private static final MetricSpec CARDINALITY_DROPPED_SPEC = new MetricSpec(
            CARDINALITY_DROPPED_METRIC,
            MetricType.COUNTER,
            "Distinct label sets dropped by the cardinality guard, by metric name",
            null,
            List.of(DROPPED_METRIC_LABEL),
            null,
            Integer.MAX_VALUE);

    private final ConcurrentHashMap<String, MetricState> metrics = new ConcurrentHashMap<>();

    @Override
    public void setGauge(MetricSpec spec, Labels labels, double value) {
        ((GaugeSeries) seriesFor(spec, labels)).set(value);
    }

    @Override
    public void addCounter(MetricSpec spec, Labels labels, double delta) {
        ((CounterSeries) seriesFor(spec, labels)).add(delta);
    }

    @Override
    public void setCounterAbsolute(MetricSpec spec, Labels labels, double total) {
        ((AbsoluteSeries) seriesFor(spec, labels)).setIfNotLower(total);
    }

    @Override
    public void observeHistogram(MetricSpec spec, Labels labels, double value) {
        ((HistogramSeries) seriesFor(spec, labels)).observe(value);
    }

    @Override
    public void writeExposition(Appendable out) throws IOException {
        List<String> names = new ArrayList<>(metrics.keySet());
        Collections.sort(names);
        for (String name : names) {
            MetricState state = metrics.get(name);
            if (state == null) {
                continue;
            }
            MetricSpec spec = state.spec;
            out.append("# HELP ").append(name).append(' ')
                    .append(PrometheusExposition.escapeHelp(spec.help())).append('\n');
            out.append("# TYPE ").append(name).append(' ')
                    .append(PrometheusExposition.typeName(spec.type())).append('\n');

            if (spec.type() == MetricType.HISTOGRAM) {
                writeHistogramSamples(out, name, state);
            } else {
                for (Map.Entry<Labels, SeriesValue> e : state.series.entrySet()) {
                    double value = readScalar(spec.type(), e.getValue());
                    out.append(name).append(PrometheusExposition.renderLabels(e.getKey()))
                            .append(' ').append(PrometheusExposition.formatValue(value)).append('\n');
                }
            }
        }
    }

    private static double readScalar(MetricType type, SeriesValue sv) {
        return switch (type) {
            case GAUGE -> ((GaugeSeries) sv).get();
            case COUNTER -> ((CounterSeries) sv).get();
            case COUNTER_ABSOLUTE -> ((AbsoluteSeries) sv).get();
            case HISTOGRAM -> throw new IllegalStateException("histogram handled separately");
        };
    }

    private static void writeHistogramSamples(Appendable out, String name, MetricState state)
            throws IOException {
        List<Double> buckets = state.spec.buckets();
        for (Map.Entry<Labels, SeriesValue> e : state.series.entrySet()) {
            HistogramSeries hs = (HistogramSeries) e.getValue();
            Labels labels = e.getKey();
            long[] perBucket = hs.bucketCounts();
            long cumulative = 0;
            for (int i = 0; i < buckets.size(); i++) {
                cumulative += perBucket[i];
                Labels withLe = labels.with("le", PrometheusExposition.formatValue(buckets.get(i)));
                out.append(name).append("_bucket").append(PrometheusExposition.renderLabels(withLe))
                        .append(' ').append(Long.toString(cumulative)).append('\n');
            }
            long total = hs.count();
            Labels withInf = labels.with("le", "+Inf");
            out.append(name).append("_bucket").append(PrometheusExposition.renderLabels(withInf))
                    .append(' ').append(Long.toString(total)).append('\n');
            out.append(name).append("_sum").append(PrometheusExposition.renderLabels(labels))
                    .append(' ').append(PrometheusExposition.formatValue(hs.sum())).append('\n');
            out.append(name).append("_count").append(PrometheusExposition.renderLabels(labels))
                    .append(' ').append(Long.toString(total)).append('\n');
        }
    }

    // --- series lookup / cardinality guard -------------------------------------------------

    private SeriesValue seriesFor(MetricSpec spec, Labels labels) {
        MetricState state = metrics.computeIfAbsent(spec.name(), n -> new MetricState(spec));
        return resolveSeries(state, labels);
    }

    private SeriesValue resolveSeries(MetricState state, Labels labels) {
        SeriesValue existing = state.series.get(labels);
        if (existing != null) {
            return existing;
        }
        synchronized (state.cardinalityLock) {
            existing = state.series.get(labels);
            if (existing != null) {
                return existing;
            }
            int max = state.spec.maxCardinality();
            if (state.distinctCount.get() < max) {
                SeriesValue created = newSeries(state.spec);
                state.series.put(labels, created);
                state.distinctCount.incrementAndGet();
                return created;
            }
            Labels overflowLabels = overflowLabelsFor(labels);
            SeriesValue overflow =
                    state.series.computeIfAbsent(overflowLabels, k -> newSeries(state.spec));
            if (!CARDINALITY_DROPPED_METRIC.equals(state.spec.name())) {
                dropCardinality(state.spec.name());
            }
            return overflow;
        }
    }

    private void dropCardinality(String metricName) {
        SeriesValue sv = seriesFor(CARDINALITY_DROPPED_SPEC, Labels.of(Map.of("metric", metricName)));
        ((CounterSeries) sv).add(1);
    }

    private static Labels overflowLabelsFor(Labels labels) {
        if (labels.isEmpty()) {
            return labels;
        }
        Map<String, String> collapsed = new java.util.LinkedHashMap<>();
        for (String key : labels.asMap().keySet()) {
            collapsed.put(key, MetricRegistry.OVERFLOW_LABEL_VALUE);
        }
        return Labels.of(collapsed);
    }

    private static SeriesValue newSeries(MetricSpec spec) {
        return switch (spec.type()) {
            case GAUGE -> new GaugeSeries();
            case COUNTER -> new CounterSeries();
            case COUNTER_ABSOLUTE -> new AbsoluteSeries();
            case HISTOGRAM -> new HistogramSeries(spec.buckets());
        };
    }

    // --- per-metric state --------------------------------------------------------------------

    private static final class MetricState {
        final MetricSpec spec;
        final ConcurrentHashMap<Labels, SeriesValue> series = new ConcurrentHashMap<>();
        final AtomicInteger distinctCount = new AtomicInteger();
        final Object cardinalityLock = new Object();

        MetricState(MetricSpec spec) {
            this.spec = spec;
        }
    }

    private abstract static class SeriesValue {}

    private static final class GaugeSeries extends SeriesValue {
        private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0.0));

        void set(double v) {
            bits.set(Double.doubleToLongBits(v));
        }

        double get() {
            return Double.longBitsToDouble(bits.get());
        }
    }

    private static final class CounterSeries extends SeriesValue {
        private final DoubleAdder adder = new DoubleAdder();

        void add(double delta) {
            adder.add(delta);
        }

        double get() {
            return adder.sum();
        }
    }

    /** Backing store for {@link MetricType#COUNTER_ABSOLUTE}: set, but never allow it to go backwards. */
    private static final class AbsoluteSeries extends SeriesValue {
        private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0.0));

        void setIfNotLower(double total) {
            while (true) {
                long curBits = bits.get();
                double cur = Double.longBitsToDouble(curBits);
                if (total < cur) {
                    return;
                }
                long newBits = Double.doubleToLongBits(total);
                if (bits.compareAndSet(curBits, newBits)) {
                    return;
                }
            }
        }

        double get() {
            return Double.longBitsToDouble(bits.get());
        }
    }

    private static final class HistogramSeries extends SeriesValue {
        private final double[] bounds;
        private final LongAdder[] perBucket;
        private final DoubleAdder sum = new DoubleAdder();
        private final LongAdder count = new LongAdder();

        HistogramSeries(List<Double> buckets) {
            bounds = new double[buckets.size()];
            for (int i = 0; i < bounds.length; i++) {
                bounds[i] = buckets.get(i);
            }
            perBucket = new LongAdder[bounds.length];
            for (int i = 0; i < perBucket.length; i++) {
                perBucket[i] = new LongAdder();
            }
        }

        void observe(double value) {
            for (int i = 0; i < bounds.length; i++) {
                if (value <= bounds[i]) {
                    perBucket[i].increment();
                    break;
                }
            }
            sum.add(value);
            count.increment();
        }

        long[] bucketCounts() {
            long[] out = new long[perBucket.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = perBucket[i].sum();
            }
            return out;
        }

        double sum() {
            return sum.sum();
        }

        long count() {
            return count.sum();
        }
    }
}
