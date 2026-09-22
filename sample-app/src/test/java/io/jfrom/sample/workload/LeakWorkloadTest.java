package io.jfrom.sample.workload;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfrom.sample.config.LeakProperties;
import io.jfrom.sample.workload.LeakWorkload.LeakResult;
import org.junit.jupiter.api.Test;

/** Pure unit test - no Spring context needed. */
class LeakWorkloadTest {

    @Test
    void growStopsAtTheConfiguredCap() {
        LeakProperties properties = new LeakProperties();
        properties.setMaxEntries(5);
        properties.setChunkSizeBytes(128);
        LeakWorkload workload = new LeakWorkload(properties);

        LeakResult first = workload.grow(3);
        assertThat(first.currentSize()).isEqualTo(3);
        assertThat(first.added()).isEqualTo(3);
        assertThat(first.maxEntries()).isEqualTo(5);

        LeakResult second = workload.grow(10); // asks for more than the remaining headroom
        assertThat(second.added()).isEqualTo(2); // only 2 more fit before hitting the cap
        assertThat(second.currentSize()).isEqualTo(5);
        assertThat(workload.size()).isEqualTo(5);

        // Cap holds even when asked again with plenty of room left in the request.
        LeakResult atCap = workload.grow(100);
        assertThat(atCap.added()).isZero();
        assertThat(atCap.currentSize()).isEqualTo(5);
    }

    @Test
    void resetFreesEveryRetainedReference() {
        LeakProperties properties = new LeakProperties();
        properties.setMaxEntries(10);
        properties.setChunkSizeBytes(64);
        LeakWorkload workload = new LeakWorkload(properties);

        workload.grow(10);
        assertThat(workload.size()).isEqualTo(10);

        workload.reset();
        assertThat(workload.size()).isZero();

        // The collection is usable again after reset, not permanently exhausted.
        LeakResult afterReset = workload.grow(4);
        assertThat(afterReset.currentSize()).isEqualTo(4);
        assertThat(afterReset.added()).isEqualTo(4);
    }

    @Test
    void negativeAmountAddsNothing() {
        LeakProperties properties = new LeakProperties();
        LeakWorkload workload = new LeakWorkload(properties);

        LeakResult result = workload.grow(-5);
        assertThat(result.added()).isZero();
        assertThat(result.currentSize()).isZero();
    }
}
