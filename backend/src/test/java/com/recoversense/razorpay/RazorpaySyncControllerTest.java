package com.recoversense.razorpay;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1.26: proves the HTTP boundary's outcome mapping (same style as
 * RecoveryControllerOutcomeMappingTest - the service is stubbed since the
 * default test context has no real Razorpay credentials).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RazorpaySyncControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private RazorpayPaymentSyncService razorpayPaymentSyncService;

    @Test
    void sync_available_returnsOkWithCounts() throws Exception {
        Mockito.when(razorpayPaymentSyncService.syncFailedPayments())
                .thenReturn(RazorpaySyncResult.completed(3, 1));

        mockMvc.perform(post("/api/dashboard/payments/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    void sync_notConfigured_returnsServiceUnavailableWithHonestMessage() throws Exception {
        Mockito.when(razorpayPaymentSyncService.syncFailedPayments())
                .thenReturn(RazorpaySyncResult.unavailable());

        mockMvc.perform(post("/api/dashboard/payments/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").exists());
    }
}
