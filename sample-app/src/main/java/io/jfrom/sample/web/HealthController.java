package io.jfrom.sample.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Simple liveness endpoint. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Health health() {
        return new Health("ok", Instant.now().toString());
    }

    public record Health(String status, String timestamp) {
    }
}
