# jfrom

**jfrom** — *JFR observability metrics*. Live-stream JVM internals from Java Flight Recorder
(JFR) into Grafana, as Prometheus metrics.

JMX and Micrometer expose a shallow, fixed set of numbers. JFR sees what the VM is actually
doing — GC phases, safepoints, lock contention, JIT compilation, allocation — at very low
overhead. Since JDK 14, `jdk.jfr.consumer.RecordingStream` delivers those events to an
in-process callback about once a second, with no `.jfr` file on disk.

`jfrom` is a Java agent that consumes that stream and serves the result at `/metrics`.
Which events become which metrics is **declarative** — adding a metric is a few lines of YAML,
not a code change.

## Quick start

Requires a JDK 25 toolchain, Maven, and a container runtime with `docker compose`.

```bash
./scripts/up.sh
```

That builds the agent, starts the sample app with the agent attached, plus VictoriaMetrics
(scraping at 1s) and Grafana, and waits until metrics are flowing. Then open:

| | |
|---|---|
| Grafana | http://localhost:3000 — no login, opens straight on the dashboard |
| VictoriaMetrics | http://localhost:8428 |
| sample app | http://localhost:8080 |
| raw agent metrics | http://localhost:9404/metrics |

The sample app drives itself, so the dashboard is never empty. To provoke something specific:

```bash
curl -X POST localhost:8080/load/churn      # allocation storm -> young-gen GC
curl -X POST localhost:8080/load/contend    # lock contention -> monitor blocked panels
curl -X POST localhost:8080/load/cpu        # hot loops -> CPU + JIT
curl -X POST localhost:8080/load/io         # file + socket traffic
curl -X POST localhost:8080/load/leak       # bounded retained growth -> old-gen creep
curl -X POST localhost:8080/load/leak/reset
```

Tear down with `./scripts/down.sh` (add `--volumes` to drop Grafana's state too).

## Using the agent on your own application

```bash
java -javaagent:/path/to/jfrom-agent.jar=port=9404 -jar your-app.jar
```

| Option | Default | Meaning |
|---|---|---|
| `port` | `9404` | port for the `/metrics` endpoint |
| `host` | `0.0.0.0` | bind address (`0.0.0.0` so it is reachable from outside a container) |
| `config` | — | path to an extra YAML mapping pack, added to the bundled ones |
| `families` | all | restrict to named bundled packs |
| `debug` | `false` | verbose logging |

The agent is built to be safe to attach to anything:

- **It cannot break your application.** `premain` catches `Throwable`; a failure is logged and
  the app starts normally. Verified against a missing config file and an already-bound port —
  the host process still exits 0.
- **It cannot collide with your dependencies.** The only runtime dependency is SnakeYAML, and
  it is shaded into `io.jfrom.agent.shaded`. A `-javaagent` loads on the *system*
  classloader, so anything it shipped unrelocated could shadow your own classes.
  `scripts/verify-shading.sh` fails the build if any class escapes `io/jfrom/`.
- **It will not keep your JVM alive.** Both the JFR stream thread and the HTTP server's
  internal dispatcher are daemon threads.

## How mapping works

Each rule maps one JFR event to one or more metrics:

```yaml
rules:
  - event: jdk.GarbageCollection
    enable: { threshold: 0ms, stackTrace: false }
    metrics:
      - name: jfr_gc_pause_seconds
        type: histogram
        help: "GC pause duration"
        value: { field: duration, kind: duration, unit: seconds }
        labels:
          - { name: gc,    field: name,  kind: string }
          - { name: cause, field: cause, kind: string }
        buckets: [0.0001, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]
```

- `type`: `gauge` · `counter` (adds a delta; with no `value`, counts events) ·
  `counter_absolute` (the field is already a cumulative JVM total, so set rather than add) ·
  `histogram`.
- `value.kind`: `number`, or `duration` for any JFR `@Timespan` field.
- `labels[].kind`: `string` · `class` · `thread` · `boolean` · `int`.
- `filter`: all entries must match or the event is skipped.
- `maxCardinality`: caps distinct label sets; beyond it, values collapse to `__other__`.
- Field paths may be dotted to reach into nested JFR structs: `heapSpace.committedSize`.

Bundled packs live in `agent/src/main/resources/mappings/`. Point `config=` at your own file
to add more without rebuilding.

## Metrics

47 metrics across four event families. Full inventory, with units, labels and caveats:

- [Memory & GC](docs/metrics/memory-gc.md)
- [CPU & threads](docs/metrics/cpu-threads.md)
- [Locks & safepoints](docs/metrics/locks-safepoints.md)
- [JIT, class loading, I/O, exceptions](docs/metrics/jit-io-class.md)

The agent also reports on itself: `jfrom_events_processed_total`,
`jfrom_events_dropped_total`, `jfrom_cardinality_dropped_total` and
`jfrom_scrape_duration_seconds` — so you can tell "the JVM is quiet" from
"our mapping is broken".

## Gotchas worth knowing

These cost real debugging time and are baked into the code and tests:

- **A JFR `@Timespan` field read as a number gives you ticks, not time.** It still renders a
  perfectly convincing graph. Always use `kind: duration`. Every pack test asserts durations
  land in a plausible seconds range.
- **`jdk.Compilation` has a field spelled `succeded`** — the JDK's own typo. Spelling it
  correctly silently yields nothing. There is a test that fails if someone "fixes" it.
- **`jdk.CPULoad.jvmUser`/`jvmSystem` read `0.0` on macOS/aarch64.** `machineTotal` works
  everywhere, so the dashboard leads with it.
- **Several JFR fields are cumulative totals, not deltas** (`loadedClassCount`,
  `compileCount`, `throwables`, `accumulatedCount`). They need `counter_absolute`; using
  `counter` produces plausible, wrong rates.
- **Fields like `monitorClass`, `objectClass`, `eventThread` and `path` are unbounded.**
  Labelling by them without a cardinality cap will grow the registry without limit.

## Editing the dashboard

The dashboard lives at `docker/grafana/dashboards/jvm-jfr.json`. After editing it:

```bash
docker compose -f docker/docker-compose.yml restart grafana
```

Grafana 13 serves dashboards from its unified storage and does not reliably re-read a
changed file on the provisioner's poll interval, so a restart is the dependable way to
apply an edit. Two things to keep in mind when hand-editing:

- **Do not add a top-level `"version"` field.** Grafana owns dashboard versioning, and a
  pinned version silently blocks provisioning updates.
- **Every target in a panel needs a unique `refId`** (`A`, `B`, `C`, ...). Duplicates make
  the whole panel error out rather than degrade, and the queries themselves will still look
  perfectly fine when run directly against the datasource.

## Development

```bash
mvn verify                       # 168 tests, plus the shading guard
mvn -q -pl agent package         # just the agent jar
./scripts/verify-shading.sh      # assert nothing ships outside io/jfrom/
```

Tests run against a committed 145 KB JFR recording
(`agent/src/test/resources/fixtures/sample.jfr`, 350 events across 21 event types), so they are
fast and deterministic. Regenerate it with `io.jfrom.agent.event.GenerateFixture`.

Metric output is validated against Prometheus' own linter:

```bash
docker run --rm -i --entrypoint promtool prom/prometheus:latest check metrics < metrics.txt
```

## Layout

```
agent/         the -javaagent: config model + loader, event adapter, mapping engine,
               metric registry, HTTP server, and the bundled YAML packs
sample-app/    Spring Boot app that misbehaves on purpose, so JFR has something to report
docker/        compose stack, VictoriaMetrics scrape config, Grafana provisioning + dashboard
docs/          metric inventory and the design spec (with findings recorded as they were made)
scripts/       up.sh, down.sh, verify-shading.sh
```

## Limitations

This is a local POC.

- Metrics are in-memory only; restarting the agent resets counters.
- The agent serves plain HTTP with no auth or TLS — fine on localhost, not for exposure.
- `jdk.ObjectAllocationSample` is sampled, so allocation rate is an estimate, not a measurement.
- Lock contention below the pack's 1ms threshold is deliberately invisible, to bound overhead.
- Only tested on JDK 25 (macOS/aarch64 host, Linux containers).
