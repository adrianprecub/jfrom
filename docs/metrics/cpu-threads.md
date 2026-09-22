# CPU & Threads metrics (T8)

Source pack: `agent/src/main/resources/mappings/cpu-threads.yaml`.
Covers `jdk.CPULoad`, `jdk.JavaThreadStatistics`, `jdk.ThreadCPULoad`.

Namespace: every metric below starts with `jfr_cpu_` or `jfr_thread_` (this pack does not use
`jfr_threads_`, though it is a reserved alternative prefix for this family — see "Departures"
below).

## Metrics

| Metric | Type | Unit | Labels | Source event | Meaning |
|---|---|---|---|---|---|
| `jfr_cpu_jvm_user_ratio` | gauge | ratio (0..1) | none | `jdk.CPULoad` | Fraction of available CPU the JVM itself consumed in user-mode code since the last sample. **On macOS/aarch64 this reads `0.0` — see the caveat below.** |
| `jfr_cpu_jvm_system_ratio` | gauge | ratio (0..1) | none | `jdk.CPULoad` | Fraction of available CPU the JVM consumed in kernel/system mode. **Also `0.0` on macOS/aarch64.** |
| `jfr_cpu_machine_total_ratio` | gauge | ratio (0..1) | none | `jdk.CPULoad` | Fraction of total machine CPU in use, across all processes, not just this JVM. Populates correctly on every platform, including the macOS/aarch64 dev box where the two metrics above read zero. **Lead dashboard panels with this series.** |
| `jfr_threads_active` | gauge | count | none | `jdk.JavaThreadStatistics` | Number of live JVM threads right now (daemon + non-daemon). |
| `jfr_threads_daemon` | gauge | count | none | `jdk.JavaThreadStatistics` | Number of live daemon threads right now. |
| `jfr_threads_peak` | gauge | count | none | `jdk.JavaThreadStatistics` | Highest live thread count observed since JVM start. |
| `jfr_thread_accumulated_total` | counter_absolute | count | none | `jdk.JavaThreadStatistics` | Cumulative number of threads ever started since JVM start. The JFR field (`accumulatedCount`) is already a running total from the JVM, so this is *set* per sample, not added — see "Traps" below. |
| `jfr_thread_cpu_user_ratio` | gauge | ratio (0..1) | `thread` (thread name; capped, see below) | `jdk.ThreadCPULoad` | Fraction of one CPU a single thread consumed in user mode. |
| `jfr_thread_cpu_system_ratio` | gauge | ratio (0..1) | `thread` (thread name; capped, see below) | `jdk.ThreadCPULoad` | Fraction of one CPU a single thread consumed in kernel/system mode. |

All ratio values are plain 0..1 fractions, matching what `jdk.CPULoad`/`jdk.ThreadCPULoad`
already report — none of them are multiplied by 100. Configure the corresponding Grafana panels
with a `percentunit` (0..1) unit, not `percent` (0..100).

## Traps this pack had to get right

1. **Fractions, not percentages.** `jvmUser`, `jvmSystem`, `machineTotal` (and, by the same
   token, `ThreadCPULoad.user`/`.system`) are JDK `float` fields already expressed as a 0..1
   fraction. The pack maps them with `kind: number` (plain `getDouble`) and applies no scaling.
   Multiplying by 100 here is the single most tempting bug in this pack and would silently push
   every CPU panel into the 0..100 range while Grafana's `percentunit` formatter (which expects
   0..1) renders it as 0–10000%.

2. **macOS/aarch64 reports `jvmUser`/`jvmSystem` as `0.0`.** Confirmed by the design doc's
   verified-ground-truth addendum #6 and reproduced by this pack's own fixture-replay test
   (`CpuThreadsPackTest`), which runs on exactly that platform: `jfr_cpu_machine_total_ratio` is
   asserted strictly between 0 and 1, while `jfr_cpu_jvm_user_ratio`/`jfr_cpu_jvm_system_ratio`
   are only asserted non-negative, deliberately **not** `> 0`. All three metrics are still
   mapped and exposed unconditionally, because inside the Linux container the JVM-specific
   figures are expected to populate normally — this is a host-OS quirk of the JFR/OS interaction
   on Apple silicon, not a bug in the mapping. **If a dashboard panel built on `jfr_cpu_jvm_user_ratio`
   or `jfr_cpu_jvm_system_ratio` looks flat at zero on a local macOS run, that is expected — check
   `jfr_cpu_machine_total_ratio` instead, or run the stack in the docker-compose Linux
   container.**

3. **`accumulatedCount` is a cumulative JVM total, not a per-sample delta.** It is mapped as
   `counter_absolute` (the registry sets it, ignoring any lower value it's given) rather than
   `counter` (which would add it every sample). Treating it as a plain counter would add the
   JVM's already-cumulative total to itself on every periodic tick, producing a `rate()` curve
   that looks like a real thread-creation rate but is actually inflated by orders of magnitude.
   `activeCount`, `daemonCount` and `peakCount` are instantaneous snapshots and are correctly
   gauges.

4. **`jdk.ThreadCPULoad` is per-thread and unbounded in cardinality.** Every live thread gets
   its own label value, and thread pools/short-lived worker threads mean the set of distinct
   thread names only grows over a JVM's lifetime. Both `jfr_thread_cpu_user_ratio` and
   `jfr_thread_cpu_system_ratio` set `maxCardinality: 20`: comfortably enough for the sample
   app's own worker/driver threads plus a handful of JVM-internal threads (GC, compiler,
   attach listener, etc.) in normal POC operation, while guaranteeing the registry never grows
   an unbounded number of series just from thread churn. Once the cap is hit, further distinct
   thread names collapse into the shared `thread="__other__"` series (and increment
   `jfrom_cardinality_dropped_total{metric="jfr_thread_cpu_user_ratio"}` /
   `...system_ratio`) instead of being dropped silently.

## Departures from the suggested coverage

- **`jdk.ThreadStart`/`jdk.ThreadEnd` (thread churn) are intentionally not mapped.** The
  committed replay fixture (`agent/src/test/resources/fixtures/sample.jfr`, produced by the
  frozen `GenerateFixture` utility) never enables those two events, so a rule for them would be
  untestable against the fixture this pack's test suite replays — it would either sit dead in
  the pack with zero coverage, or require changing a frozen file. Thread churn is still visible
  indirectly through `jfr_threads_active`/`jfr_threads_daemon` moving over time, and
  through `jfr_thread_accumulated_total`'s slope, which is sufficient for a POC dashboard. If a
  future task regenerates the fixture with `jdk.ThreadStart`/`jdk.ThreadEnd` enabled, adding
  `jfr_threads_started_total`/`jfr_threads_ended_total` as bare counters (`type: counter`, no
  `value`, i.e. an event rate) is a natural follow-up and would use the reserved `jfr_threads_`
  prefix.
- **Per-thread CPU (`jdk.ThreadCPULoad`) is included, deliberately capped.** It is genuinely
  useful in a live POC for spotting one runaway thread, which a purely aggregate CPU view can't
  show. It is included with the cardinality cap described above rather than left out entirely,
  on the judgement that "capped and labelled" is safer and more useful than "omitted" for a
  demo whose whole point is showing JFR detail Micrometer can't.

## Test coverage

`agent/src/test/java/io/jfrom/agent/mappings/CpuThreadsPackTest.java`:

1. Loads the pack from the classpath and asserts the 3 rules, 9 metrics, their types
   (including the `counter_absolute` trap and the per-thread `maxCardinality: 20` trap) and
   label sets.
2. Replays the committed fixture (`CPULoad(4)`, `JavaThreadStatistics(7)`, `ThreadCPULoad(12)`)
   through `RecordedEventView` + `RuleEvaluator` into a real `DefaultMetricRegistry`, then
   asserts on the Prometheus exposition text: `machineTotal` strictly between 0 and 1 (catches a
   stray `×100`), `jvmUser`/`jvmSystem` present and non-negative but **not** asserted `> 0`
   (macOS caveat above), active/daemon/peak thread counts sane and mutually consistent, the
   accumulated total at least as large as the peak count, and every per-thread CPU sample a
   plausible 0..1 fraction.
3. Asserts every metric name in the pack starts with `jfr_cpu_`, `jfr_thread_` or
   `jfr_threads_`, guarding the cross-pack duplicate-metric-name contract enforced by
   `MappingConfig`.
