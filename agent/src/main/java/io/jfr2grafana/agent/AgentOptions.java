package io.jfr2grafana.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the {@code -javaagent:...=key=value,key2=value2} argument string.
 *
 * <p>Recognised keys: {@code port} (default {@value #DEFAULT_PORT}), {@code host} (default
 * {@value #DEFAULT_HOST}), {@code config} (optional path to an external mapping pack),
 * {@code families} (optional filter over bundled pack names), {@code debug} (boolean, default
 * {@code false}).
 *
 * <p>The top level is comma-separated {@code key=value} pairs, but {@code families} needs to
 * name several bundled packs and the natural separator for that ({@code ,}) collides with the
 * top-level one. Rather than picking a single separator, a bare token with no {@code =} (e.g.
 * the second half of {@code families=memory-gc,cpu-threads}) is treated as a continuation of the
 * previous key's value, so both {@code families=a,b} and {@code families=a+b} work, and so does
 * mixing the two.
 */
public record AgentOptions(int port, String host, Optional<Path> config, List<String> families, boolean debug) {

    public static final int DEFAULT_PORT = 9404;
    public static final String DEFAULT_HOST = "0.0.0.0";

    public AgentOptions {
        host = (host == null || host.isBlank()) ? DEFAULT_HOST : host;
        config = config == null ? Optional.empty() : config;
        families = families == null ? List.of() : List.copyOf(families);
    }

    public static AgentOptions defaults() {
        return new AgentOptions(DEFAULT_PORT, DEFAULT_HOST, Optional.empty(), List.of(), false);
    }

    /**
     * Parses the raw {@code agentArgs} string handed to {@code premain}/{@code agentmain}.
     * {@code null} or blank yields {@link #defaults()}.
     *
     * @throws IllegalArgumentException if a recognised key's value is malformed (e.g. a
     *                                   non-numeric port, or a {@code debug} value that isn't
     *                                   {@code true}/{@code false}).
     */
    public static AgentOptions parse(String agentArgs) {
        Map<String, String> raw = tokenize(agentArgs);

        int port = parsePort(raw.get("port"));
        String host = raw.get("host");
        Path config = parseConfig(raw.get("config"));
        List<String> families = parseFamilies(raw.get("families"));
        boolean debug = parseDebug(raw.get("debug"));

        return new AgentOptions(port, host, Optional.ofNullable(config), families, debug);
    }

    // ------------------------------------------------------------------
    // Tokenizing
    // ------------------------------------------------------------------

    private static Map<String, String> tokenize(String agentArgs) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (agentArgs == null || agentArgs.isBlank()) {
            return raw;
        }
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        for (String rawToken : agentArgs.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq > 0) {
                if (currentKey != null) {
                    raw.put(currentKey, currentValue.toString());
                }
                currentKey = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                currentValue = new StringBuilder(token.substring(eq + 1).trim());
            } else {
                if (currentKey == null) {
                    throw new IllegalArgumentException(
                            "malformed agent option '" + token + "': expected 'key=value'");
                }
                currentValue.append(',').append(token);
            }
        }
        if (currentKey != null) {
            raw.put(currentKey, currentValue.toString());
        }
        return raw;
    }

    // ------------------------------------------------------------------
    // Per-key parsing
    // ------------------------------------------------------------------

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("agent option 'port' must be an integer, but found '" + raw + "'");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(
                    "agent option 'port' must be between 0 and 65535, but found '" + raw + "'");
        }
        return port;
    }

    private static Path parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Path.of(raw.trim());
    }

    private static List<String> parseFamilies(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> families = new ArrayList<>();
        for (String part : raw.split("[+,]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                families.add(trimmed);
            }
        }
        return Collections.unmodifiableList(families);
    }

    private static boolean parseDebug(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return true;
        }
        if (lower.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException(
                "agent option 'debug' must be 'true' or 'false', but found '" + raw + "'");
    }
}
