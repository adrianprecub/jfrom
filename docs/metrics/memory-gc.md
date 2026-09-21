# Memory & GC metrics

Produced by `agent/src/main/resources/mappings/memory-gc.yaml` from `jdk.GCHeapSummary`,
`jdk.GarbageCollection`, `jdk.GCPhasePause`, `jdk.MetaspaceSummary` and
`jdk.ObjectAllocationSample`. Verified field names against JDK 25.0.1 (`jfr metadata`); see
`docs/superpowers/specs/2026-09-21-jfr2grafana-design.md`.

| Metric | Type | Unit | Labels | Source event | Meaning |
|---|---|---|---|---|---|
| `jfr_heap_used_bytes` | gauge | bytes | (none) | `jdk.GCHeapSummary` (`when="After GC"`) | Heap bytes in use, sampled immediately after each garbage collection. `jdk.GCHeapSummary` fires twice per GC (`"Before GC"` / `"After GC"`); this metric keeps only the post-GC occupancy figure. |
| `jfr_heap_committed_bytes` | gauge | bytes | (none) | `jdk.GCHeapSummary` (`when="After GC"`, field `heapSpace.committedSize`) | Heap bytes currently committed by the OS, sampled after each GC. `heapSpace` is a nested `VirtualSpace` struct; read via the dotted path. Always `>= jfr_heap_used_bytes`. |
| `jfr_gc_pause_seconds` | histogram | seconds | `gc` (collector name, e.g. `G1Full`, `G1New`), `cause` (e.g. `System.gc()`) | `jdk.GarbageCollection` (field `duration`) | Distribution of whole-GC-cycle pause durations. `duration` is `@Timespan("TICKS")`; read with `kind: duration` (never `kind: number`, which yields raw ticks). Exposes `_bucket`/`_sum`/`_count`. |
| `jfr_gc_count_total` | counter | count | `gc`, `cause` | `jdk.GarbageCollection` | Number of completed GC cycles, by collector and cause. No `value` field configured — the rule counts events (adds 1 per event). |
| `jfr_gc_longest_pause_seconds` | gauge | seconds | `gc`, `cause` | `jdk.GarbageCollection` (field `longestPause`) | Longest individual sub-pause within the most recently completed GC cycle. Last-value-wins per label set; also a `@Timespan("TICKS")` field. |
| `jfr_gc_sum_of_pauses_seconds_total` | counter | seconds | `gc`, `cause` | `jdk.GarbageCollection` (field `sumOfPauses`) | Running total of time Java execution was paused for garbage collection, accumulated across GC cycles. Each event's `sumOfPauses` (itself already a per-cycle total) is added as a delta, so `rate()`/`increase()` over this series gives GC-pause time per wall-clock second. |
| `jfr_gc_phase_pause_seconds` | histogram | seconds | `phase` (GC phase name, e.g. `GC Pause`) | `jdk.GCPhasePause` (field `duration`) | Finer-grained than `jfr_gc_pause_seconds`: one observation per individual GC phase rather than per whole cycle. `name` on this event is the *phase*, not the collector. |
| `jfr_metaspace_used_bytes` | gauge | bytes | (none) | `jdk.MetaspaceSummary` (`when="After GC"`, field `metaspace.used`) | Metaspace bytes in use, sampled after each GC. `jdk.MetaspaceSummary` also fires twice per GC, mirroring `jdk.GCHeapSummary`; only the post-GC value is kept. |
| `jfr_metaspace_committed_bytes` | gauge | bytes | (none) | `jdk.MetaspaceSummary` (`when="After GC"`, field `metaspace.committed`) | Metaspace bytes committed, sampled after each GC. Always `>= jfr_metaspace_used_bytes`. |
| `jfr_metaspace_class_used_bytes` | gauge | bytes | (none) | `jdk.MetaspaceSummary` (`when="After GC"`, field `classSpace.used`) | Bytes in use in the compressed-class-pointers sub-region of metaspace — the classloading-pressure breakdown of the total above. |
| `jfr_metaspace_class_committed_bytes` | gauge | bytes | (none) | `jdk.MetaspaceSummary` (`when="After GC"`, field `classSpace.committed`) | Bytes committed in the compressed-class-pointers sub-region. `dataSpace.*` is intentionally not exposed by this pack, to keep the metric count proportionate to the design doc's suggested coverage; add it the same way if class-vs-data metaspace attribution is ever needed. |
| `jfr_allocation_bytes_total` | counter | bytes | `objectClass` (allocated class name, `maxCardinality: 50`) | `jdk.ObjectAllocationSample` (field `weight`) | **Estimated** allocation rate, by allocated class. **Caveat: `weight` is not an exact per-allocation byte count.** `jdk.ObjectAllocationSample` is a *sampled* event (JFR's adaptive sampler, not a per-object hook); `weight` is the sampler's own estimate of how many bytes that sample "represents" among all allocations of that class since the last sample. Summing it via `rate()`/`increase()` gives a statistically reasonable allocation-rate *estimate*, not an exact measurement — do not read it as precise, and do not compare it directly against `jfr_heap_used_bytes` deltas. `objectClass` is unbounded in principle (any loaded class can allocate), so this metric caps distinct label combinations at 50; overflow collapses to the `__other__` label value per the engine's cardinality guard. |

## Notes on metric-type choices

- **gauge** for all heap/metaspace occupancy figures: each `jdk.GCHeapSummary`/`jdk.MetaspaceSummary`
  event is a fresh snapshot, not an accumulated delta, so "last value wins" is correct.
- **counter** for `jfr_gc_count_total`, `jfr_gc_sum_of_pauses_seconds_total` and
  `jfr_allocation_bytes_total`: each event contributes an independent amount to a running total
  that should be observed via `rate()`/`increase()` (GC frequency, time spent pausing, allocation
  pressure). None of these fields are already a JVM-side cumulative counter, so `counter` (add a
  delta) is correct — `counter_absolute` would be wrong here and is not used anywhere in this pack.
- **histogram** for the two pause-duration metrics, with buckets from 100µs to 10s: sub-millisecond
  young-gen pauses through multi-second full/Full GC pauses are both plausible on this event set.
- Every rule sets `stackTrace: false` — this pack never reads a stack trace, and capturing them is
  the dominant cost of high-frequency events.
