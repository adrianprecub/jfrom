# jfr2grafana — live JFR → Grafana time-series POC

## Context

We want to see JVM internals as they happen. JMX/Micrometer expose a shallow,
fixed set of numbers; Java Flight Recorder sees everything the VM does — GC
phases, safepoints, lock contention, JIT, allocation — at very low overhead.
Since JDK 14 the `jdk.jfr.consumer.RecordingStream` API delivers those events
to an in-process callback within ~1s, with no `.jfr` file on disk. Nothing
off-the-shelf turns that stream into Prometheus metrics in a way you can extend
without forking.

**Deliverable:** `jfr2grafana-agent.jar` — a `-javaagent` that opens a
RecordingStream, maps events to metrics via a declarative YAML config, and
serves Prometheus text on `/metrics`. Plus a docker-compose POC where
`docker compose up` yields live-updating Grafana dashboards within seconds.

**Out of scope (decided):** no log/event-table output (Loki), no flame graphs
(Pyroscope), no stack-trace handling of any kind, no remote/JMX streaming, no
push/OTLP. Time-series only.

## Decisions

| Area | Choice |
|---|---|
| Ingestion | Custom `-javaagent`, in-process `RecordingStream` |
| Mapping | Config-driven engine + YAML rules (no Java per event) |
| Coverage | 4 families: Memory/GC, CPU/Threads, Locks/Safepoints, JIT/IO/Class |
| Toolchain | JDK 25 (verified 25.0.1 Temurin local) + Maven multi-module |
| Deps | JDK-only + SnakeYAML, relocated via maven-shade-plugin |
| Exposition | Hand-written Prometheus text format (~20 lines) |
| Sample app | Spring Boot with deliberately-misbehaving endpoints + internal driver |
| Backend | VictoriaMetrics (native scrape, no separate Prometheus container) |
| Testing | Layered: EventView unit tests + `.jfr` fixture replay + e2e smoke |
| Dashboards | One curated, auto-provisioned dashboard |

## Verified JFR ground truth

Confirmed by `jfr metadata` and a live `RecordingStream` probe on JDK 25.0.1.
**Worker agents must treat this table as authoritative and not guess field names.**

| Event | Fields (verified) | Notes |
|---|---|---|
| `jdk.GCHeapSummary` | `when`, `heapUsed`, `heapSpace.*` | `when` is **`"Before GC"` / `"After GC"`** (spaces, not underscores). `heapSpace` is a `VirtualSpace` struct: `committedSize`, `reservedSize`, `start`, `committedEnd`. |
| `jdk.GarbageCollection` | `duration`, `name`, `cause`, `sumOfPauses`, `longestPause` | `name` is the collector (`"G1Full"`, `"G1New"`), `cause` e.g. `"System.gc()"`. No `collector` field. |
| `jdk.GCPhasePause` | `duration`, `name`, `gcId` | |
| `jdk.MetaspaceSummary` | `when`, `gcThreshold`, `metaspace.*`, `dataSpace.*`, `classSpace.*` | nested `MetaspaceSizes` structs |
| `jdk.ObjectAllocationSample` | `objectClass`, `weight` | `weight` = estimated bytes; sum it for alloc rate. Sampled, not per-allocation. |
| `jdk.CPULoad` | `jvmUser`, `jvmSystem`, `machineTotal` | `float`, already a 0..1 fraction |
| `jdk.JavaThreadStatistics` | `activeCount`, `daemonCount`, `peakCount`, `accumulatedCount` | `accumulatedCount` is cumulative |
| `jdk.ThreadCPULoad` | `user`, `system`, `eventThread` | per-thread; high cardinality — see guard |
| `jdk.JavaMonitorEnter` | `duration`, `monitorClass`, `previousOwner`, `address` | `monitorClass` is `RecordedClass` |
| `jdk.JavaMonitorWait` | `duration`, `monitorClass`, `timedOut`, `timeout` | |
| `jdk.ThreadPark` | `duration`, `parkedClass`, `timeout`, `until` | |
| `jdk.SafepointBegin` | `duration`, `safepointId`, `totalThreadCount` | |
| `jdk.ExecuteVMOperation` | `duration`, `operation`, `safepoint`, `blocking` | `operation` is a good low-cardinality label |
| `jdk.Compilation` | `duration`, `compiler`, `compileLevel`, `succeded`, `codeSize`, `isOsr` | note the JDK's own typo: **`succeded`** |
| `jdk.CompilerStatistics` | `compileCount`, `bailoutCount`, `standardBytesCompiled`, `totalTimeSpent`, … | all cumulative totals |
| `jdk.ClassLoadingStatistics` | `loadedClassCount`, `unloadedClassCount` | cumulative |
| `jdk.ExceptionStatistics` | `throwables` | cumulative |
| `jdk.SocketRead` / `SocketWrite` | `duration`, `host`, `address`, `port`, `bytesRead`/`bytesWritten` | |
| `jdk.FileRead` / `FileWrite` | `duration`, `path`, `bytesRead`/`bytesWritten`, `endOfFile` | `path` is high cardinality — do not label by default |

Three behaviours proven by live probe, which the engine design depends on:

1. **Dotted nested paths resolve**: `e.getLong("heapSpace.committedSize")` works.
2. **TICKS timespans need `getDuration`**: `duration` and `sumOfPauses` are
   `@Timespan("TICKS")`. `e.getLong("duration")` returns raw ticks (wrong);
   `e.getDuration("duration").toNanos()` is correct.
3. Events flush to the stream within ~1–3s of occurring.

## Architecture

```
java -javaagent:jfr2grafana-agent.jar=port=9404 -jar sample-app.jar

  ┌─ sample-app JVM ────────────────────────┐
  │  RecordingStream (1s flush, no stacks)  │
  │       └─> RecordedEventView (adapter)   │
  │             └─> RuleEvaluator (YAML)    │
  │                   └─> MetricRegistry    │
  │                         └─ HTTP :9404/metrics
  └──────────────────────────────┬──────────┘
                    scrape 1s    ▼
              [VictoriaMetrics :8428] ──> [Grafana :3000]
```

## Module layout

```
jfr2grafana/
├── pom.xml                              parent, JDK 25, module list
├── agent/
│   ├── pom.xml                          shade-plugin, relocates SnakeYAML
│   └── src/main/java/io/jfr2grafana/agent/
│       ├── Agent.java                   premain: parse args, load config, start engine + server
│       ├── AgentOptions.java            parse `port=,config=,families=,logLevel=`
│       ├── config/  MappingConfig, EventRule, MetricSpec, ValueSpec, LabelSpec, MappingLoader
│       ├── event/   EventView (interface), RecordedEventView (RecordedEvent adapter)
│       ├── engine/  MappingEngine, RuleEvaluator, ValueExtractor, LabelExtractor
│       ├── metrics/ MetricRegistry, Gauge, Counter, Histogram, PrometheusExposition
│       └── http/    MetricsServer (com.sun.net.httpserver)
│   └── src/main/resources/mappings/
│       memory-gc.yaml, cpu-threads.yaml, locks-safepoints.yaml, jit-io-class.yaml
├── sample-app/                          Spring Boot; /churn /contend /leak /io /cpu + driver
├── docker/
│   ├── docker-compose.yml
│   ├── victoriametrics/scrape.yml
│   └── grafana/provisioning/{datasources,dashboards}/ + dashboards/jvm-jfr.json
└── docs/superpowers/specs/2026-09-21-jfr2grafana-design.md
```

## Engine contract (freeze this first — all parallel work compiles against it)

```yaml
# mappings/*.yaml
rules:
  - event: jdk.GCHeapSummary
    enable: { period: everyChunk, stackTrace: false }   # -> rs.enable().withPeriod()/.withoutStackTrace()
    filter: { when: "After GC" }                        # skip event unless all fields match
    metrics:
      - name: jfr_heap_used_bytes
        type: gauge
        help: "Heap bytes in use after GC"
        value: { field: heapUsed, kind: number }

  - event: jdk.GarbageCollection
    enable: { threshold: 0ms, stackTrace: false }
    metrics:
      - name: jfr_gc_pause_seconds
        type: histogram
        value: { field: duration, kind: duration, unit: seconds }
        labels:
          - { name: gc,    field: name,  kind: string }
          - { name: cause, field: cause, kind: string }
        buckets: [0.0005,0.001,0.0025,0.005,0.01,0.025,0.05,0.1,0.25,0.5,1,2.5,5,10]
```

**Metric types** — the distinction between the two counter modes is the single
most error-prone part; specify it explicitly in every rule:

| `type` | Semantics |
|---|---|
| `gauge` | last value wins per label set |
| `counter` | **add** `value` to the running total per event (omit `value` → add 1, i.e. event rate) |
| `counter_absolute` | the field is *already* a JVM cumulative total → **set** it (`ClassLoadingStatistics.loadedClassCount`, `CompilerStatistics.compileCount`, `ExceptionStatistics.throwables`) |
| `histogram` | observe `value` into `buckets`; emits `_bucket`/`_sum`/`_count` |

**Value kinds:** `number` (long/double/float, incl. dotted nested paths),
`duration` (TICKS → `getDuration().toNanos()`, converted per `unit`).
**Label kinds:** `string`, `class` (`RecordedClass#getName`), `thread`
(`RecordedThread#getJavaName`), `boolean`, `int`.

**Cardinality guard (required, not optional):** `monitorClass`, `objectClass`,
`eventThread`, `path`, `host` can explode a local registry. Every metric gets
`maxCardinality` (default 100); on overflow the label value collapses to
`__other__` and a `jfr2grafana_cardinality_dropped_total` counter increments.

**Concurrency:** RecordingStream callbacks run on one stream thread; the HTTP
handler scrapes from another. `MetricRegistry` uses `ConcurrentHashMap` +
`LongAdder`/`DoubleAdder`; histogram buckets are `LongAdder[]`.

**Naming:** all metrics are prefixed **`jfr_`** — the sample app is Spring Boot
and may also expose Actuator/Micrometer `jvm_*`; the prefix keeps them distinct.

**Self-observability:** the agent exposes `jfr2grafana_events_processed_total{event}`,
`jfr2grafana_events_dropped_total{reason}`, `jfr2grafana_scrape_duration_seconds`.

## Task DAG for parallel agents

Max 4 concurrent (under your limit of 5). Each task lists disjoint files, so
workers in the same wave cannot collide. Review gate between waves.

**Wave 0 — sequential, coordinator only. Blocks everything.**
- **T0 Skeleton & frozen contract.** `git init`; parent + module poms; JDK 25
  toolchain; shade plugin with SnakeYAML relocation and `Premain-Class`
  manifest; `.gitignore`. Write the *complete* interfaces and config model as
  compiling stubs: `EventView`, `MetricRegistry`, `MetricSpec`, `EventRule`,
  `ValueSpec`, `LabelSpec`. Commit the design doc to `docs/superpowers/specs/`.
  *Acceptance:* `mvn -q verify` passes on an empty-but-compiling tree.

**Wave 1 — 4 parallel workers.**
- **T1 `metrics/` + exposition.** Registry, Gauge, Counter (both modes),
  Histogram, `PrometheusExposition`. *Acceptance:* unit tests cover counter-vs-
  counter_absolute, histogram bucket boundaries and `+Inf`, label escaping
  (`\`, `"`, `\n`), `NaN`/`+Inf` formatting, cardinality overflow → `__other__`.
- **T2 `config/`.** YAML model + `MappingLoader` + validation with actionable
  errors (unknown type, missing `buckets` on histogram, duplicate metric name,
  bad `unit`). *Acceptance:* round-trip tests; every malformed-config case
  asserts a specific message.
- **T3 `event/` + fixtures.** `EventView`, `RecordedEventView`, and a
  `GenerateFixtures` test utility that records a real `.jfr` covering all four
  families into `src/test/resources/fixtures/`. *Acceptance:* fixture replayed
  via `RecordingFile.readAllEvents()`; asserts dotted paths (`heapSpace.committedSize`),
  TICKS→Duration, `RecordedClass`/`RecordedThread` label extraction, and
  missing-field behaviour.
- **T4 `sample-app/`.** Spring Boot + Dockerfile. Endpoints `/churn` (allocation
  storm), `/contend` (pool contending on one monitor), `/leak` (retained growth),
  `/io` (socket + file traffic), `/cpu` (hot loop). A background driver cycles
  through them from startup so dashboards are never empty. *Acceptance:* app
  boots, each endpoint responds, driver runs without config.

**Wave 2 — 2 parallel workers.**
- **T5 `engine/` + `Agent` + `http/`.** `ValueExtractor`, `LabelExtractor`,
  `RuleEvaluator`, `MappingEngine` (builds the RecordingStream, applies
  `enable`/`period`/`threshold`/`withoutStackTrace`), `premain`, `MetricsServer`.
  Depends on T1+T2+T3. *Acceptance:* in-process e2e — start engine, force GC,
  assert `/metrics` contains `jfr_gc_pause_seconds_count` > 0.
- **T6 `docker/`.** compose (sample-app, victoriametrics, grafana);
  VictoriaMetrics `-promscrape.config` at 1s interval; Grafana datasource
  provisioning (type `prometheus` → `http://victoriametrics:8428`), anonymous
  admin auth, dashboard provider pointing at the dashboards dir. Depends on T4.

**Wave 3 — 4 parallel workers. Pure YAML + tests, fully disjoint.**
- **T7** `memory-gc.yaml` · **T8** `cpu-threads.yaml` ·
  **T9** `locks-safepoints.yaml` · **T10** `jit-io-class.yaml`.
  Each: author rules for its family from the verified table above, plus a test
  that loads the pack and replays the T3 fixture asserting the expected metric
  names, types and label sets. Each worker also appends its metric inventory to
  `docs/metrics.md` (separate section per family, no conflict).

**Wave 4 — sequential.**
- **T11 Dashboard.** `jvm-jfr.json`, one row per family, 5m window, 5s refresh,
  set as Grafana's home dashboard. Needs final metric names from T7–T10.
- **T12 E2E + README.** Full-stack verification script and docs.

## Verification

```bash
mvn -q verify                                    # all unit + fixture + in-process e2e tests
cd docker && docker compose up -d --build

curl -s localhost:9404/metrics | grep -c '^jfr_' # agent exposing metrics
curl -s localhost:9404/metrics | grep jfr_gc_pause_seconds_count
curl -s 'http://localhost:8428/api/v1/query?query=jfr_heap_used_bytes' | jq .
open http://localhost:3000                       # home dashboard live, no login
```

**POC is done when:** `docker compose up` → Grafana home dashboard shows moving
GC pause, heap, CPU, thread-count, lock-contention and JIT series within 15s of
start, with no manual clicks.

## Risks

- **Spring Boot on JDK 25** — pin a Boot version that supports it; T4 verifies at build time.
- **Event volume** — `JavaMonitorEnter` with a 0ms threshold under `/contend` can
  flood. Mappings set sane thresholds (e.g. 1ms) and always `withoutStackTrace`.
- **Shading correctness** — a leaked unrelocated SnakeYAML breaks target apps.
  T1/T0 acceptance includes `unzip -l` asserting only relocated packages ship.
- **`ObjectAllocationSample` is sampled** — allocation rate is an estimate.
  Label the dashboard panel accordingly rather than implying exactness.

---

## Addendum — findings from T0 (skeleton)

Recorded during implementation of the skeleton; these amend the plan above.

1. **Version metadata from `search.maven.org` is stale.** Its solrsearch index reported
   Spring Boot 3.5.3 and JUnit 5.13.0-M3 as latest. The authoritative source is
   `https://repo1.maven.org/maven2/<group-path>/<artifact>/maven-metadata.xml`.
   Pinned: Spring Boot 4.1.1, JUnit 5.14.4, AssertJ 3.27.7, SnakeYAML 2.7,
   shade 3.6.2, surefire 3.6.0, compiler 3.14.1, exec 3.6.4.
   The `4.0.0-beta` compiler and jar plugins were deliberately skipped.

2. **SnakeYAML ships a multi-release JAR.** maven-shade-plugin relocated
   `org/yaml/snakeyaml/**` but left `META-INF/versions/9/org/yaml/snakeyaml/**` in place.
   Because the agent jar does not declare `Multi-Release: true` those copies are inert, but
   they still ship classes in a namespace the agent promises never to touch. Fixed by
   excluding `META-INF/versions/**`. Guarded permanently by `scripts/verify-shading.sh`,
   bound to the `verify` phase: it fails the build if any shipped class falls outside
   `io/jfr2grafana/`, or if relocation silently stops happening.

3. **`MappingConfig` rejects duplicate metric names at load time.** Two packs declaring the
   same metric would emit conflicting `HELP`/`TYPE` headers for one series, which Prometheus
   rejects at scrape time. Since Wave 3 has four agents authoring packs in parallel, this
   collision is likely, and a load-time failure with both owning event names is far easier to
   diagnose than a scrape error.

4. **`setCounterAbsolute` must ignore decreasing values.** A counter that goes backwards makes
   PromQL `rate()` synthesise a huge spike. Stated in the `MetricRegistry` contract.

## Addendum — findings from Wave 1

5. **`RecordedEvent` accessors throw `IllegalArgumentException`** for a missing field AND for
   a type mismatch, so `hasField` alone is not a sufficient guard. In particular
   `getLong("jvmUser")` throws rather than widening, because `jdk.CPULoad.jvmUser` is a
   `float`. `RecordedEventView.getLong` falls back to `getDouble` so the `EventView`
   contract ("widened to long") still holds. Verified: raw `getDouble` and `getFloat` work
   correctly on float fields.

6. **`jdk.CPULoad.jvmUser` and `jvmSystem` report `0.0` on macOS/aarch64**, while
   `machineTotal` reports real values. A CPU panel built only on `jvmUser` will look broken
   when the stack is run on a Mac host outside a container. Mapping packs should expose all
   three, and the dashboard should lead with `machineTotal`. Inside the Linux container the
   JVM-specific figures are expected to populate — verify at e2e rather than assuming.

7. **TICKS happen to equal nanoseconds on this JDK 25.0.1/macOS-aarch64 combination**, so
   `getLong("duration")` and `getDuration("duration").toNanos()` are numerically identical
   *here*. This is platform-dependent and must not be relied on. The rule stands: always use
   `getDuration` for `@Timespan` fields.

8. **The `.jfr` test fixture is committed** at `agent/src/test/resources/fixtures/sample.jfr`
   (148 KB, 350 events across 20 event types covering all four families), with a `.gitignore`
   negation carved out of the blanket `*.jfr` rule. Regenerate with
   `event/GenerateFixture`. Wave 3 mapping packs should assert against it.

## Addendum — findings from Wave 2

9. **`RecordingStream` has no `setFlushInterval` and no `setDaemon` in JDK 25.** Verified with
   `javap`: the class exposes `onFlush(Runnable)` but no flush setter, and `setDaemon` exists
   only on the internal, non-exported `AbstractEventStream`. `MappingEngine` therefore calls
   the blocking `start()` from a thread it creates and marks daemon itself, rather than
   `startAsync()` which would spawn JFR's own non-daemon thread.
   Measured empirically: the default flush interval is already ~1s, and passing
   `setSettings(Map.of("flush-interval", "1 s"))` produces the same ~1s cadence. Relying on
   the default is correct; no tuning is needed to meet the "live" requirement.

10. **`com.sun.net.httpserver.HttpServer`'s internal HTTP-Dispatcher thread inherits daemon
    status from whichever thread calls `start()`.** Calling it from `premain` (non-daemon)
    leaves the dispatcher non-daemon and **keeps the host JVM alive after its `main` returns** —
    an observability agent silently preventing application shutdown. `MetricsServer` calls
    `server.start()` from a throwaway daemon thread. Verified end-to-end: a host app with the
    agent attached exits ~1s after `main` returns, leaving no process behind.

11. **Agent failure is contained.** Verified live for a missing config file and for an already
    bound port: in both cases the agent logs `jfr2grafana: failed to start: ...` and the host
    application runs to completion with exit code 0.
