package io.jfr2grafana.sample.web;

import io.jfr2grafana.sample.workload.ChurnWorkload;
import io.jfr2grafana.sample.workload.ChurnWorkload.ChurnResult;
import io.jfr2grafana.sample.workload.ContendWorkload;
import io.jfr2grafana.sample.workload.ContendWorkload.ContendResult;
import io.jfr2grafana.sample.workload.CpuWorkload;
import io.jfr2grafana.sample.workload.CpuWorkload.CpuResult;
import io.jfr2grafana.sample.workload.IoWorkload;
import io.jfr2grafana.sample.workload.IoWorkload.IoResult;
import io.jfr2grafana.sample.workload.LeakWorkload;
import io.jfr2grafana.sample.workload.LeakWorkload.LeakResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Each endpoint deliberately misbehaves in one specific way so a Grafana
 * panel can be traced back to a single cause. Every parameter is bounded so
 * a demo call can never hang the JVM or exhaust its heap.
 */
@RestController
@RequestMapping("/load")
public class LoadController {

    private final ChurnWorkload churnWorkload;
    private final ContendWorkload contendWorkload;
    private final LeakWorkload leakWorkload;
    private final IoWorkload ioWorkload;
    private final CpuWorkload cpuWorkload;

    public LoadController(ChurnWorkload churnWorkload,
                           ContendWorkload contendWorkload,
                           LeakWorkload leakWorkload,
                           IoWorkload ioWorkload,
                           CpuWorkload cpuWorkload) {
        this.churnWorkload = churnWorkload;
        this.contendWorkload = contendWorkload;
        this.leakWorkload = leakWorkload;
        this.ioWorkload = ioWorkload;
        this.cpuWorkload = cpuWorkload;
    }

    /** Allocation storm: allocate and immediately discard, driving young-gen GC and allocation rate. */
    @PostMapping("/churn")
    public ChurnResult churn(@RequestParam(name = "intensity", defaultValue = "5") int intensity,
                              @RequestParam(name = "durationMs", defaultValue = "200") long durationMs) {
        requireRange("intensity", intensity, 1, 10);
        requireRange("durationMs", durationMs, 1, 10_000);
        return churnWorkload.run(intensity, durationMs);
    }

    /** Lock contention: a pool of threads contending on one monitor, each holding it a few ms. */
    @PostMapping("/contend")
    public ContendResult contend(@RequestParam(name = "threads", defaultValue = "4") int threads,
                                  @RequestParam(name = "holdMs", defaultValue = "5") int holdMs,
                                  @RequestParam(name = "durationMs", defaultValue = "500") long durationMs) {
        requireRange("threads", threads, 2, 16);
        requireRange("holdMs", holdMs, 1, 50);
        requireRange("durationMs", durationMs, 1, 10_000);
        return contendWorkload.run(threads, holdMs, durationMs);
    }

    /** Slow retained growth into a bounded, long-lived collection. */
    @PostMapping("/leak")
    public LeakResult leak(@RequestParam(name = "amount", defaultValue = "10") int amount) {
        requireRange("amount", amount, 0, 100_000);
        return leakWorkload.grow(amount);
    }

    /** Frees every retained reference so a long demo cannot OOM the container. */
    @PostMapping("/leak/reset")
    public LeakResult leakReset() {
        leakWorkload.reset();
        return new LeakResult(leakWorkload.size(), leakWorkload.maxEntries(), 0);
    }

    /** File and socket traffic: write/read a temp file, and a real loopback socket round trip. */
    @PostMapping("/io")
    public IoResult io(@RequestParam(name = "sizeKb", defaultValue = "8") int sizeKb) {
        requireRange("sizeKb", sizeKb, 1, 1024);
        try {
            return ioWorkload.run(sizeKb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A hot compute loop across several threads, driving CPU load and JIT compilation. */
    @PostMapping("/cpu")
    public CpuResult cpu(@RequestParam(name = "threads", defaultValue = "2") int threads,
                          @RequestParam(name = "durationMs", defaultValue = "300") long durationMs) {
        requireRange("threads", threads, 1, 8);
        requireRange("durationMs", durationMs, 1, 10_000);
        return cpuWorkload.run(threads, durationMs);
    }

    private void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "%s must be between %d and %d (was %d)".formatted(name, min, max, value));
        }
    }
}
