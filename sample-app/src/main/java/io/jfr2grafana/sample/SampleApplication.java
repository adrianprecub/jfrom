package io.jfr2grafana.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point for the workload app the agent is demonstrated against. */
@SpringBootApplication
@ConfigurationPropertiesScan("io.jfr2grafana.sample.config")
public class SampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
