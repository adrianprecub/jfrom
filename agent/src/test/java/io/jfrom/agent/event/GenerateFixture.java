package io.jfrom.agent.event;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import jdk.jfr.Recording;

/**
 * Records a small, real {@code .jfr} fixture covering all four event families the mapping
 * engine targets: memory/GC, CPU/threads, locks/safepoints, and JIT/IO/class. Dumps it to
 * {@code agent/src/test/resources/fixtures/sample.jfr}.
 *
 * <p>The fixture is committed to the repo (see the {@code !agent/src/test/resources/fixtures/*.jfr}
 * exception carved into {@code .gitignore}) so replay tests are fast and deterministic - no
 * agent needs to record a fresh one on every run. This class exists purely to make the fixture
 * reproducible later; it is deliberately named so Surefire's default test-class patterns
 * ({@code *Test}, {@code Test*}, {@code *Tests}, {@code *TestCase}) do not pick it up and rerun
 * it as part of {@code mvn verify}.
 *
 * <p><b>To regenerate the fixture</b> (e.g. after a JDK upgrade changes the verified event
 * schema), run this class's {@code main} method with the test classes on the classpath, from
 * the repository root:
 *
 * <pre>{@code
 * mvn -q -pl agent test-compile
 * java -cp agent/target/test-classes:agent/target/classes \
 *     io.jfrom.agent.event.GenerateFixture
 * }</pre>
 *
 * <p>Or, equivalently, from inside {@code agent/}:
 *
 * <pre>{@code
 * mvn -q test-compile
 * java -cp target/test-classes:target/classes io.jfrom.agent.event.GenerateFixture
 * }</pre>
 *
 * <p>Both invocations resolve the output path relative to the current working directory and
 * overwrite the checked-in fixture in place; diff it before committing.
 */
public final class GenerateFixture {

    private static final Object LOCK = new Object();

    private GenerateFixture() {}

    public static void main(String[] args) throws Exception {
        Path output = resolveOutputPath(args);
        Files.createDirectories(output.getParent());

        try (Recording recording = new Recording()) {
            configure(recording);
            recording.start();

            driveActivity();

            recording.stop();
            recording.dump(output);
        }

        System.out.println("Wrote fixture: " + output.toAbsolutePath() + " (" + Files.size(output) + " bytes)");
    }

    private static Path resolveOutputPath(String[] args) {
        if (args.length > 0) {
            return Path.of(args[0]);
        }
        Path fromRepoRoot = Path.of("agent/src/test/resources/fixtures/sample.jfr");
        if (Files.isDirectory(Path.of("agent/src/test/java"))) {
            return fromRepoRoot;
        }
        return Path.of("src/test/resources/fixtures/sample.jfr");
    }

    /**
     * Enables exactly the events named in the design doc's "Verified JFR ground truth" table,
     * with stack traces off (they are out of scope and inflate file size) and thresholds/periods
     * tuned so a few seconds of driven activity is enough to guarantee at least one sample of
     * each.
     */
    private static void configure(Recording recording) {
        Duration noThreshold = Duration.ZERO;
        Duration fastPeriod = Duration.ofMillis(200);

        // Memory / GC
        recording.enable("jdk.GCHeapSummary").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.GarbageCollection").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.GCPhasePause").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.MetaspaceSummary").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.ObjectAllocationSample").withoutStackTrace();

        // CPU / threads
        recording.enable("jdk.CPULoad").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.JavaThreadStatistics").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.ThreadCPULoad").withPeriod(fastPeriod).withoutStackTrace();

        // Locks / safepoints
        recording.enable("jdk.JavaMonitorEnter").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.JavaMonitorWait").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.ThreadPark").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.SafepointBegin").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.ExecuteVMOperation").withThreshold(noThreshold).withoutStackTrace();

        // JIT / IO / class
        recording.enable("jdk.Compilation").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.CompilerStatistics").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.ClassLoadingStatistics").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.ExceptionStatistics").withPeriod(fastPeriod).withoutStackTrace();
        recording.enable("jdk.SocketRead").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.SocketWrite").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.FileRead").withThreshold(noThreshold).withoutStackTrace();
        recording.enable("jdk.FileWrite").withThreshold(noThreshold).withoutStackTrace();
    }

    private static void driveActivity() throws Exception {
        allocateAndCollect();
        burnCpuAndContend();
        churnClassesAndCompile();
        doFileAndSocketIo();
        // Give periodic events (CPULoad, JavaThreadStatistics, ...) another tick, and let the
        // GC/JIT/allocation-sample events triggered above flush to the recording.
        Thread.sleep(1500);
    }

    /** Provokes {@code jdk.GCHeapSummary}, {@code jdk.GarbageCollection}, {@code jdk.GCPhasePause}. */
    private static void allocateAndCollect() {
        List<byte[]> garbage = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            garbage.add(new byte[64 * 1024]);
            if (i % 50 == 0) {
                garbage.clear();
            }
        }
        garbage.clear();
        System.gc();
        System.gc();
    }

    /**
     * Provokes {@code jdk.JavaMonitorEnter} (several threads contending on one monitor),
     * {@code jdk.JavaMonitorWait}, {@code jdk.ThreadPark}, and (via CPU load) {@code jdk.CPULoad}
     * / {@code jdk.ThreadCPULoad}.
     */
    private static void burnCpuAndContend() throws InterruptedException {
        int threadCount = 4;
        CountDownLatch done = new CountDownLatch(threadCount);
        Runnable contend = () -> {
            try {
                for (int i = 0; i < 20; i++) {
                    synchronized (LOCK) {
                        // Hold the lock briefly so the other threads queue up behind it and
                        // jdk.JavaMonitorEnter actually fires.
                        busyLoop(200_000);
                    }
                }
            } finally {
                done.countDown();
            }
        };
        for (int i = 0; i < threadCount; i++) {
            new Thread(contend, "fixture-contender-" + i).start();
        }
        done.await();

        Thread burner = new Thread(() -> busyLoop(20_000_000), "fixture-cpu-burner");
        burner.start();
        burner.join();

        synchronized (LOCK) {
            LOCK.wait(1);
        }

        Thread parker = new Thread(
                () -> java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(5).toNanos()),
                "fixture-parker");
        parker.start();
        parker.join();
    }

    private static void busyLoop(long iterations) {
        double acc = 0;
        for (long i = 0; i < iterations; i++) {
            acc += Math.sqrt(i);
        }
        if (acc < 0) {
            // Never true; keeps the JIT from proving the loop has no observable effect and
            // eliding it entirely.
            throw new AssertionError(acc);
        }
    }

    /** Provokes {@code jdk.ClassLoadingStatistics}, {@code jdk.Compilation}, {@code jdk.CompilerStatistics}. */
    private static void churnClassesAndCompile() {
        for (int i = 0; i < 50_000; i++) {
            hotMethod(i);
        }
        try {
            Class.forName("java.util.concurrent.ConcurrentSkipListMap");
            Class.forName("java.util.concurrent.CopyOnWriteArrayList");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int hotMethod(int seed) {
        int x = seed;
        for (int i = 0; i < 32; i++) {
            x = Integer.rotateLeft(x * 31 + i, 3);
        }
        return x;
    }

    /** Provokes {@code jdk.FileRead}/{@code jdk.FileWrite} and {@code jdk.SocketRead}/{@code jdk.SocketWrite}. */
    private static void doFileAndSocketIo() throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("jfrom-fixture-", ".tmp");
        try {
            byte[] payload = new byte[8192];
            Files.write(tmp, payload);
            byte[] readBack = Files.readAllBytes(tmp);
            if (readBack.length != payload.length) {
                throw new IllegalStateException("unexpected read size");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(
                    () -> {
                        try (Socket accepted = server.accept()) {
                            accepted.getInputStream().read(new byte[64]);
                            accepted.getOutputStream().write("pong".getBytes());
                        } catch (IOException ignored) {
                            // Best-effort; the fixture only needs one SocketRead/SocketWrite pair.
                        }
                    },
                    "fixture-socket-server");
            serverThread.start();

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.getOutputStream().write("ping".getBytes());
                client.getInputStream().read(new byte[64]);
            }
            serverThread.join(Duration.ofSeconds(2).toMillis());
        }
    }
}
