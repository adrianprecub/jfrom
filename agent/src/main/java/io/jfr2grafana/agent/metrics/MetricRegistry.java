package io.jfr2grafana.agent.metrics;

import io.jfr2grafana.agent.config.MetricSpec;
import java.io.IOException;

/**
 * Thread-safe store of current metric values.
 *
 * <p><b>Concurrency contract:</b> the {@code record*} methods are called from the single JFR
 * {@code RecordingStream} thread, while {@link #writeExposition} is called from an HTTP
 * handler thread. Implementations must tolerate that without locking the stream thread —
 * a blocked stream thread means dropped events.
 *
 * <p><b>Cardinality:</b> implementations enforce {@link MetricSpec#maxCardinality()} per metric.
 * On overflow the offending label values collapse to {@value #OVERFLOW_LABEL_VALUE} rather than
 * growing without bound, and {@value #CARDINALITY_DROPPED_METRIC} is incremented. JFR fields such
 * as {@code monitorClass}, {@code objectClass}, {@code eventThread} and {@code path} are
 * unbounded in principle, so this is required, not advisory.
 */
public interface MetricRegistry {

    String OVERFLOW_LABEL_VALUE = "__other__";
    String CARDINALITY_DROPPED_METRIC = "jfr2grafana_cardinality_dropped_total";

    /** Set a gauge; last write wins. */
    void setGauge(MetricSpec spec, Labels labels, double value);

    /** Add {@code delta} to a counter. */
    void addCounter(MetricSpec spec, Labels labels, double delta);

    /**
     * Set a counter to an absolute cumulative total reported by the JVM.
     *
     * <p>Implementations must ignore a value lower than the one already stored: a counter that
     * goes backwards makes PromQL {@code rate()} synthesise an enormous spike.
     */
    void setCounterAbsolute(MetricSpec spec, Labels labels, double total);

    /** Observe a value into a histogram's buckets. */
    void observeHistogram(MetricSpec spec, Labels labels, double value);

    /** Write the full registry in Prometheus text exposition format. */
    void writeExposition(Appendable out) throws IOException;
}
