package io.jfr2grafana.agent.config;

import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses mapping-pack YAML into the {@link MappingConfig} record tree.
 *
 * <p>Uses SnakeYAML's {@link SafeConstructor}, which only ever builds plain
 * {@code Map}/{@code List}/scalar values and never instantiates arbitrary classes from a tag -
 * a mapping pack is untrusted config, not code.
 *
 * <p>Every error thrown by this class names the offending file, and where applicable the rule's
 * event and the metric, plus what was wrong. Structural validation already performed by the
 * compact constructors of {@link MappingConfig}, {@link EventRule}, {@link MetricSpec},
 * {@link ValueSpec} and {@link LabelSpec} is reused rather than duplicated: this class only
 * turns YAML scalars into the right Java types and adds positional context to whatever those
 * constructors reject.
 */
public final class MappingLoader {

    /** Classpath directory (no leading/trailing slash) searched by {@link #loadBundledPacks()}. */
    private static final String BUNDLED_MAPPINGS_DIR = "mappings";

    private static final Set<String> PERIOD_LITERALS_LOWER =
            Set.of("everychunk", "beginchunk", "endchunk");

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ns|us|ms|s|m)\\s*$", Pattern.CASE_INSENSITIVE);

    private MappingLoader() {
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /** Parses one YAML document. {@code sourceName} is used only to make error messages useful. */
    public static MappingConfig load(Reader reader, String sourceName) {
        Object root = parseYaml(reader, sourceName);

        if (root == null) {
            // An empty file is a legal, empty pack.
            return new MappingConfig(List.of());
        }
        if (!(root instanceof Map<?, ?> rootMapRaw)) {
            throw new MappingLoadException(sourceName
                    + ": expected the document to be a YAML mapping with a 'rules' key, but found "
                    + describe(root));
        }
        Map<String, Object> rootMap = stringKeyed(rootMapRaw);

        Object rulesRaw = rootMap.get("rules");
        if (rulesRaw == null) {
            return new MappingConfig(List.of());
        }
        if (!(rulesRaw instanceof List<?> rulesList)) {
            throw new MappingLoadException(
                    sourceName + ": 'rules' must be a list, but found " + describe(rulesRaw));
        }

        List<EventRule> rules = new ArrayList<>();
        int index = 0;
        for (Object ruleObj : rulesList) {
            rules.add(parseRule(ruleObj, sourceName, index));
            index++;
        }

        try {
            return new MappingConfig(rules);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(sourceName + ": " + e.getMessage(), e);
        }
    }

    public static MappingConfig load(Reader reader) {
        return load(reader, "<reader>");
    }

    public static MappingConfig load(InputStream in, String sourceName) {
        return load(new InputStreamReader(in, StandardCharsets.UTF_8), sourceName);
    }

    public static MappingConfig load(InputStream in) {
        return load(in, "<input-stream>");
    }

    /** Loads one mapping pack from the classpath, e.g. {@code "mappings/memory-gc.yaml"}. */
    public static MappingConfig loadResource(String classpathResource) {
        ClassLoader loader = MappingLoader.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new MappingLoadException("classpath resource not found: '" + classpathResource + "'");
            }
            return load(in, "classpath:" + classpathResource);
        } catch (IOException e) {
            throw new MappingLoadException(
                    "failed to read classpath resource '" + classpathResource + "': " + e.getMessage(), e);
        }
    }

    /** Loads one mapping pack from a filesystem path. */
    public static MappingConfig loadPath(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader, path.toString());
        } catch (IOException e) {
            throw new MappingLoadException(
                    "failed to read mapping file '" + path + "': " + e.getMessage(), e);
        }
    }

    /**
     * Loads and merges every {@code *.yaml}/{@code *.yml} file under {@code classpath:/mappings/}
     * (the four bundled packs: memory-gc, cpu-threads, locks-safepoints, jit-io-class).
     *
     * <p>Tolerates a missing or empty {@code mappings/} directory - it simply yields an empty
     * {@link MappingConfig} - since the bundled packs are authored by a separate task and may not
     * exist yet on some builds.
     */
    public static MappingConfig loadBundledPacks() {
        List<String> names = listBundledPackNames();
        List<MappingConfig> parts = new ArrayList<>();
        for (String name : names) {
            parts.add(loadResource(BUNDLED_MAPPINGS_DIR + "/" + name));
        }
        try {
            return MappingConfig.merge(parts);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(
                    "failed to merge bundled mapping packs " + names + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // YAML parsing
    // ------------------------------------------------------------------

    private static Object parseYaml(Reader reader, String sourceName) {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            return yaml.load(reader);
        } catch (RuntimeException e) {
            // Covers YAMLException (bad syntax) and ConstructorException (an attempt to
            // instantiate a type SafeConstructor refuses to build, e.g. !!java.net.URL).
            throw new MappingLoadException(
                    "failed to parse YAML in '" + sourceName + "': " + e.getMessage(), e);
        }
    }

    private static EventRule parseRule(Object ruleObj, String sourceName, int index) {
        Map<String, Object> ruleMap = asMap(ruleObj, sourceName, "rules[" + index + "]");
        String event = asStringOrNull(ruleMap.get("event"));
        String context = sourceName + ": rules[" + index + "]"
                + (event != null ? " (event '" + event + "')" : "");

        EnableSpec enable = parseEnable(ruleMap.get("enable"), context);
        Map<String, String> filter = parseFilter(ruleMap.get("filter"), context);
        List<MetricSpec> metrics = parseMetrics(ruleMap.get("metrics"), context);

        try {
            return new EventRule(event, enable, filter, metrics);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(context + ": " + e.getMessage(), e);
        }
    }

    private static EnableSpec parseEnable(Object raw, String context) {
        if (raw == null) {
            return EnableSpec.defaults();
        }
        Map<String, Object> map = asMap(raw, context, "enable");

        String period = asStringOrNull(map.get("period"));
        if (period != null) {
            validatePeriod(period, context);
        }
        Duration threshold = parseDurationField(map.get("threshold"), context, "enable.threshold");
        boolean stackTrace = asBoolean(map.get("stackTrace"), context, "enable.stackTrace", false);

        try {
            return new EnableSpec(period, threshold, stackTrace);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(context + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, String> parseFilter(Object raw, String context) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> map = asMap(raw, context, "filter");
        Map<String, String> filter = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            filter.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().toString());
        }
        return filter;
    }

    private static List<MetricSpec> parseMetrics(Object metricsObj, String ruleContext) {
        if (metricsObj == null) {
            // Legal shape-wise; EventRule's own constructor rejects "no metrics" with context.
            return List.of();
        }
        if (!(metricsObj instanceof List<?> metricsList)) {
            throw new MappingLoadException(
                    ruleContext + ": 'metrics' must be a list, but found " + describe(metricsObj));
        }
        List<MetricSpec> metrics = new ArrayList<>();
        int index = 0;
        for (Object metricObj : metricsList) {
            metrics.add(parseMetric(metricObj, ruleContext, index));
            index++;
        }
        return metrics;
    }

    private static MetricSpec parseMetric(Object metricObj, String ruleContext, int index) {
        Map<String, Object> map = asMap(metricObj, ruleContext, "metrics[" + index + "]");
        String name = asStringOrNull(map.get("name"));
        String context = ruleContext + ", metrics[" + index + "]"
                + (name != null ? " (metric '" + name + "')" : "");

        MetricType type = parseEnum(map.get("type"), MetricType.class, context + ": type");
        String help = asStringOrNull(map.get("help"));
        ValueSpec value = parseValue(map.get("value"), context);
        List<LabelSpec> labels = parseLabels(map.get("labels"), context);
        List<Double> buckets = parseBuckets(map.get("buckets"), context);
        int maxCardinality = asInt(map.get("maxCardinality"), context, "maxCardinality");

        try {
            return new MetricSpec(name, type, help, value, labels, buckets, maxCardinality);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(context + ": " + e.getMessage(), e);
        }
    }

    private static ValueSpec parseValue(Object raw, String context) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> map = asMap(raw, context, "value");
        String field = asStringOrNull(map.get("field"));
        ValueKind kind = parseEnum(map.get("kind"), ValueKind.class, context + ": value.kind");
        DurationUnit unit = map.get("unit") == null
                ? null
                : parseEnum(map.get("unit"), DurationUnit.class, context + ": value.unit");

        try {
            return new ValueSpec(field, kind, unit);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(context + ": value: " + e.getMessage(), e);
        }
    }

    private static List<LabelSpec> parseLabels(Object raw, String context) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new MappingLoadException(context + ": 'labels' must be a list, but found " + describe(raw));
        }
        List<LabelSpec> labels = new ArrayList<>();
        int index = 0;
        for (Object labelObj : list) {
            labels.add(parseLabel(labelObj, context, index));
            index++;
        }
        return labels;
    }

    private static LabelSpec parseLabel(Object raw, String context, int index) {
        String labelContext = context + ": labels[" + index + "]";
        Map<String, Object> map = asMap(raw, context, "labels[" + index + "]");
        String name = asStringOrNull(map.get("name"));
        String field = asStringOrNull(map.get("field"));
        LabelKind kind = parseEnum(map.get("kind"), LabelKind.class, labelContext + ".kind");

        try {
            return new LabelSpec(name, field, kind);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(labelContext + ": " + e.getMessage(), e);
        }
    }

    private static List<Double> parseBuckets(Object raw, String context) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new MappingLoadException(
                    context + ": 'buckets' must be a list of numbers, but found " + describe(raw));
        }
        List<Double> buckets = new ArrayList<>();
        for (Object element : list) {
            buckets.add(asDouble(element, context, "buckets"));
        }
        return buckets;
    }

    // ------------------------------------------------------------------
    // Duration / period parsing
    // ------------------------------------------------------------------

    private static void validatePeriod(String period, String context) {
        if (PERIOD_LITERALS_LOWER.contains(period.toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            parseDurationString(period);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(context + ": enable.period '" + period
                    + "' must be 'everyChunk', 'beginChunk', 'endChunk', or a duration like '10ms' ("
                    + e.getMessage() + ")");
        }
    }

    private static Duration parseDurationField(Object raw, String context, String fieldName) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString();
        try {
            return parseDurationString(text);
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(context + ": " + fieldName + " " + e.getMessage());
        }
    }

    /**
     * Parses a duration like {@code "10ms"}, {@code "1s"}, {@code "0ms"}, {@code "500us"} or
     * {@code "2m"}. Deliberately not {@link Duration#parse}, which requires ISO-8601 ({@code
     * "PT10S"}) - hostile to hand-write in a config file.
     */
    private static Duration parseDurationString(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "is not a valid duration: '" + text + "' (expected e.g. '10ms', '1s', '500us', '2m', '0ms')");
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        double nanos = switch (unit) {
            case "ns" -> amount;
            case "us" -> amount * 1_000d;
            case "ms" -> amount * 1_000_000d;
            case "s" -> amount * 1_000_000_000d;
            case "m" -> amount * 60_000_000_000d;
            default -> throw new IllegalStateException("unreachable: unit '" + unit + "'");
        };
        return Duration.ofNanos(Math.round(nanos));
    }

    // ------------------------------------------------------------------
    // Small scalar/coercion helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> asMap(Object raw, String context, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new MappingLoadException(
                    context + ": '" + field + "' must be a mapping, but found " + describe(raw));
        }
        return stringKeyed(map);
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String asStringOrNull(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static boolean asBoolean(Object raw, String context, String field, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("true")) {
            return true;
        }
        if (text.equals("false")) {
            return false;
        }
        throw new MappingLoadException(
                context + ": " + field + " must be a boolean ('true'/'false'), but found '" + raw + "'");
    }

    private static int asInt(Object raw, String context, String field) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new MappingLoadException(
                    context + ": " + field + " must be an integer, but found '" + raw + "'");
        }
    }

    private static double asDouble(Object raw, String context, String field) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new MappingLoadException(
                    context + ": each entry in '" + field + "' must be a number, but found '" + raw + "'");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Object raw, Class<E> type, String context) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString();
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MappingLoadException(
                    context + ": unknown value '" + text + "'; expected one of " + enumNames(type));
        }
    }

    private static <E extends Enum<E>> String enumNames(Class<E> type) {
        StringBuilder sb = new StringBuilder("[");
        E[] values = type.getEnumConstants();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i].name().toLowerCase(Locale.ROOT));
        }
        return sb.append(']').toString();
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List) {
            return "a list";
        }
        if (value instanceof Map) {
            return "a mapping";
        }
        return "a scalar ('" + value + "')";
    }

    // ------------------------------------------------------------------
    // Bundled-pack discovery
    // ------------------------------------------------------------------

    private static List<String> listBundledPackNames() {
        List<String> names = new ArrayList<>();
        try {
            Enumeration<URL> dirs = MappingLoader.class.getClassLoader().getResources(BUNDLED_MAPPINGS_DIR);
            while (dirs.hasMoreElements()) {
                names.addAll(listPackNamesAt(dirs.nextElement()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to enumerate bundled mapping packs on the classpath", e);
        }
        return names.stream().distinct().sorted().toList();
    }

    private static List<String> listPackNamesAt(URL dirUrl) throws IOException {
        List<String> names = new ArrayList<>();
        switch (dirUrl.getProtocol()) {
            case "file" -> {
                Path dir;
                try {
                    dir = Path.of(dirUrl.toURI());
                } catch (URISyntaxException e) {
                    throw new IOException(e);
                }
                if (Files.isDirectory(dir)) {
                    try (var stream = Files.list(dir)) {
                        stream.map(p -> p.getFileName().toString())
                                .filter(MappingLoader::isYamlFile)
                                .forEach(names::add);
                    }
                }
            }
            case "jar" -> {
                JarURLConnection connection = (JarURLConnection) dirUrl.openConnection();
                try (JarFile jarFile = connection.getJarFile()) {
                    String prefix = BUNDLED_MAPPINGS_DIR + "/";
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        if (!entry.isDirectory() && entryName.startsWith(prefix) && isYamlFile(entryName)) {
                            String base = entryName.substring(prefix.length());
                            if (!base.contains("/")) {
                                names.add(base);
                            }
                        }
                    }
                }
            }
            default -> {
                // Unsupported classpath entry protocol (e.g. a custom VFS) - nothing we can list;
                // tolerate it rather than failing the whole load.
            }
        }
        return names;
    }

    private static boolean isYamlFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    /** Thrown for any problem loading or parsing a mapping pack. Always names the file and, where
     * known, the rule's event and the metric, plus what was wrong. */
    public static final class MappingLoadException extends RuntimeException {

        public MappingLoadException(String message) {
            super(message);
        }

        public MappingLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
