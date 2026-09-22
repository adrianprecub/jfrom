package io.jfrom.agent.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Replays the committed {@code fixtures/sample.jfr} recording (see {@link GenerateFixture}) and
 * asserts {@link RecordedEventView}'s behaviour against real {@code RecordedEvent} instances -
 * dotted nested paths, TICKS-to-{@code Duration} conversion, {@code RecordedClass}/
 * {@code RecordedThread} resolution, and missing/null-field safety.
 */
class RecordedEventViewTest {

    private static List<RecordedEvent> events;
    private static Map<String, List<RecordedEvent>> byName;

    @BeforeAll
    static void loadFixture() throws IOException, URISyntaxException {
        Path fixture = Path.of(RecordedEventViewTest.class.getResource("/fixtures/sample.jfr").toURI());
        events = RecordingFile.readAllEvents(fixture);
        byName = events.stream().collect(Collectors.groupingBy(e -> e.getEventType().getName()));
    }

    private static RecordedEvent first(String eventName) {
        List<RecordedEvent> matches = byName.get(eventName);
        assertThat(matches).as("no %s events in fixture", eventName).isNotNull().isNotEmpty();
        return matches.get(0);
    }

    @Test
    void fixtureCoversAllFourEventFamilies() {
        assertThat(events).isNotEmpty();

        // Memory / GC
        assertThat(byName).containsKeys("jdk.GCHeapSummary", "jdk.GarbageCollection", "jdk.ObjectAllocationSample");
        // CPU / threads
        assertThat(byName).containsKeys("jdk.CPULoad", "jdk.JavaThreadStatistics", "jdk.ThreadCPULoad");
        // Locks / safepoints
        assertThat(byName).containsKeys("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.SafepointBegin");
        // JIT / IO / class
        assertThat(byName)
                .containsKeys("jdk.Compilation", "jdk.ClassLoadingStatistics", "jdk.FileRead", "jdk.SocketRead");
    }

    @Test
    void dottedNestedPathResolvesOnRealEvent() {
        RecordedEvent heapSummary = first("jdk.GCHeapSummary");
        RecordedEventView view = new RecordedEventView(heapSummary);

        assertThat(view.hasField("heapSpace.committedSize")).isTrue();
        assertThat(view.getLong("heapSpace.committedSize")).isGreaterThan(0);
    }

    @Test
    void ticksTimespanIsConvertedViaGetDurationNotRawLong() {
        RecordedEvent gc = first("jdk.GarbageCollection");
        RecordedEventView view = new RecordedEventView(gc);

        long rawTicks = view.getLong("duration");
        Duration duration = view.getDuration("duration");

        assertThat(rawTicks).isPositive();
        assertThat(duration).isNotNull();
        assertThat(duration.toNanos()).isPositive();
        // Sane wall-clock magnitude for a GC pause on this tiny fixture workload.
        assertThat(duration).isLessThan(Duration.ofSeconds(5));

        // "duration" is @Timespan("TICKS"), not milliseconds. Naively assuming the raw long is
        // already in milliseconds - a plausible mistake for what looks like an ordinary numeric
        // field - is off by orders of magnitude from the real, correctly-converted duration.
        // This is what the interface javadoc means by "getLong returns raw ticks, which is
        // meaningless as a wall-clock value".
        Duration misinterpretedAsMillis = Duration.ofMillis(rawTicks);
        assertThat(misinterpretedAsMillis).isNotEqualTo(duration);
        assertThat(misinterpretedAsMillis).isGreaterThan(duration.multipliedBy(1000));
    }

    @Test
    void getClassNameResolvesMonitorClass() {
        RecordedEvent enter = first("jdk.JavaMonitorEnter");
        RecordedEventView view = new RecordedEventView(enter);

        assertThat(enter.getClass("monitorClass")).isNotNull();
        assertThat(view.getClassName("monitorClass")).isNotNull().isNotBlank();
    }

    @Test
    void getThreadNameResolvesEventThreadJavaName() {
        RecordedEvent enter = first("jdk.JavaMonitorEnter");
        RecordedEventView view = new RecordedEventView(enter);

        assertThat(view.getThreadName("eventThread")).isNotNull().isNotBlank();
    }

    @Test
    void getThreadNameFallsBackToOsNameWhenJavaNameIsNull() {
        // jdk.SafepointBegin fires on the VM Thread, which has no Java name - only an OS name
        // ("VM Thread"). This is the real-world instance of "a field being present but null":
        // the eventThread field itself is present and non-null, but its javaName is null.
        RecordedEvent safepoint = first("jdk.SafepointBegin");
        assertThat(safepoint.getThread("eventThread").getJavaName())
                .as("expected a VM-internal thread with no Java name for this test to be meaningful")
                .isNull();

        RecordedEventView view = new RecordedEventView(safepoint);
        assertThat(view.getThreadName("eventThread")).isEqualTo("VM Thread");
    }

    @Test
    void missingFieldsNeverThrowAndReturnDocumentedEmptyValues() {
        RecordedEvent gc = first("jdk.GarbageCollection");
        RecordedEventView view = new RecordedEventView(gc);
        String bogus = "totallyBogusFieldThatDoesNotExist";

        assertThat(view.hasField(bogus)).isFalse();
        assertThat(view.getString(bogus)).isNull();
        assertThat(view.getLong(bogus)).isZero();
        assertThat(view.getDouble(bogus)).isZero();
        assertThat(view.getBoolean(bogus)).isFalse();
        assertThat(view.getDuration(bogus)).isNull();
        assertThat(view.getClassName(bogus)).isNull();
        assertThat(view.getThreadName(bogus)).isNull();

        // A missing nested path underneath a struct field that *does* exist.
        RecordedEvent heapSummary = first("jdk.GCHeapSummary");
        RecordedEventView heapView = new RecordedEventView(heapSummary);
        assertThat(heapView.hasField("heapSpace.bogusNestedField")).isFalse();
        assertThat(heapView.getLong("heapSpace.bogusNestedField")).isZero();

        // A field that is real elsewhere but wrong-typed here (getClass on a non-class field)
        // must degrade the same way rather than propagating RecordedEvent's IllegalArgumentException.
        assertThat(heapView.getClassName("heapUsed")).isNull();
        assertThat(heapView.getDuration("heapUsed")).isNull();
    }

    @Test
    void presentButNullFieldNeverThrowsAcrossAllAccessors() {
        RecordedEvent safepoint = first("jdk.SafepointBegin");
        RecordedEventView view = new RecordedEventView(safepoint);

        // eventThread is present (hasField true) but its javaName is null; every accessor that
        // is not getThreadName must still degrade cleanly rather than throw on this field.
        assertThat(view.hasField("eventThread")).isTrue();
        assertThat(view.getString("eventThread")).isNull();
        assertThat(view.getLong("eventThread")).isZero();
        assertThat(view.getClassName("eventThread")).isNull();
    }

    @Test
    void eventNameAndStartTimeAreAlwaysAvailable() {
        RecordedEvent gc = first("jdk.GarbageCollection");
        RecordedEventView view = new RecordedEventView(gc);

        assertThat(view.eventName()).isEqualTo("jdk.GarbageCollection");
        assertThat(view.startTime()).isEqualTo(gc.getStartTime());
    }

    @Test
    void numericWideningWorksAcrossFloatAndLongFields() {
        // jvmUser on jdk.CPULoad is a float; getLong must still widen it rather than throwing
        // the IllegalArgumentException RecordedEvent.getLong() raises on that conversion.
        RecordedEvent cpuLoad = first("jdk.CPULoad");
        RecordedEventView view = new RecordedEventView(cpuLoad);

        assertThat(view.getDouble("jvmUser")).isBetween(0.0, 1.0);
        assertThat(view.getLong("jvmUser")).isGreaterThanOrEqualTo(0L);

        // heapUsed on jdk.GCHeapSummary is a long; getDouble must widen it too.
        RecordedEvent heapSummary = first("jdk.GCHeapSummary");
        RecordedEventView heapView = new RecordedEventView(heapSummary);
        assertThat(heapView.getDouble("heapUsed")).isEqualTo((double) heapView.getLong("heapUsed"));
    }

    @Test
    void mapEventViewAndRecordedEventViewAgreeForTheSameLogicalEvent() {
        RecordedEvent gc = first("jdk.GarbageCollection");
        RecordedEventView real = new RecordedEventView(gc);

        MapEventView fake = MapEventView.builder("jdk.GarbageCollection")
                .string("name", real.getString("name"))
                .string("cause", real.getString("cause"))
                .duration("duration", real.getDuration("duration"))
                .number("sumOfPauses", real.getLong("sumOfPauses"))
                .build();

        assertThat(fake.eventName()).isEqualTo(real.eventName());
        assertThat(fake.getString("name")).isEqualTo(real.getString("name"));
        assertThat(fake.getString("cause")).isEqualTo(real.getString("cause"));
        assertThat(fake.getDuration("duration")).isEqualTo(real.getDuration("duration"));
        assertThat(fake.getLong("sumOfPauses")).isEqualTo(real.getLong("sumOfPauses"));

        // Missing-field behaviour agrees too.
        assertThat(fake.hasField("nope")).isEqualTo(real.hasField("nope"));
        assertThat(fake.getString("nope")).isEqualTo(real.getString("nope"));
        assertThat(fake.getLong("nope")).isEqualTo(real.getLong("nope"));
        assertThat(fake.getDuration("nope")).isEqualTo(real.getDuration("nope"));
    }

    @Test
    void mapEventViewAgreesOnThreadNameFallbackForNullJavaName() {
        RecordedEvent safepoint = first("jdk.SafepointBegin");
        RecordedEventView real = new RecordedEventView(safepoint);

        MapEventView fake =
                MapEventView.builder("jdk.SafepointBegin").thread("eventThread", null, "VM Thread").build();

        assertThat(fake.getThreadName("eventThread")).isEqualTo(real.getThreadName("eventThread"));
    }

    @Test
    void mapEventViewAgreesOnClassNameResolution() {
        RecordedEvent enter = first("jdk.JavaMonitorEnter");
        RecordedEventView real = new RecordedEventView(enter);

        MapEventView fake = MapEventView.builder("jdk.JavaMonitorEnter")
                .className("monitorClass", real.getClassName("monitorClass"))
                .build();

        assertThat(fake.getClassName("monitorClass")).isEqualTo(real.getClassName("monitorClass"));
    }

    @Test
    void mapEventViewSupportsDottedPathsAndPresentButNullFields() {
        MapEventView fake = MapEventView.builder("jdk.GCHeapSummary")
                .number("heapSpace.committedSize", 123_456L)
                .nullField("previousOwner")
                .build();

        assertThat(fake.hasField("heapSpace.committedSize")).isTrue();
        assertThat(fake.getLong("heapSpace.committedSize")).isEqualTo(123_456L);

        // Present but null: hasField is true, but every accessor degrades to its empty value.
        assertThat(fake.hasField("previousOwner")).isTrue();
        assertThat(fake.getString("previousOwner")).isNull();
        assertThat(fake.getLong("previousOwner")).isZero();
        assertThat(fake.getThreadName("previousOwner")).isNull();

        assertThat(fake.hasField("neverSet")).isFalse();
        assertThat(fake.getLong("neverSet")).isZero();
        assertThat(fake.getString("neverSet")).isNull();
    }
}
