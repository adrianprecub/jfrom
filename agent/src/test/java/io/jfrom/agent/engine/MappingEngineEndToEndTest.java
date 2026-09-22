package io.jfrom.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfrom.agent.config.DurationUnit;
import io.jfrom.agent.config.EnableSpec;
import io.jfrom.agent.config.EventRule;
import io.jfrom.agent.config.MappingConfig;
import io.jfrom.agent.config.MetricSpec;
import io.jfrom.agent.config.ValueKind;
import io.jfrom.agent.config.ValueSpec;
import io.jfrom.agent.http.MetricsServer;
import io.jfrom.agent.metrics.DefaultMetricRegistry;
import io.jfrom.agent.metrics.MetricType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A real, full-stack smoke test: a live {@code RecordingStream} driven by hand-made rules,
 * scraped over a real {@link MetricsServer} HTTP endpoint.
 *
 * <p>JFR flush latency to a {@code RecordingStream} is documented as ~1-3s, so this polls with a
 * generous overall timeout rather than sleeping a fixed duration.
 */
class MappingEngineEndToEndTest {

    private MappingEngine engine;
    private MetricsServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(30)
    void gcAndHeapMetricsAppearOnARealHttpScrapeAfterForcingAGc() throws Exception {
        MetricSpec gcPauseSeconds = new MetricSpec("jfr_gc_pause_seconds", MetricType.HISTOGRAM,
                "GC pause duration",
                new ValueSpec("duration", ValueKind.DURATION, DurationUnit.SECONDS),
                List.of(), List.of(0.0005, 0.005, 0.05, 0.5, 5.0), 0);
        EventRule gcRule = new EventRule("jdk.GarbageCollection", EnableSpec.defaults(), Map.of(),
                List.of(gcPauseSeconds));

        MetricSpec heapUsedBytes = new MetricSpec("jfr_heap_used_bytes", MetricType.GAUGE,
                "Heap bytes in use",
                new ValueSpec("heapUsed", ValueKind.NUMBER, null), List.of(), null, 0);
        EventRule heapRule = new EventRule("jdk.GCHeapSummary", EnableSpec.defaults(), Map.of(),
                List.of(heapUsedBytes));

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        engine = new MappingEngine(new MappingConfig(List.of(gcRule, heapRule)), registry);
        engine.start();

        server = MetricsServer.start("127.0.0.1", 0, registry);
        HttpClient client = HttpClient.newHttpClient();
        URI metricsUri = URI.create("http://127.0.0.1:" + server.port() + "/metrics");

        // Generate allocation churn and a heap summary, and force a GC pause to observe.
        Thread churn = new Thread(() -> {
            List<byte[]> garbage = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                garbage.add(new byte[64 * 1024]);
                if (garbage.size() > 512) {
                    garbage.clear();
                }
            }
        });
        churn.setDaemon(true);
        churn.start();

        System.gc();
        Thread.sleep(500);
        System.gc();

        String body = pollUntilBothMetricsPresentAndNonZero(client, metricsUri);

        assertThat(body).contains("jfr_gc_pause_seconds_count");
        assertThat(extractSampleValue(body, "jfr_gc_pause_seconds_count")).isGreaterThan(0);
        assertThat(extractSampleValue(body, "jfr_heap_used_bytes")).isGreaterThan(0);
    }

    private static String pollUntilBothMetricsPresentAndNonZero(HttpClient client, URI uri) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        String lastBody = "";
        while (System.nanoTime() < deadline) {
            HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            lastBody = response.body();
            boolean gcSeen = containsNonZeroSample(lastBody, "jfr_gc_pause_seconds_count");
            boolean heapSeen = containsNonZeroSample(lastBody, "jfr_heap_used_bytes");
            if (gcSeen && heapSeen) {
                return lastBody;
            }
            System.gc();
            Thread.sleep(500);
        }
        throw new AssertionError("timed out waiting for jfr_gc_pause_seconds_count and "
                + "jfr_heap_used_bytes to appear non-zero. Last body:\n" + lastBody);
    }

    private static boolean containsNonZeroSample(String body, String metricPrefix) {
        for (String line : body.split("\n")) {
            if (line.startsWith(metricPrefix + " ") || line.startsWith(metricPrefix + "{")) {
                double value = parseTrailingValue(line);
                if (value > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double extractSampleValue(String body, String metricPrefix) {
        for (String line : body.split("\n")) {
            if (line.startsWith(metricPrefix + " ") || line.startsWith(metricPrefix + "{")) {
                double value = parseTrailingValue(line);
                if (value > 0) {
                    return value;
                }
            }
        }
        throw new AssertionError("no non-zero sample for '" + metricPrefix + "' found in:\n" + body);
    }

    private static double parseTrailingValue(String line) {
        int lastSpace = line.lastIndexOf(' ');
        return Double.parseDouble(line.substring(lastSpace + 1).trim());
    }
}
