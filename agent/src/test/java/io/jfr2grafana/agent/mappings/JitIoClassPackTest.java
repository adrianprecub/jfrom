package io.jfr2grafana.agent.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfr2grafana.agent.config.EventRule;
import io.jfr2grafana.agent.config.LabelKind;
import io.jfr2grafana.agent.config.LabelSpec;
import io.jfr2grafana.agent.config.MappingConfig;
import io.jfr2grafana.agent.config.MappingLoader;
import io.jfr2grafana.agent.config.MetricSpec;
import io.jfr2grafana.agent.config.ValueKind;
import io.jfr2grafana.agent.engine.RuleEvaluator;
import io.jfr2grafana.agent.event.RecordedEventView;
import io.jfr2grafana.agent.metrics.DefaultMetricRegistry;
import io.jfr2grafana.agent.metrics.MetricType;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code jit-io-class.yaml} mapping pack: that it parses into the expected metrics,
 * and that replaying the committed fixture through {@link RuleEvaluator} produces sane series.
 *
 * <p>Every expected numeric outcome below is computed directly from the fixture's real
 * {@code RecordedEvent}s (max/sum of the relevant field), never hard-coded, so the test stays
 * correct if the fixture is ever regenerated with slightly different values - it only assumes
 * the event counts stated in the task ({@code Compilation(216)}, etc.), which it also asserts
 * explicitly as a guard against silently drifting off a stale fixture.
 */
class JitIoClassPackTest {

    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "jfr_jit_", "jfr_class_", "jfr_classes_", "jfr_socket_", "jfr_file_",
            "jfr_exception_", "jfr_exceptions_");

    private static MappingConfig pack;
    private static Map<String, List<RecordedEvent>> byName;

    @BeforeAll
    static void loadPackAndFixture() throws IOException, URISyntaxException {
        pack = MappingLoader.loadResource("mappings/jit-io-class.yaml");

        Path fixture = Path.of(JitIoClassPackTest.class.getResource("/fixtures/sample.jfr").toURI());
        List<RecordedEvent> events = RecordingFile.readAllEvents(fixture);
        byName = events.stream().collect(Collectors.groupingBy(e -> e.getEventType().getName()));
    }

    // ------------------------------------------------------------------
    // 1. The pack parses into the expected metrics, types, and labels.
    // ------------------------------------------------------------------

    @Test
    void packDeclaresEightRulesCoveringEveryRequiredEvent() {
        assertThat(pack.rules()).extracting(EventRule::event).containsExactlyInAnyOrder(
                "jdk.Compilation", "jdk.CompilerStatistics", "jdk.ClassLoadingStatistics",
                "jdk.ExceptionStatistics", "jdk.SocketRead", "jdk.SocketWrite",
                "jdk.FileRead", "jdk.FileWrite");
    }

    @Test
    void compilationRuleDeclaresDurationHistogramCodeSizeHistogramAndCounter() {
        EventRule rule = ruleFor("jdk.Compilation");
        assertThat(rule.enable().threshold()).isEqualTo(java.time.Duration.ofMillis(1));
        assertThat(rule.enable().stackTrace()).isFalse();

        MetricSpec compileSeconds = metricFor(rule, "jfr_jit_compile_seconds");
        assertThat(compileSeconds.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(compileSeconds.value().field()).isEqualTo("duration");
        assertThat(compileSeconds.value().kind()).isEqualTo(ValueKind.DURATION);
        assertThat(compileSeconds.labels()).containsExactly(
                new LabelSpec("compiler", "compiler", LabelKind.STRING),
                new LabelSpec("level", "compileLevel", LabelKind.INT));
        assertThat(compileSeconds.buckets()).isNotEmpty();

        MetricSpec codeBytes = metricFor(rule, "jfr_jit_compiled_code_bytes");
        assertThat(codeBytes.type()).isEqualTo(MetricType.HISTOGRAM);
        assertThat(codeBytes.value().field()).isEqualTo("codeSize");
        assertThat(codeBytes.value().kind()).isEqualTo(ValueKind.NUMBER);

        MetricSpec compilationsTotal = metricFor(rule, "jfr_jit_compilations_total");
        assertThat(compilationsTotal.type()).isEqualTo(MetricType.COUNTER);
        assertThat(compilationsTotal.value()).isNull(); // counts events
        assertThat(compilationsTotal.labels()).contains(
                new LabelSpec("succeeded", "succeded", LabelKind.BOOLEAN),
                new LabelSpec("osr", "isOsr", LabelKind.BOOLEAN));
    }

    @Test
    void compilerStatisticsFieldsAreAllCounterAbsoluteNeverCounter() {
        EventRule rule = ruleFor("jdk.CompilerStatistics");
        for (String name : List.of(
                "jfr_jit_compiler_compiled_methods_total",
                "jfr_jit_compiler_bailouts_total",
                "jfr_jit_compiler_standard_bytes_compiled_total",
                "jfr_jit_compiler_osr_bytes_compiled_total",
                "jfr_jit_compiler_nmethods_bytes_total",
                "jfr_jit_compiler_time_seconds_total")) {
            assertThat(metricFor(rule, name).type())
                    .as("metric '%s' must be counter_absolute (cumulative JVM total)", name)
                    .isEqualTo(MetricType.COUNTER_ABSOLUTE);
        }
        assertThat(metricFor(rule, "jfr_jit_compiler_time_seconds_total").value().kind())
                .isEqualTo(ValueKind.DURATION);
    }

    @Test
    void classLoadingAndExceptionStatisticsAreCounterAbsolute() {
        EventRule classRule = ruleFor("jdk.ClassLoadingStatistics");
        assertThat(metricFor(classRule, "jfr_classes_loaded_total").type())
                .isEqualTo(MetricType.COUNTER_ABSOLUTE);
        assertThat(metricFor(classRule, "jfr_classes_unloaded_total").type())
                .isEqualTo(MetricType.COUNTER_ABSOLUTE);

        EventRule exceptionRule = ruleFor("jdk.ExceptionStatistics");
        assertThat(metricFor(exceptionRule, "jfr_exceptions_thrown_total").type())
                .isEqualTo(MetricType.COUNTER_ABSOLUTE);
    }

    @Test
    void socketAndFileByteCountersAreAddingCountersNeverAbsolute() {
        for (String event : List.of("jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite")) {
            EventRule rule = ruleFor(event);
            for (MetricSpec metric : rule.metrics()) {
                if (metric.name().endsWith("_bytes_total")) {
                    assertThat(metric.type())
                            .as("metric '%s' sums a per-event delta, must be counter", metric.name())
                            .isEqualTo(MetricType.COUNTER);
                }
                if (metric.name().endsWith("_seconds")) {
                    assertThat(metric.type()).isEqualTo(MetricType.HISTOGRAM);
                    assertThat(metric.value().kind()).isEqualTo(ValueKind.DURATION);
                }
            }
        }
    }

    @Test
    void noMetricLabelsByPathHostOrAddress() {
        for (EventRule rule : pack.rules()) {
            for (MetricSpec metric : rule.metrics()) {
                for (LabelSpec label : metric.labels()) {
                    assertThat(label.field())
                            .as("metric '%s' must not label by unbounded field '%s'", metric.name(), label.field())
                            .isNotIn("path", "host", "address");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Namespace guard: every metric name starts with an allowed prefix.
    // ------------------------------------------------------------------

    @Test
    void everyMetricNameStartsWithAnAllowedPrefix() {
        for (EventRule rule : pack.rules()) {
            for (MetricSpec metric : rule.metrics()) {
                boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(metric.name()::startsWith);
                assertThat(allowed)
                        .as("metric '%s' (from %s) must start with one of %s", metric.name(), rule.event(),
                                ALLOWED_PREFIXES)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. Fixture replay through RuleEvaluator into a real registry.
    // ------------------------------------------------------------------

    @Test
    void fixtureHasTheExpectedEventCountsPerTheTaskDescription() {
        assertThat(count("jdk.Compilation")).isEqualTo(216);
        assertThat(count("jdk.CompilerStatistics")).isEqualTo(7);
        assertThat(count("jdk.ClassLoadingStatistics")).isEqualTo(7);
        assertThat(count("jdk.ExceptionStatistics")).isEqualTo(7);
        assertThat(count("jdk.SocketRead")).isEqualTo(2);
        assertThat(count("jdk.SocketWrite")).isEqualTo(2);
        assertThat(count("jdk.FileRead")).isEqualTo(18);
        assertThat(count("jdk.FileWrite")).isEqualTo(1);
    }

    @Test
    void replayingTheFixtureProducesSaneSeriesForEveryMetric() throws IOException {
        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        replayAll(registry);
        String body = exposition(registry);

        // --- jdk.Compilation: per-event counter, and duration/size histograms -----------------
        assertThat(sumOf(body, "jfr_jit_compilations_total")).isEqualTo(count("jdk.Compilation"));

        double compileSecondsCount = sumOf(body, "jfr_jit_compile_seconds_count");
        double compileSecondsSum = sumOf(body, "jfr_jit_compile_seconds_sum");
        assertThat(compileSecondsCount).isGreaterThan(0);
        // Plausible seconds range: a real JIT compile is microseconds to low milliseconds, never
        // anywhere near 10^9 - the value getLong("duration") would yield if TICKS were
        // misread as a raw number instead of via getDuration().
        double avgCompileSeconds = compileSecondsSum / compileSecondsCount;
        assertThat(avgCompileSeconds).isGreaterThan(0).isLessThan(1.0);

        double codeBytesCount = sumOf(body, "jfr_jit_compiled_code_bytes_count");
        assertThat(codeBytesCount).isGreaterThan(0);

        // --- jdk.CompilerStatistics: cumulative JVM totals, so the registry must land on the max
        // ever reported, not the sum of all seven periodic snapshots. -------------------------
        assertMatchesMax(body, "jfr_jit_compiler_compiled_methods_total", "jdk.CompilerStatistics", "compileCount");
        assertMatchesMax(body, "jfr_jit_compiler_bailouts_total", "jdk.CompilerStatistics", "bailoutCount");
        assertMatchesMax(body, "jfr_jit_compiler_standard_bytes_compiled_total", "jdk.CompilerStatistics",
                "standardBytesCompiled");
        assertMatchesMax(body, "jfr_jit_compiler_osr_bytes_compiled_total", "jdk.CompilerStatistics",
                "osrBytesCompiled");
        assertMatchesMax(body, "jfr_jit_compiler_nmethods_bytes_total", "jdk.CompilerStatistics", "nmethodsSize");
        double compilerTimeSeconds = sumOf(body, "jfr_jit_compiler_time_seconds_total");
        assertThat(compilerTimeSeconds).isGreaterThanOrEqualTo(0).isLessThan(60.0);

        // --- jdk.ClassLoadingStatistics / jdk.ExceptionStatistics: also cumulative totals -----
        assertMatchesMax(body, "jfr_classes_loaded_total", "jdk.ClassLoadingStatistics", "loadedClassCount");
        assertMatchesMax(body, "jfr_classes_unloaded_total", "jdk.ClassLoadingStatistics", "unloadedClassCount");
        assertMatchesMax(body, "jfr_exceptions_thrown_total", "jdk.ExceptionStatistics", "throwables");
        assertThat(sumOf(body, "jfr_classes_loaded_total")).isGreaterThan(0);

        // --- jdk.SocketRead/Write, jdk.FileRead/Write: bytes are summed per-event deltas ------
        assertMatchesSum(body, "jfr_socket_read_bytes_total", "jdk.SocketRead", "bytesRead");
        assertMatchesSum(body, "jfr_socket_write_bytes_total", "jdk.SocketWrite", "bytesWritten");
        assertMatchesSum(body, "jfr_file_read_bytes_total", "jdk.FileRead", "bytesRead");
        assertMatchesSum(body, "jfr_file_write_bytes_total", "jdk.FileWrite", "bytesWritten");

        for (String metric : List.of(
                "jfr_socket_read_seconds", "jfr_socket_write_seconds",
                "jfr_file_read_seconds", "jfr_file_write_seconds")) {
            double sum = sumOf(body, metric + "_sum");
            double n = sumOf(body, metric + "_count");
            assertThat(n).as("%s_count", metric).isGreaterThan(0);
            assertThat(sum / n).as("%s average", metric).isGreaterThan(0).isLessThan(5.0);
        }
    }

    // ------------------------------------------------------------------
    // 3. The JDK's own "succeded" typo must be spelled that way, and must resolve.
    // ------------------------------------------------------------------

    @Test
    void succededIsSpelledTheJdksWayAndResolvesAgainstTheFixture() throws IOException {
        LabelSpec succeededLabel = metricFor(ruleFor("jdk.Compilation"), "jfr_jit_compilations_total")
                .labels().stream()
                .filter(l -> l.name().equals("succeeded"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a 'succeeded' label on jfr_jit_compilations_total"));
        assertThat(succeededLabel.field())
                .as("must read the JDK's own field spelling, not a 'fixed' one")
                .isEqualTo("succeded");

        RecordedEvent compilation = byName.get("jdk.Compilation").get(0);
        RecordedEventView view = new RecordedEventView(compilation);

        // The typo'd field is genuinely present on the real event...
        assertThat(view.hasField("succeded")).isTrue();
        // ...while the "corrected" spelling is not: a mapping pack that "fixed" the typo would
        // silently resolve to nothing on every single Compilation event in this fixture.
        assertThat(view.hasField("succeeded")).isFalse();

        DefaultMetricRegistry registry = new DefaultMetricRegistry();
        replayAll(registry);
        String body = exposition(registry);

        long trueCount = byName.get("jdk.Compilation").stream()
                .filter(e -> new RecordedEventView(e).getBoolean("succeded"))
                .count();
        long falseCount = byName.get("jdk.Compilation").size() - trueCount;

        if (trueCount > 0) {
            assertThat(body).contains("succeeded=\"true\"");
        }
        if (falseCount > 0) {
            assertThat(body).contains("succeeded=\"false\"");
        }
        assertThat(trueCount + falseCount).isEqualTo(byName.get("jdk.Compilation").size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static EventRule ruleFor(String event) {
        return pack.rules().stream()
                .filter(r -> r.event().equals(event))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule for event '" + event + "'"));
    }

    private static MetricSpec metricFor(EventRule rule, String metricName) {
        return rule.metrics().stream()
                .filter(m -> m.name().equals(metricName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no metric '" + metricName + "' on rule for '" + rule.event() + "'"));
    }

    private static int count(String event) {
        return byName.getOrDefault(event, List.of()).size();
    }

    private static void replayAll(DefaultMetricRegistry registry) {
        for (EventRule rule : pack.rules()) {
            for (RecordedEvent event : byName.getOrDefault(rule.event(), List.of())) {
                RuleEvaluator.evaluate(rule, new RecordedEventView(event), registry);
            }
        }
    }

    private static String exposition(DefaultMetricRegistry registry) throws IOException {
        StringBuilder sb = new StringBuilder();
        registry.writeExposition(sb);
        return sb.toString();
    }

    /** Sums the sample value across every label combination for one exact metric name. */
    private static double sumOf(String body, String exactMetricName) {
        double total = 0;
        for (String line : body.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int brace = line.indexOf('{');
            int space = line.indexOf(' ');
            int nameEnd = brace >= 0 && (space < 0 || brace < space) ? brace : space;
            if (nameEnd < 0) {
                continue;
            }
            String name = line.substring(0, nameEnd);
            if (name.equals(exactMetricName)) {
                total += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1).trim());
            }
        }
        return total;
    }

    /** Asserts a counter_absolute metric equals the max of {@code field} across real fixture events. */
    private static void assertMatchesMax(String body, String metricName, String event, String field) {
        long expectedMax = byName.get(event).stream()
                .mapToLong(e -> new RecordedEventView(e).getLong(field))
                .max()
                .orElseThrow();
        assertThat(sumOf(body, metricName))
                .as("%s should be the max cumulative %s.%s ever reported, not a sum of snapshots",
                        metricName, event, field)
                .isEqualTo((double) expectedMax);
    }

    /** Asserts a counter metric equals the sum of {@code field} (a per-event delta) across fixture events. */
    private static void assertMatchesSum(String body, String metricName, String event, String field) {
        long expectedSum = byName.get(event).stream()
                .mapToLong(e -> new RecordedEventView(e).getLong(field))
                .sum();
        assertThat(sumOf(body, metricName))
                .as("%s should sum the per-event delta %s.%s", metricName, event, field)
                .isEqualTo((double) expectedSum);
    }
}
