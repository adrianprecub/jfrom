package io.jfrom.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfrom.agent.config.DurationUnit;
import io.jfrom.agent.config.EnableSpec;
import io.jfrom.agent.config.EventRule;
import io.jfrom.agent.config.MappingConfig;
import io.jfrom.agent.config.MetricSpec;
import io.jfrom.agent.config.ValueKind;
import io.jfrom.agent.config.ValueSpec;
import io.jfrom.agent.event.EventView;
import io.jfrom.agent.event.MapEventView;
import io.jfrom.agent.event.RecordedEventView;
import io.jfrom.agent.metrics.DefaultMetricRegistry;
import io.jfrom.agent.metrics.Labels;
import io.jfrom.agent.metrics.MetricRegistry;
import io.jfrom.agent.metrics.MetricType;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MappingEngineTest {

    private static Map<String, List<RecordedEvent>> byName;

    @BeforeAll
    static void loadFixture() throws IOException, URISyntaxException {
        Path fixture = Path.of(MappingEngineTest.class.getResource("/fixtures/sample.jfr").toURI());
        List<RecordedEvent> events = RecordingFile.readAllEvents(fixture);
        byName = events.stream().collect(Collectors.groupingBy(e -> e.getEventType().getName()));
    }

    private static String exposition(MetricRegistry registry) throws IOException {
        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        return sb.toString();
    }

    @Test
    void replayingFixtureEventsProducesExpectedSeries() throws IOException {
        MetricSpec pauseSeconds = new MetricSpec("jfr_gc_pause_seconds", MetricType.HISTOGRAM, null,
                new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS),
                List.of(), List.of(0.001, 0.01, 0.1, 1.0, 10.0), 0);
        EventRule rule = new EventRule("jdk.GarbageCollection", EnableSpec.defaults(), Map.of(),
                List.of(pauseSeconds));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MappingEngine engine = new MappingEngine(new MappingConfig(List.of(rule)), registry);

        List<RecordedEvent> gcEvents = byName.get("jdk.GarbageCollection");
        assertThat(gcEvents).isNotEmpty();
        for (RecordedEvent event : gcEvents) {
            engine.dispatchEvent("jdk.GarbageCollection", List.of(rule), new RecordedEventView(event));
        }

        String text = exposition(registry);
        assertThat(text).contains("jfr_gc_pause_seconds_count");
        assertThat(text).contains("jfrom_events_processed_total{event=\"jdk.GarbageCollection\"} "
                + gcEvents.size());
    }

    @Test
    void twoRulesOnTheSameEventBothFire() throws IOException {
        MetricSpec metricA = new MetricSpec("jfr_a", MetricType.COUNTER, null, null, List.of(), null, 0);
        MetricSpec metricB = new MetricSpec("jfr_b", MetricType.COUNTER, null, null, List.of(), null, 0);
        EventRule ruleA = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(metricA));
        EventRule ruleB = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(metricB));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        MappingEngine engine = new MappingEngine(new MappingConfig(List.of(ruleA, ruleB)), registry);

        engine.dispatchEvent("evt", List.of(ruleA, ruleB), MapEventView.builder("evt").build());

        String text = exposition(registry);
        assertThat(text).contains("jfr_a 1");
        assertThat(text).contains("jfr_b 1");
    }

    @Test
    void aHandlerThatThrowsIsContainedAndCounted() throws IOException {
        MetricRegistry throwing = new ThrowingOnMetricRegistry("jfr_boom");

        MetricSpec boom = new MetricSpec("jfr_boom", MetricType.GAUGE, null,
                new ValueSpec("v", ValueKind.NUMBER, null), List.of(), null, 0);
        MetricSpec ok = new MetricSpec("jfr_ok", MetricType.GAUGE, null,
                new ValueSpec("v", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule brokenRule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(boom));
        EventRule healthyRule = new EventRule("evt", EnableSpec.defaults(), Map.of(), List.of(ok));

        MappingEngine engine = new MappingEngine(
                new MappingConfig(List.of(brokenRule, healthyRule)), throwing);

        EventView view = MapEventView.builder("evt").number("v", 5L).build();
        engine.dispatchEvent("evt", List.of(brokenRule, healthyRule), view);

        String text = exposition(throwing);
        // The failing rule must not have prevented the healthy rule on the same event from firing.
        assertThat(text).contains("jfr_ok 5");
        // The failure is counted, not silently swallowed...
        assertThat(text).contains("jfrom_events_dropped_total{reason=\"IllegalStateException\"} 1");
        // ...and the event is still counted as processed overall.
        assertThat(text).contains("jfrom_events_processed_total{event=\"evt\"} 1");

        // A second event confirms the engine keeps running afterwards (nothing was left broken).
        engine.dispatchEvent("evt", List.of(brokenRule, healthyRule), view);
        String text2 = exposition(throwing);
        assertThat(text2).contains("jfrom_events_dropped_total{reason=\"IllegalStateException\"} 2");
        assertThat(text2).contains("jfrom_events_processed_total{event=\"evt\"} 2");
    }

    /** Delegates to a real registry but throws for one named metric, to simulate a broken rule. */
    private static final class ThrowingOnMetricRegistry implements MetricRegistry {
        private final DefaultMetricRegistry delegate = new DefaultMetricRegistry();
        private final String failingMetric;

        ThrowingOnMetricRegistry(String failingMetric) {
            this.failingMetric = failingMetric;
        }

        @Override
        public void setGauge(MetricSpec spec, Labels labels, double value) {
            if (spec.name().equals(failingMetric)) {
                throw new IllegalStateException("simulated failure for " + failingMetric);
            }
            delegate.setGauge(spec, labels, value);
        }

        @Override
        public void addCounter(MetricSpec spec, Labels labels, double delta) {
            delegate.addCounter(spec, labels, delta);
        }

        @Override
        public void setCounterAbsolute(MetricSpec spec, Labels labels, double total) {
            delegate.setCounterAbsolute(spec, labels, total);
        }

        @Override
        public void observeHistogram(MetricSpec spec, Labels labels, double value) {
            delegate.observeHistogram(spec, labels, value);
        }

        @Override
        public void writeExposition(Appendable out) throws IOException {
            delegate.writeExposition(out);
        }
    }
}
