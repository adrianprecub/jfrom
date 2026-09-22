package io.jfrom.sample.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.jfrom.sample.config.DriverProperties;
import io.jfrom.sample.config.LeakProperties;
import io.jfrom.sample.workload.ChurnWorkload;
import io.jfrom.sample.workload.ContendWorkload;
import io.jfrom.sample.workload.CpuWorkload;
import io.jfrom.sample.workload.IoWorkload;
import io.jfrom.sample.workload.LeakWorkload;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link BackgroundDriver} directly (no Spring context) so the
 * lifecycle methods run synchronously and predictably under test. Verifies
 * both halves of "controllable via config": off by default in tests, and
 * actually cycling phases when explicitly enabled.
 */
class BackgroundDriverTest {

    @Test
    void disabledByConfigNeverStartsAThread() {
        DriverProperties properties = new DriverProperties();
        properties.setEnabled(false);
        BackgroundDriver driver = newDriver(properties);

        driver.start();

        assertThat(driver.isRunning()).isFalse();
        assertThat(driver.getCurrentPhase()).isEqualTo("idle");

        driver.stop(); // must be a safe no-op when never started
    }

    @Test
    void enabledCyclesThroughPhases() {
        DriverProperties properties = new DriverProperties();
        properties.setEnabled(true);
        properties.setPhaseDuration(Duration.ofMillis(20));
        properties.setIntensity(1);
        BackgroundDriver driver = newDriver(properties);

        try {
            driver.start();
            assertThat(driver.isRunning()).isTrue();

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> !"idle".equals(driver.getCurrentPhase()));
        } finally {
            driver.stop();
        }
    }

    private static BackgroundDriver newDriver(DriverProperties properties) {
        LeakProperties leakProperties = new LeakProperties();
        leakProperties.setMaxEntries(10);
        leakProperties.setChunkSizeBytes(64);
        return new BackgroundDriver(properties,
                new ChurnWorkload(),
                new ContendWorkload(),
                new LeakWorkload(leakProperties),
                new IoWorkload(),
                new CpuWorkload());
    }
}
