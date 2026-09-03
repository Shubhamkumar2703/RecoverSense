package com.recoversense.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the demo reset routes are actually reachable, and behave correctly,
 * under the demo profile (contrast with DemoControllerNotActiveByDefaultTest,
 * which proves the opposite for every other profile). {@code @Transactional}:
 * relies on DemoDataSeeder's CommandLineRunner having already seeded
 * pay_demo_payment_link at context startup; this test's own writes (via the
 * reset call) roll back afterward.
 */
@SpringBootTest
@ActiveProfiles("demo")
@AutoConfigureMockMvc
@Transactional
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void status_isAvailableUnderDemoProfile() throws Exception {
        mockMvc.perform(get("/api/demo/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void resetPaymentLink_targetsExactlyTheHeroPaymentAndReturnsFailed() throws Exception {
        mockMvc.perform(post("/api/demo/reset-payment-link"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.paymentId").value(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }
}
