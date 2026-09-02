package com.recoversense.dashboard;

import com.recoversense.razorpay.RazorpayPaymentSyncService;
import com.recoversense.razorpay.RazorpaySyncResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1.32: proves the CORS mapping fix at the layer that actually broke -
 * MockMvc calls without an Origin header (as every other controller test in
 * this codebase makes them) never exercise Spring's CORS processing at all,
 * which is exactly why the browser 403 on POST /api/dashboard/payments/sync
 * went uncaught by the existing suite. Setting Origin here reproduces the
 * real cross-origin browser request from the Vite dev server.
 * <p>
 * M1.35: also proves app.cors.allowed-origins - set here to the local
 * default plus one production-looking origin (comma-separated, exercising
 * that support) - accepts both configured origins and rejects an
 * unconfigured one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:*,https://recoversense-demo.vercel.app")
class DashboardCorsConfigTest {

    private static final String VITE_DEV_ORIGIN = "http://localhost:5173";
    private static final String PROD_ORIGIN = "https://recoversense-demo.vercel.app";
    private static final String UNCONFIGURED_ORIGIN = "https://evil.example.com";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private RazorpayPaymentSyncService razorpayPaymentSyncService;

    @Test
    void crossOriginPostToPaymentsSync_passesCors_reachesController() throws Exception {
        Mockito.when(razorpayPaymentSyncService.syncFailedPayments())
                .thenReturn(new RazorpaySyncResult(true, 0, 0));

        mockMvc.perform(post("/api/dashboard/payments/sync").header("Origin", VITE_DEV_ORIGIN))
                .andExpect(status().isOk());
    }

    /**
     * The narrow /api/dashboard/payments/sync mapping must not have widened
     * the broader /api/dashboard/** prefix. DashboardController maps no POST
     * handler at all under /api/dashboard/** (metrics is GET-only), so Spring
     * MVC's handler-method resolution itself refuses a cross-origin POST here
     * with 405 before CORS processing is even reached - proving no mutating
     * handler was newly exposed under this prefix.
     */
    @Test
    void crossOriginPostToMetrics_stillRejectedAsNoSuchHandler() throws Exception {
        mockMvc.perform(post("/api/dashboard/metrics").header("Origin", VITE_DEV_ORIGIN))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void crossOriginGetToMetrics_stillAllowed() throws Exception {
        mockMvc.perform(get("/api/dashboard/metrics").header("Origin", VITE_DEV_ORIGIN))
                .andExpect(status().isOk());
    }

    /**
     * Unambiguous proof that CORS itself (not just "no handler exists")
     * still restricts /api/dashboard/** to GET: a real CORS preflight
     * (OPTIONS + Access-Control-Request-Method) asking to POST to sync's
     * sibling path is rejected by DefaultCorsProcessor - preflight requests
     * are always method-checked against the CorsConfiguration, independent
     * of whether any handler is mapped for that method.
     */
    @Test
    void preflightForPostOnMetrics_rejectedByCors() throws Exception {
        mockMvc.perform(options("/api/dashboard/metrics")
                        .header("Origin", VITE_DEV_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    /**
     * Same preflight check, but proving the fix: the same style of request
     * against /api/dashboard/payments/sync itself must be allowed.
     */
    @Test
    void preflightForPostOnPaymentsSync_allowedByCors() throws Exception {
        mockMvc.perform(options("/api/dashboard/payments/sync")
                        .header("Origin", VITE_DEV_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    /**
     * M1.35: the configured production origin (Vercel) must be treated
     * identically to the local dev origin - proves app.cors.allowed-origins
     * is actually read, not just defaulted.
     */
    @Test
    void configuredProductionOrigin_allowedByCors() throws Exception {
        mockMvc.perform(options("/api/dashboard/metrics")
                        .header("Origin", PROD_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
    }

    /**
     * M1.35: an origin that was never configured must still be rejected -
     * proves the change didn't accidentally widen to a wildcard.
     */
    @Test
    void unconfiguredOrigin_rejectedByCors() throws Exception {
        mockMvc.perform(options("/api/dashboard/metrics")
                        .header("Origin", UNCONFIGURED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
