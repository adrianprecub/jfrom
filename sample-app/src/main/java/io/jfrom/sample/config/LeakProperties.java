package io.jfrom.sample.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds for the {@code /load/leak} workload so a long-running demo cannot
 * exhaust the container heap. Bound from {@code sample.leak.*}.
 */
@ConfigurationProperties(prefix = "sample.leak")
public class LeakProperties {

    /** Maximum number of chunks retained at once; growth stops silently past this cap. */
    private int maxEntries = 2000;

    /** Size in bytes of each retained chunk. */
    private int chunkSizeBytes = 8192;

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }
}
