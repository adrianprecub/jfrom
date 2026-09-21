package io.jfr2grafana.sample.driver;

import io.jfr2grafana.sample.config.DriverProperties;
import io.jfr2grafana.sample.workload.ChurnWorkload;
import io.jfr2grafana.sample.workload.ContendWorkload;
import io.jfr2grafana.sample.workload.CpuWorkload;
import io.jfr2grafana.sample.workload.IoWorkload;
import io.jfr2grafana.sample.workload.LeakWorkload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cycles continuously through every workload phase from application startup
 * so Grafana dashboards are never empty and no human interaction is needed.
 *
 * <p>On by default; disable with {@code sample.driver.enabled=false}. Runs on
 * a single daemon thread and paces itself to {@code sample.driver.phase-duration}
 * per phase, so it stays gentle enough for a modest container heap indefinitely.
 */
@Component
public class BackgroundDriver {

    private static final Logger log = LoggerFactory.getLogger(BackgroundDriver.class);
    private static final List<String> PHASES = List.of("churn", "contend", "leak", "io", "cpu");
    private static final long MIN_PHASE_MS = 200;

    private final DriverProperties properties;
    private final ChurnWorkload churnWorkload;
    private final ContendWorkload contendWorkload;
    private final LeakWorkload leakWorkload;
    private final IoWorkload ioWorkload;
    private final CpuWorkload cpuWorkload;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String currentPhase = "idle";
    private Thread thread;

    public BackgroundDriver(DriverProperties properties,
                             ChurnWorkload churnWorkload,
                             ContendWorkload contendWorkload,
                             LeakWorkload leakWorkload,
                             IoWorkload ioWorkload,
                             CpuWorkload cpuWorkload) {
        this.properties = properties;
        this.churnWorkload = churnWorkload;
        this.contendWorkload = contendWorkload;
        this.leakWorkload = leakWorkload;
        this.ioWorkload = ioWorkload;
        this.cpuWorkload = cpuWorkload;
    }

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("Background driver disabled (sample.driver.enabled=false)");
            return;
        }
        running.set(true);
        thread = new Thread(this::cycle, "sample-background-driver");
        thread.setDaemon(true);
        thread.start();
        log.info("Background driver started: phaseDuration={} intensity={}",
                properties.getPhaseDuration(), properties.getIntensity());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Whether the driver thread is active. False when disabled by configuration. */
    public boolean isRunning() {
        return running.get();
    }

    /** The workload family currently executing, for diagnostics/tests. */
    public String getCurrentPhase() {
        return currentPhase;
    }

    private void cycle() {
        long phaseMs = Math.max(MIN_PHASE_MS, properties.getPhaseDuration().toMillis());
        int intensity = Math.max(1, Math.min(properties.getIntensity(), 10));
        int index = 0;
        while (running.get()) {
            String phase = PHASES.get(index % PHASES.size());
            currentPhase = phase;
            long start = System.currentTimeMillis();
            try {
                runPhase(phase, intensity, phaseMs);
            } catch (Exception e) {
                log.warn("Background driver phase '{}' failed: {}", phase, e.toString());
            }
            long remaining = phaseMs - (System.currentTimeMillis() - start);
            if (remaining > 0 && running.get()) {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            index++;
        }
        log.info("Background driver stopped");
    }

    private void runPhase(String phase, int intensity, long phaseMs) throws Exception {
        switch (phase) {
            case "churn" -> churnWorkload.run(intensity, phaseMs);
            case "contend" -> contendWorkload.run(2 + intensity, 2, phaseMs);
            case "leak" -> leakWorkload.grow(intensity * 5);
            case "io" -> ioWorkload.run(Math.max(1, intensity * 4));
            case "cpu" -> cpuWorkload.run(Math.max(1, intensity / 2), phaseMs);
            default -> { }
        }
    }
}
