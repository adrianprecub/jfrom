package io.jfr2grafana.sample.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls the background driver that keeps every workload family producing
 * JFR events continuously from application startup, with no human interaction.
 *
 * Bound from {@code sample.driver.*} in {@code application.yaml}.
 */
@ConfigurationProperties(prefix = "sample.driver")
public class DriverProperties {

    /** Master switch. When {@code false} the driver thread never starts. */
    private boolean enabled = true;

    /** How long each phase (churn/contend/leak/io/cpu) runs before rotating to the next. */
    private Duration phaseDuration = Duration.ofSeconds(5);

    /** 1 (gentle) .. 10 (aggressive); scales load passed into each workload. */
    private int intensity = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPhaseDuration() {
        return phaseDuration;
    }

    public void setPhaseDuration(Duration phaseDuration) {
        this.phaseDuration = phaseDuration;
    }

    public int getIntensity() {
        return intensity;
    }

    public void setIntensity(int intensity) {
        this.intensity = intensity;
    }
}
