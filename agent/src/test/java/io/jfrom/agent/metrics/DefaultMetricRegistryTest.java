package io.jfrom.agent.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfrom.agent.config.DurationUnit;
import io.jfrom.agent.config.LabelKind;
import io.jfrom.agent.config.LabelSpec;
import io.jfrom.agent.config.MetricSpec;
import io.jfrom.agent.config.ValueKind;
import io.jfrom.agent.config.ValueSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DefaultMetricRegistryTest {

    private static final ValueSpec NUMBER_VALUE = new ValueSpec("x", ValueKind.NUMBER, null);

    private String expose(DefaultMetricRegistry registry) throws Exception {
        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        return sb.toString();
    }

    // --- gauge ------------------------------------------------------------------------------

    @Test
    void gaugeLastWriteWins() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec spec = new MetricSpec("jfr_heap_used_bytes", MetricType.GAUGE, "Heap used",
                NUMBER_VALUE, List.of(), null, 0);

        registry.setGauge(spec, Labels.empty(), 100.0);
        registry.setGauge(spec, Labels.empty(), 200.0);

        String out = expose(registry);
        assertThat(out).contains("jfr_heap_used_bytes 200");
        assertThat(out).doesNotContain("jfr_heap_used_bytes 100");
    }

    @Test
    void noLabelMetricsEmitNoBraces() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec spec = new MetricSpec("jfr_no_labels", MetricType.GAUGE, null,
                NUMBER_VALUE, List.of(), null, 0);
        registry.setGauge(spec, Labels.empty(), 42.0);

        String out = expose(registry);
        assertThat(out).contains("\njfr_no_labels 42\n");
        assertThat(out).doesNotContain("jfr_no_labels{");
    }

    // --- counter vs counter_absolute ---------------------------------------------------------

    @Test
    void counterAddsDelta() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec spec = new MetricSpec("jfr_events_total", MetricType.COUNTER, null,
                null, List.of(), null, 0);

        registry.addCounter(spec, Labels.empty(), 1);
        registry.addCounter(spec, Labels.empty(), 4);

        assertThat(expose(registry)).contains("jfr_events_total 5");
    }

    @Test
    void counterAbsoluteSetsRatherThanAdds() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec spec = new MetricSpec("jfr_loaded_class_count", MetricType.COUNTER_ABSOLUTE, null,
                NUMBER_VALUE, List.of(), null, 0);

        registry.setCounterAbsolute(spec, Labels.empty(), 1000);
        registry.setCounterAbsolute(spec, Labels.empty(), 1200);

        assertThat(expose(registry)).contains("jfr_loaded_class_count 1200");
    }

    @Test
    void counterAbsoluteIgnoresBackwardsValue() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec spec = new MetricSpec("jfr_loaded_class_count", MetricType.COUNTER_ABSOLUTE, null,
                NUMBER_VALUE, List.of(), null, 0);

        registry.setCounterAbsolute(spec, Labels.empty(), 1200);
        registry.setCounterAbsolute(spec, Labels.empty(), 900); // JVM restart-like blip; must be ignored

        assertThat(expose(registry)).contains("jfr_loaded_class_count 1200");
    }

    @Test
    void counterAbsoluteAllowsEqualValue() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec spec = new MetricSpec("jfr_x", MetricType.COUNTER_ABSOLUTE, null,
                NUMBER_VALUE, List.of(), null, 0);

        registry.setCounterAbsolute(spec, Labels.empty(), 5);
        registry.setCounterAbsolute(spec, Labels.empty(), 5);

        assertThat(expose(registry)).contains("jfr_x 5");
    }

    // --- histogram ----------------------------------------------------------------------------

    @Test
    void histogramBucketBoundariesAreCumulativeAndIncludeInf() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        ValueSpec durationValue = new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS);
        MetricSpec spec = new MetricSpec("jfr_gc_pause_seconds", MetricType.HISTOGRAM, "GC pause",
                durationValue, List.of(), List.of(1.0, 5.0, 10.0), 0);

        // 1.0 lands exactly on the first bound -> must fall INSIDE it (le semantics: value <= bound).
        registry.observeHistogram(spec, Labels.empty(), 1.0);
        registry.observeHistogram(spec, Labels.empty(), 3.0);
        registry.observeHistogram(spec, Labels.empty(), 20.0); // beyond every finite bucket

        String out = expose(registry);

        assertThat(out).contains("jfr_gc_pause_seconds_bucket{le=\"1\"} 1");
        assertThat(out).contains("jfr_gc_pause_seconds_bucket{le=\"5\"} 2");
        assertThat(out).contains("jfr_gc_pause_seconds_bucket{le=\"10\"} 2");
        assertThat(out).contains("jfr_gc_pause_seconds_bucket{le=\"+Inf\"} 3");
        assertThat(out).contains("jfr_gc_pause_seconds_sum 24");
        assertThat(out).contains("jfr_gc_pause_seconds_count 3");

        // le must be the last label.
        assertThat(out).contains("jfr_gc_pause_seconds_bucket{le=");
    }

    @Test
    void histogramLePlacedLastWithOtherLabels() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        LabelSpec gcLabel = new LabelSpec("gc", "name", LabelKind.STRING);
        MetricSpec spec = new MetricSpec("jfr_gc_pause_seconds", MetricType.HISTOGRAM, null,
                NUMBER_VALUE, List.of(gcLabel), List.of(1.0), 0);

        registry.observeHistogram(spec, Labels.of(Map.of("gc", "G1Full")), 0.5);

        String out = expose(registry);
        assertThat(out).contains("jfr_gc_pause_seconds_bucket{gc=\"G1Full\",le=\"1\"} 1");
    }

    // --- HELP/TYPE and escaping -----------------------------------------------------------------

    @Test
    void helpAndLabelEscaping() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        LabelSpec labelSpec = new LabelSpec("thread", "eventThread", LabelKind.THREAD);
        MetricSpec spec = new MetricSpec(
                "jfr_x",
                MetricType.GAUGE,
                "help with \\backslash and \"quote\" and\nnewline",
                NUMBER_VALUE,
                List.of(labelSpec),
                null,
                0);

        registry.setGauge(spec, Labels.of(Map.of("thread", "weird\\thread\"name\nhere")), 1.0);

        String out = expose(registry);
        assertThat(out).contains(
                "# HELP jfr_x help with \\\\backslash and \"quote\" and\\nnewline\n");
        assertThat(out).contains("# TYPE jfr_x gauge\n");
        assertThat(out).contains("thread=\"weird\\\\thread\\\"name\\nhere\"");
    }

    @Test
    void helpAndTypeEmittedOncePerMetricName() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        LabelSpec labelSpec = new LabelSpec("gc", "name", LabelKind.STRING);
        MetricSpec spec = new MetricSpec("jfr_x", MetricType.COUNTER, "help", null,
                List.of(labelSpec), null, 0);

        registry.addCounter(spec, Labels.of(Map.of("gc", "A")), 1);
        registry.addCounter(spec, Labels.of(Map.of("gc", "B")), 1);

        String out = expose(registry);
        assertThat(countOccurrences(out, "# HELP jfr_x")).isEqualTo(1);
        assertThat(countOccurrences(out, "# TYPE jfr_x")).isEqualTo(1);
        assertThat(out).contains("jfr_x{gc=\"A\"} 1");
        assertThat(out).contains("jfr_x{gc=\"B\"} 1");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // --- special value formatting --------------------------------------------------------------

    @Test
    void specialDoubleValuesFormatExactly() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec nan = new MetricSpec("jfr_nan", MetricType.GAUGE, null, NUMBER_VALUE, List.of(), null, 0);
        MetricSpec posInf = new MetricSpec("jfr_pos_inf", MetricType.GAUGE, null, NUMBER_VALUE, List.of(), null, 0);
        MetricSpec negInf = new MetricSpec("jfr_neg_inf", MetricType.GAUGE, null, NUMBER_VALUE, List.of(), null, 0);

        registry.setGauge(nan, Labels.empty(), Double.NaN);
        registry.setGauge(posInf, Labels.empty(), Double.POSITIVE_INFINITY);
        registry.setGauge(negInf, Labels.empty(), Double.NEGATIVE_INFINITY);

        String out = expose(registry);
        assertThat(out).contains("jfr_nan NaN");
        assertThat(out).contains("jfr_pos_inf +Inf");
        assertThat(out).contains("jfr_neg_inf -Inf");
    }

    // --- cardinality guard ------------------------------------------------------------------

    @Test
    void cardinalityOverflowCollapsesToOtherAndIncrementsDroppedCounter() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        LabelSpec classLabel = new LabelSpec("monitorClass", "monitorClass", LabelKind.CLASS);
        MetricSpec spec = new MetricSpec("jfr_monitor_enter_total", MetricType.COUNTER, null,
                null, List.of(classLabel), null, 2); // maxCardinality = 2

        registry.addCounter(spec, Labels.of(Map.of("monitorClass", "A")), 1);
        registry.addCounter(spec, Labels.of(Map.of("monitorClass", "B")), 1);
        // A and B fit under the cap; both keep working afterwards.
        registry.addCounter(spec, Labels.of(Map.of("monitorClass", "A")), 1);

        // C, D are new and push past the cap -> both collapse into the single __other__ series.
        registry.addCounter(spec, Labels.of(Map.of("monitorClass", "C")), 1);
        registry.addCounter(spec, Labels.of(Map.of("monitorClass", "D")), 1);

        String out = expose(registry);
        assertThat(out).contains("jfr_monitor_enter_total{monitorClass=\"A\"} 2");
        assertThat(out).contains("jfr_monitor_enter_total{monitorClass=\"B\"} 1");
        assertThat(out).contains("jfr_monitor_enter_total{monitorClass=\"" + MetricRegistry.OVERFLOW_LABEL_VALUE + "\"} 2");
        assertThat(out).doesNotContain("monitorClass=\"C\"");
        assertThat(out).doesNotContain("monitorClass=\"D\"");

        assertThat(out).contains(MetricRegistry.CARDINALITY_DROPPED_METRIC + "{metric=\"jfr_monitor_enter_total\"} 2");
    }

    // --- concurrency --------------------------------------------------------------------------

    @Test
    void concurrentUpdatesAndExpositionDoNotLoseUpdatesOrThrow() throws Exception {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MetricSpec counterSpec = new MetricSpec("jfr_c", MetricType.COUNTER, null, null, List.of(), null, 0);
        MetricSpec absoluteSpec = new MetricSpec("jfr_a", MetricType.COUNTER_ABSOLUTE, null, NUMBER_VALUE,
                List.of(), null, 0);
        MetricSpec gaugeSpec = new MetricSpec("jfr_g", MetricType.GAUGE, null, NUMBER_VALUE, List.of(), null, 0);
        LabelSpec threadLabel = new LabelSpec("thread", "eventThread", LabelKind.THREAD);
        MetricSpec labeledSpec = new MetricSpec("jfr_l", MetricType.COUNTER, null, null,
                List.of(threadLabel), null, 5);

        int threadCount = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean scraping = new AtomicBoolean(true);
        AtomicBoolean scrapeFailed = new AtomicBoolean(false);

        // Concurrent scraper: repeatedly calls writeExposition while writers hammer the registry.
        pool.submit(() -> {
            try {
                start.await();
                while (scraping.get()) {
                    StringBuilder sb = new StringBuilder();
                    registry.writeExposition(sb);
                }
            } catch (Exception e) {
                scrapeFailed.set(true);
            }
        });

        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            int threadIdx = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.addCounter(counterSpec, Labels.empty(), 1);
                        registry.setCounterAbsolute(absoluteSpec, Labels.empty(), i);
                        registry.setGauge(gaugeSpec, Labels.empty(), i);
                        registry.addCounter(labeledSpec,
                                Labels.of(Map.of("thread", "t" + (threadIdx % 3))), 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        scraping.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(scrapeFailed).isFalse();

        String finalOut = expose(registry);
        assertThat(finalOut).contains("jfr_c " + (threadCount * perThread));
        assertThat(finalOut).contains("jfr_a " + (perThread - 1));
        assertThat(finalOut).contains("jfr_g " + (perThread - 1));

        // labeledSpec has maxCardinality 5 but only 3 distinct thread labels are ever used, so no
        // overflow should have occurred and every event must be accounted for across its series.
        long total = 0;
        for (String label : new String[] {"t0", "t1", "t2"}) {
            String marker = "jfr_l{thread=\"" + label + "\"} ";
            int idx = finalOut.indexOf(marker);
            assertThat(idx).isGreaterThanOrEqualTo(0);
            int end = finalOut.indexOf('\n', idx + marker.length());
            total += Long.parseLong(finalOut.substring(idx + marker.length(), end).trim());
        }
        assertThat(total).isEqualTo((long) threadCount * perThread);
    }
}
