package io.jfrom.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.jfrom.agent.config.LabelKind;
import io.jfrom.agent.config.LabelSpec;
import io.jfrom.agent.event.MapEventView;
import io.jfrom.agent.metrics.Labels;
import java.util.List;
import org.junit.jupiter.api.Test;

class LabelExtractorTest {

    @Test
    void stringKind() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").string("cause", "System.gc()").build();
        LabelSpec spec = new LabelSpec("cause", "cause", LabelKind.STRING);

        assertThat(LabelExtractor.extract(spec, view)).isEqualTo("System.gc()");
    }

    @Test
    void classKind() {
        MapEventView view = MapEventView.builder("jdk.JavaMonitorEnter")
                .className("monitorClass", "java.lang.Object")
                .build();
        LabelSpec spec = new LabelSpec("monitor", "monitorClass", LabelKind.CLASS);

        assertThat(LabelExtractor.extract(spec, view)).isEqualTo("java.lang.Object");
    }

    @Test
    void threadKind() {
        MapEventView view = MapEventView.builder("jdk.JavaMonitorEnter")
                .threadName("eventThread", "pool-1-thread-1")
                .build();
        LabelSpec spec = new LabelSpec("thread", "eventThread", LabelKind.THREAD);

        assertThat(LabelExtractor.extract(spec, view)).isEqualTo("pool-1-thread-1");
    }

    @Test
    void threadKindFallsBackToOsNameWhenJavaNameNull() {
        MapEventView view = MapEventView.builder("jdk.SafepointBegin")
                .thread("eventThread", null, "VM Thread")
                .build();
        LabelSpec spec = new LabelSpec("thread", "eventThread", LabelKind.THREAD);

        assertThat(LabelExtractor.extract(spec, view)).isEqualTo("VM Thread");
    }

    @Test
    void booleanKind() {
        MapEventView view = MapEventView.builder("jdk.JavaMonitorWait").bool("timedOut", true).build();
        LabelSpec spec = new LabelSpec("timedOut", "timedOut", LabelKind.BOOLEAN);

        assertThat(LabelExtractor.extract(spec, view)).isEqualTo("true");
    }

    @Test
    void intKind() {
        MapEventView view = MapEventView.builder("jdk.SafepointBegin").number("totalThreadCount", 7).build();
        LabelSpec spec = new LabelSpec("threads", "totalThreadCount", LabelKind.INT);

        assertThat(LabelExtractor.extract(spec, view)).isEqualTo("7");
    }

    @Test
    void missingFieldRendersEmptyStringRatherThanDroppingTheSample() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").build();
        LabelSpec spec = new LabelSpec("cause", "cause", LabelKind.STRING);

        assertThat(LabelExtractor.extract(spec, view)).isEmpty();
    }

    @Test
    void presentButNullFieldRendersEmptyString() {
        MapEventView view = MapEventView.builder("jdk.JavaMonitorEnter").nullField("previousOwner").build();
        LabelSpec spec = new LabelSpec("owner", "previousOwner", LabelKind.THREAD);

        assertThat(LabelExtractor.extract(spec, view)).isEmpty();
    }

    @Test
    void buildLabelsPreservesOrderAndUsesEmptyStringForMissing() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").string("name", "G1New").build();
        List<LabelSpec> specs = List.of(
                new LabelSpec("gc", "name", LabelKind.STRING),
                new LabelSpec("cause", "cause", LabelKind.STRING));

        Labels labels = LabelExtractor.buildLabels(specs, view);

        assertThat(labels.asMap().keySet()).containsExactly("gc", "cause");
        assertThat(labels.asMap()).containsEntry("gc", "G1New").containsEntry("cause", "");
    }

    @Test
    void buildLabelsWithNoSpecsIsEmpty() {
        MapEventView view = MapEventView.builder("jdk.GarbageCollection").build();
        assertThat(LabelExtractor.buildLabels(List.of(), view).isEmpty()).isTrue();
    }
}
