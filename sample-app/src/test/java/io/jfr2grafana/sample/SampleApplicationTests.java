package io.jfr2grafana.sample;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The background driver is disabled via src/test/resources/application.yaml
 * so this and every other test in the module stay fast and deterministic.
 */
@SpringBootTest
class SampleApplicationTests {

    @Test
    void contextLoads(ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
