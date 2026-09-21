package io.jfr2grafana.agent.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.config.ValueKind;
import io.jfr2grafana.agent.config.ValueSpec;
import io.jfr2grafana.agent.metrics.DefaultMetricRegistry;
import io.jfr2grafana.agent.metrics.Labels;
import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsServerTest {

    private DefaultMetricRegistry registry;
    private MetricsServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DefaultMetricRegistry();
        server = MetricsServer.start("127.0.0.1", 0, registry);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsEndpointReturns200WithPrometheusContentTypeAndBody() throws Exception {
        MetricSpec heap = new MetricSpec("jfr_heap_used_bytes", MetricType.GAUGE, "heap used",
                new ValueSpec("heapUsed", ValueKind.NUMBER, null), List.of(), null, 0);
        registry.setGauge(heap, Labels.empty(), 12345.0);

        HttpResponse<String> response = get("/metrics");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("text/plain; version=0.0.4; charset=utf-8");
        assertThat(response.body()).contains("# TYPE jfr_heap_used_bytes gauge");
        assertThat(response.body()).contains("jfr_heap_used_bytes 12345");
    }

    @Test
    void metricsEndpointReflectsRegistryEvenWithNoUserMetrics() throws Exception {
        HttpResponse<String> response = get("/metrics");

        assertThat(response.statusCode()).isEqualTo(200);
        // Nothing recorded yet, but a well-formed (possibly near-empty) exposition body is fine.
        assertThat(response.body()).isNotNull();
    }

    @Test
    void rootEndpointPointsAtMetrics() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/metrics");
    }

    @Test
    void unknownPathReturns404() throws Exception {
        HttpResponse<String> response = get("/nope");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void scrapeDurationSelfMetricAppearsAfterAScrape() throws Exception {
        get("/metrics"); // first scrape: records the self-metric for the *next* scrape to see
        HttpResponse<String> response = get("/metrics");

        assertThat(response.body()).contains("jfr2grafana_scrape_duration_seconds");
    }

    @Test
    void concurrentScrapeWhileRegistryIsBeingWrittenDoesNotBlockOrFail() throws Exception {
        MetricSpec counter = new MetricSpec("jfr_writes_total", MetricType.COUNTER, null, null, List.of(), null, 0);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                registry.addCounter(counter, Labels.empty(), 1.0);
            }
        });
        writer.start();

        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = get("/metrics");
            assertThat(response.statusCode()).isEqualTo(200);
        }
        writer.join();

        HttpResponse<String> finalScrape = get("/metrics");
        assertThat(finalScrape.body()).contains("jfr_writes_total 2000");
    }
}
