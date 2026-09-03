package com.recoversense.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Without the demo profile active (every existing test class, and every
 * default/production boot), DemoController must not be wired at all - both
 * routes resolve to Spring's ordinary 404 for an unmapped path, proving the
 * reset capability genuinely does not exist outside the demo profile rather
 * than merely being hidden behind a runtime check.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoControllerNotActiveByDefaultTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void status_notAvailableOutsideDemoProfile() throws Exception {
        mockMvc.perform(get("/api/demo/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resetPaymentLink_notAvailableOutsideDemoProfile() throws Exception {
        mockMvc.perform(post("/api/demo/reset-payment-link"))
                .andExpect(status().isNotFound());
    }
}
