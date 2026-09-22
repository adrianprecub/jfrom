# JIT, class loading, I/O and exceptions metrics

Source pack: `agent/src/main/resources/mappings/jit-io-class.yaml`.
Covers `jdk.Compilation`, `jdk.CompilerStatistics`, `jdk.ClassLoadingStatistics`,
`jdk.ExceptionStatistics`, `jdk.SocketRead`, `jdk.SocketWrite`, `jdk.FileRead`, `jdk.FileWrite`.

Every metric name is prefixed with one of `jfr_jit_`, `jfr_class_`/`jfr_classes_`,
`jfr_socket_`, `jfr_file_`, `jfr_exception_`/`jfr_exceptions_` — this pack's slice of the
project-wide `jfr_` namespace, kept disjoint from the other three concurrently-authored packs
(memory/GC, CPU/threads, locks/safepoints) so `MappingConfig` never sees a duplicate metric name.

## Metric inventory

| Metric | Type | Unit | Labels | Source event | Meaning |
|---|---|---|---|---|---|
| `jfr_jit_compile_seconds` | histogram | seconds | `compiler`, `level` | `jdk.Compilation` | Duration of one JIT compilation attempt |
| `jfr_jit_compiled_code_bytes` | histogram | bytes | `compiler` | `jdk.Compilation` | Native code size produced by one compilation |
| `jfr_jit_compilations_total` | counter | count | `compiler`, `succeeded`, `osr` | `jdk.Compilation` | Count of compilation attempts, by outcome |
| `jfr_jit_compiler_compiled_methods_total` | counter_absolute | count | — | `jdk.CompilerStatistics` | Cumulative methods compiled since JVM start |
| `jfr_jit_compiler_bailouts_total` | counter_absolute | count | — | `jdk.CompilerStatistics` | Cumulative compilation bailouts since JVM start |
| `jfr_jit_compiler_standard_bytes_compiled_total` | counter_absolute | bytes | — | `jdk.CompilerStatistics` | Cumulative bytes of standard (non-OSR) compiled code |
| `jfr_jit_compiler_osr_bytes_compiled_total` | counter_absolute | bytes | — | `jdk.CompilerStatistics` | Cumulative bytes of on-stack-replacement compiled code |
| `jfr_jit_compiler_nmethods_bytes_total` | counter_absolute | bytes | — | `jdk.CompilerStatistics` | Cumulative size of compiled nmethods produced |
| `jfr_jit_compiler_time_seconds_total` | counter_absolute | seconds | — | `jdk.CompilerStatistics` | Cumulative wall-clock time spent compiling |
| `jfr_classes_loaded_total` | counter_absolute | count | — | `jdk.ClassLoadingStatistics` | Cumulative classes loaded since JVM start |
| `jfr_classes_unloaded_total` | counter_absolute | count | — | `jdk.ClassLoadingStatistics` | Cumulative classes unloaded since JVM start |
| `jfr_exceptions_thrown_total` | counter_absolute | count | — | `jdk.ExceptionStatistics` | Cumulative `Throwable` instances created since JVM start |
| `jfr_socket_read_seconds` | histogram | seconds | — | `jdk.SocketRead` | Socket read latency |
| `jfr_socket_read_bytes_total` | counter | bytes | — | `jdk.SocketRead` | Bytes read from sockets |
| `jfr_socket_write_seconds` | histogram | seconds | — | `jdk.SocketWrite` | Socket write latency |
| `jfr_socket_write_bytes_total` | counter | bytes | — | `jdk.SocketWrite` | Bytes written to sockets |
| `jfr_file_read_seconds` | histogram | seconds | — | `jdk.FileRead` | File read latency |
| `jfr_file_read_bytes_total` | counter | bytes | — | `jdk.FileRead` | Bytes read from files |
| `jfr_file_write_seconds` | histogram | seconds | — | `jdk.FileWrite` | File write latency |
| `jfr_file_write_bytes_total` | counter | bytes | — | `jdk.FileWrite` | Bytes written to files |

Histogram metrics also expose the usual `_bucket{le=...}`, `_sum`, `_count` series.

## Cumulative totals vs. summed deltas

Three of this pack's four periodic events (`jdk.CompilerStatistics`,
`jdk.ClassLoadingStatistics`, `jdk.ExceptionStatistics`) report numbers the JVM has already
accumulated since startup — `compileCount`, `bailoutCount`, `standardBytesCompiled`,
`osrBytesCompiled`, `nmethodsSize`, `totalTimeSpent`, `loadedClassCount`, `unloadedClassCount`,
`throwables` are **all cumulative**. Every metric built from one of them is `counter_absolute`:
the registry *sets* the series to the reported total (and, per `MetricRegistry`'s contract,
never lets it go backwards), so PromQL `rate()`/`increase()` over the raw series is meaningful.
Mapping any of these as `counter` would add each periodic snapshot's already-cumulative value on
top of the running total, compounding it every second into a wildly wrong curve — this is the
single easiest mistake to make in this pack, since a "loaded class count" *looks* like something
you'd naturally sum.

By contrast, `jdk.Compilation`, `jdk.SocketRead/Write` and `jdk.FileRead/Write` are per-attempt
events: `bytesRead`/`bytesWritten` is the number of bytes moved by *that one call*, a genuine
per-event delta. Those are mapped with the adding `counter`, so PromQL `rate()` over
`jfr_socket_read_bytes_total` etc. gives real throughput. `jfr_jit_compilations_total` is a bare
counter (no `value`) for the same reason — it counts attempts, not an accumulated JVM field.

`totalTimeSpent` on `jdk.CompilerStatistics` is declared `@Timespan("MILLISECONDS")`, not
`TICKS` like the duration fields elsewhere in this pack — but it is still a real `@Timespan`
field, so `kind: duration` still applies the correct unit conversion via `getDuration()`; reading
it as `kind: number` would report raw milliseconds under a `_seconds` name.

## Why `path`, `host`, and `address` are never labels

`jdk.FileRead.path`/`jdk.FileWrite.path` are unbounded: this project's own fixture generator and
sample workload both create temp files with randomly-generated names, so labelling by `path`
would mint a fresh time series (and, past `maxCardinality`, a fresh
`jfrom_cardinality_dropped_total` increment) for essentially every file operation forever.
Capping it doesn't rescue the situation either — once cardinality is dominated by one-off paths,
the collapsed `__other__` bucket absorbs nearly all samples and stops being useful as a
breakdown. The same reasoning applies to `jdk.SocketRead`/`SocketWrite`'s `host` and `address`:
whichever side of a connection is doing the reading, the remote endpoint is frequently the
high-cardinality one (an ephemeral client port/address on a server, or a rotating set of
upstream hosts on a client). This pack reports aggregate byte/latency totals for sockets and
files with no per-endpoint or per-path breakdown; that finer-grained detail belongs in a
log/trace tool, which the project's design doc explicitly puts out of scope (no Loki, no
Pyroscope, no stack traces).

`jdk.Compilation.compiler` (`"c1"`/`"c2"`) and `compileLevel` are the opposite case: verified
low-cardinality (a handful of compiler tiers), so they are used as labels on
`jfr_jit_compile_seconds`/`jfr_jit_compiled_code_bytes`/`jfr_jit_compilations_total` without
concern.

## `enable` settings

- `jdk.Compilation`: `threshold: 1ms`. JIT recompiles trivial methods constantly under normal
  load — almost all C1 compiles finish in well under a millisecond — and admitting every one of
  them would make this the highest-volume event in the pack for no analytical benefit; the
  compiles worth graphing (C2, OSR, anything slow enough to matter) all clear 1ms comfortably.
- `jdk.CompilerStatistics` / `jdk.ClassLoadingStatistics` / `jdk.ExceptionStatistics`:
  `period: 1s`. These are periodic snapshots of cumulative counters, so a 1s cadence is enough
  resolution without generating meaningful extra volume.
- `jdk.SocketRead`/`SocketWrite`/`FileRead`/`FileWrite`: `threshold: 0ms`, deliberately **not**
  raised, even though these can be a genuinely high-volume event class under real traffic. Unlike
  `jdk.Compilation`, a nonzero threshold on an I/O event would silently drop the
  `bytesRead`/`bytesWritten` of every call that happens to complete quickly — for a loopback or
  LAN socket, or a warm local file, that is nearly every call — which would make the byte
  counters systematically undercount real throughput. The byte totals are the more valuable
  signal for this pack than shedding load by threshold; volume is instead bounded the safe way,
  by not labelling these events by `path`/`host`/`address` at all (see above).
- Every rule sets `stackTrace: false`: this project produces time series only and never reads a
  stack trace.

## Ground-truth check

All field names and types (`jdk.Compilation.succeded` — the JDK's own typo, `duration` as
`@Timespan("TICKS")`, the cumulative fields on the three statistics events, `bytesRead`/
`bytesWritten` as per-event deltas, `path`/`host`/`address` cardinality) were re-verified against
this JDK (Temurin 25.0.1) with `jfr metadata --events jdk.Compilation,jdk.CompilerStatistics,
jdk.ClassLoadingStatistics,jdk.ExceptionStatistics,jdk.SocketRead,jdk.SocketWrite,jdk.FileRead,
jdk.FileWrite` while authoring this pack. No discrepancy from the design doc's "Verified JFR
ground truth" table was found; `jfr metadata` additionally confirmed the exact field names for
`CompilerStatistics` (`compileCount`, `bailoutCount`, `invalidatedCount`, `osrCompileCount`,
`standardCompileCount`, `osrBytesCompiled`, `standardBytesCompiled`, `nmethodsSize`,
`nmethodCodeSize`, `peakTimeSpent`, `totalTimeSpent`) that the design doc's table only
summarized with "…".
