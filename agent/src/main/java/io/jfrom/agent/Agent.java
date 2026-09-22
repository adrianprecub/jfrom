package io.jfrom.agent;

import io.jfrom.agent.config.MappingConfig;
import io.jfrom.agent.config.MappingLoader;
import io.jfrom.agent.engine.MappingEngine;
import io.jfrom.agent.http.MetricsServer;
import io.jfrom.agent.metrics.DefaultMetricRegistry;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code -javaagent} entry point: parses {@link AgentOptions}, loads mapping packs, and starts
 * the {@link MappingEngine} plus {@link MetricsServer}.
 *
 * <p>No bytecode instrumentation is performed - {@code Instrumentation} is accepted only because
 * the {@code premain}/{@code agentmain} contract requires it, and is otherwise unused.
 *
 * <p><b>This agent must never break the host application.</b> The entire startup sequence is
 * wrapped in a single {@code try/catch (Throwable)}; on any failure this logs a clearly prefixed
 * message to {@code System.err} and returns normally, leaving the host JVM exactly as it would
 * have been without the agent attached. An observability agent that takes down a production
 * process is far worse than one that fails to produce metrics.
 */
public final class Agent {

    private static final String PREFIX = "jfrom: ";

    // Retained only so tests/tools can inspect what got started; not required for correctness.
    private static volatile MappingEngine engine;
    private static volatile MetricsServer server;

    private Agent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        start(agentArgs);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        start(agentArgs);
    }

    private static void start(String agentArgs) {
        boolean debug = false;
        try {
            AgentOptions options = AgentOptions.parse(agentArgs);
            debug = options.debug();

            log("starting: port=" + options.port() + " host=" + options.host()
                    + " families=" + options.families()
                    + " config=" + options.config().map(Object::toString).orElse("<none>")
                    + " debug=" + options.debug());

            MappingConfig config = loadConfig(options);
            if (config.rules().isEmpty()) {
                log("WARNING: no mapping rules loaded (no bundled packs present and no external "
                        + "'config' given). The agent will start and serve /metrics with only "
                        + "self-observability series until mapping packs are added.");
            } else if (debug) {
                log("loaded " + config.rules().size() + " rule(s)");
            }

            DefaultMetricRegistry registry = new DefaultMetricRegistry();
            MappingEngine mappingEngine = new MappingEngine(config, registry, Agent::log);
            mappingEngine.start();

            MetricsServer metricsServer = MetricsServer.start(options.host(), options.port(), registry);
            log("listening on http://" + options.host() + ":" + metricsServer.port() + "/metrics");

            engine = mappingEngine;
            server = metricsServer;
        } catch (Throwable t) {
            log("failed to start: " + t);
            if (debug) {
                t.printStackTrace(System.err);
            }
        }
    }

    /**
     * Loads mapping rules per {@link AgentOptions}: either every bundled pack, or (if
     * {@code families} was given) only the bundled packs named there, plus an external
     * {@code config} pack if one was given. A requested family with no matching bundled pack
     * file is skipped with a warning rather than failing startup - the bundled packs are
     * authored by a separate task and may not all exist yet.
     */
    private static MappingConfig loadConfig(AgentOptions options) {
        List<MappingConfig> parts = new ArrayList<>();
        if (options.families().isEmpty()) {
            parts.add(MappingLoader.loadBundledPacks());
        } else {
            for (String family : options.families()) {
                parts.add(loadFamilyPack(family));
            }
        }
        options.config().ifPresent(path -> parts.add(MappingLoader.loadPath(path)));

        try {
            return MappingConfig.merge(parts);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("failed to merge mapping configuration: " + e.getMessage(), e);
        }
    }

    private static MappingConfig loadFamilyPack(String family) {
        for (String extension : List.of(".yaml", ".yml")) {
            try {
                return MappingLoader.loadResource("mappings/" + family + extension);
            } catch (MappingLoader.MappingLoadException e) {
                // try the other extension before giving up
            }
        }
        log("WARNING: requested family '" + family + "' has no matching bundled pack under "
                + "'mappings/'; skipping it");
        return new MappingConfig(List.of());
    }

    private static void log(String message) {
        System.err.println(PREFIX + message);
    }
}
