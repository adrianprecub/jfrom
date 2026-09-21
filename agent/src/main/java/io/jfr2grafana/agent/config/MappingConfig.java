package io.jfr2grafana.agent.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded set of mapping rules, typically merged from several YAML packs
 * (memory-gc, cpu-threads, locks-safepoints, jit-io-class).
 */
public record MappingConfig(List<EventRule> rules) {

    public MappingConfig {
        rules = rules == null ? List.of() : List.copyOf(rules);

        // A duplicated metric name across packs would emit two conflicting HELP/TYPE headers
        // for one series, which Prometheus rejects on scrape. Fail at load instead.
        Map<String, String> owners = new HashMap<>();
        for (EventRule rule : rules) {
            for (MetricSpec metric : rule.metrics()) {
                String previous = owners.putIfAbsent(metric.name(), rule.event());
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "metric '" + metric.name() + "' is declared by both '" + previous
                                    + "' and '" + rule.event() + "'; names must be unique");
                }
            }
        }
    }

    /** Merge several packs into one config. */
    public static MappingConfig merge(List<MappingConfig> parts) {
        List<EventRule> all = new ArrayList<>();
        for (MappingConfig part : parts) {
            all.addAll(part.rules());
        }
        return new MappingConfig(all);
    }
}
