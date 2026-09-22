package io.jfrom.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.jfrom.agent.config.MetricSpec;
import io.jfrom.agent.config.ValueKind;
import io.jfrom.agent.config.ValueSpec;
import io.jfrom.agent.metrics.Labels;
import io.jfrom.agent.metrics.MetricRegistry;
import io.jfrom.agent.metrics.MetricType;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves the {@link MetricRegistry} over HTTP using only the JDK-builtin
 * {@code com.sun.net.httpserver.HttpServer} - no dependency is added for this.
 *
 * <p>{@code GET /metrics} writes the full Prometheus text exposition
 * ({@code text/plain; version=0.0.4; charset=utf-8}); {@code GET /} returns a tiny pointer page;
 * anything else is a 404.
 *
 * <p><b>Concurrency.</b> {@link MetricRegistry#writeExposition} is already safe to call
 * concurrently with the JFR stream thread writing new samples (see its contract); this class adds
 * no locking of its own around it; a scrape can never block the stream. A small, bounded, daemon
 * thread pool serves requests so a slow or stuck client cannot exhaust host application threads
 * or keep the JVM alive.
 */
public final class MetricsServer implements AutoCloseable {

    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final int THREAD_POOL_SIZE = 4;

    /** Self-observability gauge: how long the most recently completed scrape took. */
    public static final MetricSpec SCRAPE_DURATION_SPEC = new MetricSpec(
            "jfrom_scrape_duration_seconds",
            MetricType.GAUGE,
            "Duration of the most recently completed /metrics scrape, in seconds",
            // Never actually read by name: this server sets the value directly. A ValueSpec is
            // required by MetricSpec's own invariant for a non-counter metric, so this one just
            // documents what the number means.
            new ValueSpec("scrapeDurationSeconds", ValueKind.NUMBER, null),
            List.of(),
            null,
            0);

    private final HttpServer httpServer;
    private final ExecutorService executor;

    private MetricsServer(HttpServer httpServer, ExecutorService executor) {
        this.httpServer = httpServer;
        this.executor = executor;
    }

    /**
     * Starts serving {@code registry} on {@code host:port}. Pass {@code port == 0} for an
     * ephemeral port (useful in tests); read it back with {@link #port()}.
     */
    public static MetricsServer start(String host, int port, MetricRegistry registry) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "jfrom-http-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE, daemonFactory);
        server.setExecutor(executor);

        server.createContext("/metrics", new MetricsHandler(registry));
        server.createContext("/", new RootHandler());
        startDaemon(server);

        return new MetricsServer(server, executor);
    }

    /**
     * {@code HttpServer.start()} spawns its own internal "HTTP-Dispatcher" accept-loop thread,
     * which - per the JDK source - inherits daemon status from whichever thread calls
     * {@code start()} rather than being fixed. Calling it directly from {@code premain} (a
     * non-daemon thread) would leave that dispatcher thread non-daemon, which alone would keep
     * the host JVM alive even with every one of our own threads correctly marked daemon. Starting
     * it from a throwaway daemon thread instead makes the dispatcher daemon too.
     */
    private static void startDaemon(HttpServer server) throws IOException {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                server.start();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "jfrom-http-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the metrics HTTP server", e);
        }
        if (failure.get() != null) {
            throw new IOException("failed to start the metrics HTTP server", failure.get());
        }
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static final class MetricsHandler implements HttpHandler {
        private final MetricRegistry registry;

        MetricsHandler(MetricRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendPlainText(exchange, 405, "method not allowed\n");
                return;
            }
            try {
                long startNanos = System.nanoTime();
                StringWriter writer = new StringWriter();
                registry.writeExposition(writer);
                double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000d;
                // Recorded for the *next* scrape to read; measuring a scrape's own duration
                // before it has finished writing its own body is not possible, and lagging by
                // one scrape is a normal, well-understood self-instrumentation trade-off.
                registry.setGauge(SCRAPE_DURATION_SPEC, Labels.empty(), elapsedSeconds);

                byte[] body = writer.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                sendPlainText(exchange, 500, "failed to render metrics: " + e + "\n");
            }
        }
    }

    private static final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path)) {
                sendPlainText(exchange, 404, "not found\n");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendPlainText(exchange, 405, "method not allowed\n");
                return;
            }
            sendPlainText(exchange, 200, "jfrom-agent\nmetrics: /metrics\n");
        }
    }

    private static void sendPlainText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
