package io.jfr2grafana.sample.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One test per {@code /load/*} endpoint: it must respond with the workload's
 * result, and it must validate its own parameters (reject out-of-range
 * values with 400 without ever invoking the workload).
 */
@WebMvcTest(LoadController.class)
class LoadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChurnWorkload churnWorkload;
    @MockitoBean
    private ContendWorkload contendWorkload;
    @MockitoBean
    private LeakWorkload leakWorkload;
    @MockitoBean
    private IoWorkload ioWorkload;
    @MockitoBean
    private CpuWorkload cpuWorkload;

    @Test
    void churnRunsAndReturnsResult() throws Exception {
        when(churnWorkload.run(anyInt(), anyLong())).thenReturn(new ChurnResult(5, 20, 42, 1024));

        mockMvc.perform(post("/load/churn").param("intensity", "5").param("durationMs", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations").value(42))
                .andExpect(jsonPath("$.bytesAllocated").value(1024));

        verify(churnWorkload).run(5, 20);
    }

    @Test
    void churnRejectsOutOfRangeIntensity() throws Exception {
        mockMvc.perform(post("/load/churn").param("intensity", "11").param("durationMs", "20"))
                .andExpect(status().isBadRequest());

        verify(churnWorkload, never()).run(anyInt(), anyLong());
    }

    @Test
    void churnRejectsOutOfRangeDuration() throws Exception {
        mockMvc.perform(post("/load/churn").param("intensity", "5").param("durationMs", "999999"))
                .andExpect(status().isBadRequest());

        verify(churnWorkload, never()).run(anyInt(), anyLong());
    }

    @Test
    void contendRunsAndReturnsResult() throws Exception {
        when(contendWorkload.run(anyInt(), anyInt(), anyLong())).thenReturn(new ContendResult(4, 5, 50, 7));

        mockMvc.perform(post("/load/contend")
                        .param("threads", "4").param("holdMs", "5").param("durationMs", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorEntries").value(7));

        verify(contendWorkload).run(4, 5, 50);
    }

    @Test
    void contendRejectsTooFewThreads() throws Exception {
        mockMvc.perform(post("/load/contend").param("threads", "1"))
                .andExpect(status().isBadRequest());

        verify(contendWorkload, never()).run(anyInt(), anyInt(), anyLong());
    }

    @Test
    void contendRejectsHoldMsOutOfRange() throws Exception {
        mockMvc.perform(post("/load/contend").param("threads", "4").param("holdMs", "500"))
                .andExpect(status().isBadRequest());

        verify(contendWorkload, never()).run(anyInt(), anyInt(), anyLong());
    }

    @Test
    void leakGrowsAndReturnsResult() throws Exception {
        when(leakWorkload.grow(10)).thenReturn(new LeakResult(10, 2000, 10));

        mockMvc.perform(post("/load/leak").param("amount", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSize").value(10))
                .andExpect(jsonPath("$.maxEntries").value(2000));

        verify(leakWorkload).grow(10);
    }

    @Test
    void leakRejectsNegativeAmount() throws Exception {
        mockMvc.perform(post("/load/leak").param("amount", "-1"))
                .andExpect(status().isBadRequest());

        verify(leakWorkload, never()).grow(anyInt());
    }

    @Test
    void leakResetClearsAndReportsZero() throws Exception {
        when(leakWorkload.size()).thenReturn(0);
        when(leakWorkload.maxEntries()).thenReturn(2000);

        mockMvc.perform(post("/load/leak/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSize").value(0))
                .andExpect(jsonPath("$.added").value(0));

        verify(leakWorkload, times(1)).reset();
    }

    @Test
    void ioRunsAndReturnsResult() throws Exception {
        when(ioWorkload.run(8)).thenReturn(new IoResult(8192, 8192, 8192));

        mockMvc.perform(post("/load/io").param("sizeKb", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileBytesWritten").value(8192))
                .andExpect(jsonPath("$.socketBytesEchoed").value(8192));

        verify(ioWorkload).run(8);
    }

    @Test
    void ioRejectsOutOfRangeSize() throws Exception {
        mockMvc.perform(post("/load/io").param("sizeKb", "0"))
                .andExpect(status().isBadRequest());

        verify(ioWorkload, never()).run(anyInt());
    }

    @Test
    void cpuRunsAndReturnsResult() throws Exception {
        when(cpuWorkload.run(anyInt(), anyLong())).thenReturn(new CpuResult(2, 30, 100));

        mockMvc.perform(post("/load/cpu").param("threads", "2").param("durationMs", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iterations").value(100));

        verify(cpuWorkload).run(2, 30);
    }

    @Test
    void cpuRejectsTooManyThreads() throws Exception {
        mockMvc.perform(post("/load/cpu").param("threads", "9"))
                .andExpect(status().isBadRequest());

        verify(cpuWorkload, never()).run(anyInt(), anyLong());
    }
}
