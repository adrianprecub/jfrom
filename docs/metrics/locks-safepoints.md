# Locks & Safepoints (T9)

Source pack: `agent/src/main/resources/mappings/locks-safepoints.yaml`.
Test: `agent/src/test/java/io/jfrom/agent/mappings/LocksSafepointsPackTest.java`.

This is the family JMX/Micrometer cannot show at all: no standard JVM MXBean reports how long a
thread actually queued behind a contended monitor, how long a wait or park actually lasted, how
long the *entire JVM* stood still at a safepoint, or which VM operation caused that pause. Every
metric below comes from `jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait`, `jdk.ThreadPark`,
`jdk.SafepointBegin` and `jdk.ExecuteVMOperation` - fields verified against a live
`RecordingStream` probe on JDK 25.0.1 (see the design doc's "Verified JFR ground truth" table).

## Metric inventory

| Metric | Type | Unit | Labels | Source event | Meaning |
|---|---|---|---|---|---|
| `jfr_monitor_blocked_seconds` | histogram | seconds | `monitor_class` (capped, see below) | `jdk.JavaMonitorEnter` | Time a thread spent blocked trying to enter a contended `synchronized` monitor. Only acquisitions that took ≥1ms are recorded (see **Contention threshold**). |
| `jfr_monitor_wait_seconds` | histogram | seconds | `monitor_class` (capped), `timed_out` | `jdk.JavaMonitorWait` | Time a thread spent in `Object.wait()`. `timed_out` distinguishes a wait that expired from one that was notified. Only waits ≥1ms are recorded. |
| `jfr_park_seconds` | histogram | seconds | `parked_class` (capped) | `jdk.ThreadPark` | Time a thread spent parked via `LockSupport.park`/`parkNanos` (e.g. a thread-pool worker idling, or a blocking-queue wait). Only parks ≥1ms are recorded. |
| `jfr_safepoint_pause_seconds` | histogram | seconds | *(none)* | `jdk.SafepointBegin` | Duration of each JVM safepoint pause - the interval during which every application thread is stopped for the VM's own bookkeeping. Every safepoint is recorded, however short; see **No threshold on safepoint/VM-op events**. |
| `jfr_safepoint_threads` | gauge | count | *(none)* | `jdk.SafepointBegin` | Number of threads that had to reach the most recently observed safepoint (`totalThreadCount`). |
| `jfr_vmop_duration_seconds` | histogram | seconds | `operation`, `safepoint` | `jdk.ExecuteVMOperation` | Duration of each VM operation run by the VM thread, labelled by which operation it was and whether it required a safepoint. Every VM operation is recorded. |

Every histogram uses buckets `[0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]`
(1ms through 10s), sized for lock-contention and safepoint-pause magnitudes; anything longer still
counts in the implicit `+Inf` bucket.

All five source events carry `duration` as a JFR `@Timespan("TICKS")` field. Every duration-based
metric above therefore uses `value.kind: duration` with `value.unit: seconds`, never
`kind: number` - reading a `@Timespan` field as a plain number yields raw ticks (a number in the
millions/billions), which still renders a convincing-looking graph while being numerically
meaningless.

## Contention threshold

`jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait` and `jdk.ThreadPark` can each fire on *every* lock
acquisition, wait, or park in a busy application. At a 0ms threshold, a genuinely contended
workload can flood the JFR stream and add real overhead purely from event emission - independent
of the cost of the contention itself. All three are enabled with `enable.threshold: 1ms`, so only
acquisitions/waits/parks that actually cost something are recorded.

**This is a deliberate trade-off: sub-1ms contention on these three events is invisible on the
resulting dashboard.** A monitor acquired 100,000 times a second with 200µs of blocking each time
will show zero `jfr_monitor_blocked_seconds` samples. This pack optimizes for "show real
contention without flooding the stream," not "account for every nanosecond of lock overhead." If
that trade-off is wrong for a given deployment, lower the threshold in a local copy of the pack -
at the cost of the volume/overhead this threshold exists to avoid.

## No threshold on safepoint/VM-op events

`jdk.SafepointBegin` and `jdk.ExecuteVMOperation` are enabled with `threshold: 0ms` - every
safepoint and every VM operation is recorded, no matter how short. Their rate is governed by the
JVM itself (roughly one pair of events per safepoint), not by application lock-call volume, so
the flood risk that justifies the 1ms threshold above does not apply here. It is also the whole
point of this pair of metrics: JMX/Micrometer report nothing about safepoints at all, and even a
very short, frequent safepoint pause is exactly the kind of thing this project exists to surface.

## Cardinality caps

Four JFR fields verified as relevant to this pack are application-dependent and effectively
unbounded, or worse:

- **`monitorClass`** (`jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait`) and **`parkedClass`**
  (`jdk.ThreadPark`) are `kind: class` labels. Any class in the target application can be
  synchronized on or parked under, so the set of distinct values is effectively unbounded.
  `jfr_monitor_blocked_seconds`, `jfr_monitor_wait_seconds` and `jfr_park_seconds` each set
  **`maxCardinality: 20`** - enough to distinguish the small number of *hot* contended
  locks/park sites that actually matter on a dashboard, while guaranteeing the registry cannot
  grow without bound under an adversarial or simply diverse workload. Values beyond the cap
  collapse into the shared `__other__` series per the engine's cardinality guard.
- **`previousOwner`** (`jdk.JavaMonitorEnter`, a `kind: thread` field) and **`eventThread`** are
  worse than `monitorClass`/`parkedClass` - every distinct thread has a distinct name, so
  cardinality tracks thread count/churn directly. Neither is used as a label anywhere in this
  pack.
- **`safepointId`** (`jdk.SafepointBegin`) is unique per event by definition - unbounded
  cardinality with zero grouping value, since no two safepoints share an id. It is deliberately
  not used as a label; `jfr_safepoint_pause_seconds` and `jfr_safepoint_threads` carry no
  labels at all.
- **`operation`** and **`safepoint`** (`jdk.ExecuteVMOperation`) are the exception: `operation`
  is drawn from a small, fixed set of JVM-internal operation names, and `safepoint` is a boolean.
  Both are genuinely low-cardinality, so `jfr_vmop_duration_seconds` is left under the
  metric-wide default cap (`maxCardinality: 100`, the engine's `MetricSpec.DEFAULT_MAX_CARDINALITY`)
  without an explicit override - the realistic combination count (a few dozen operation names ×
  2) never approaches it.

## stackTrace

Every rule in this pack sets `enable.stackTrace: false` explicitly. All five source events
capture stack traces by default, and for a high-frequency event like `jdk.JavaMonitorEnter`
that capture is the dominant cost - far more expensive than emitting the event itself. This
project is time-series-only and never reads a stack trace, so there is no reason to pay for one.

## Ground-truth note

No discrepancy was found against the design doc's verified table for these five events; all
field names (`duration`, `monitorClass`, `previousOwner`, `timedOut`, `timeout`, `parkedClass`,
`safepointId`, `totalThreadCount`, `operation`, `safepoint`, `blocking`) were used exactly as
listed, except `address` (`jdk.JavaMonitorEnter`) and `until`/`timeout`
(`jdk.JavaMonitorWait`/`jdk.ThreadPark`) and `blocking` (`jdk.ExecuteVMOperation`), which this
pack does not map to any metric - they don't add observability value beyond what
`duration`/`monitor_class`/`timed_out`/`operation`/`safepoint` already provide, and `address` in
particular is a raw memory address (unbounded, meaningless as a label).
