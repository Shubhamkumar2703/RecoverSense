package com.recoversense.batch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the batch evaluation route is reachable in the default profile
 * (unlike DemoController, this capability is not demo-profile-gated - it
 * never touches persisted data or Razorpay, so it's safe in every profile)
 * and that the response is honestly labeled as simulated/evaluation data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void evaluate_returnsBatchMetricsWithSimulationLabel() throws Exception {
        mockMvc.perform(get("/api/batch/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetLabel").value(org.hamcrest.Matchers.containsString("SIMULATED")))
                .andExpect(jsonPath("$.metrics.batchSize").value(10))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(10)));
    }
}
