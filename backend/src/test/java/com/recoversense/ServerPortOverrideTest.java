package com.recoversense;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.35: server.port=${PORT:8081} must bind to a supplied PORT (e.g. what
 * Render injects) instead of the 8081 default.
 */
@SpringBootTest
@TestPropertySource(properties = "PORT=9090")
class ServerPortOverrideTest {

    @Value("${server.port}")
    private String serverPort;

    @Test
    void usesSuppliedPortInstead() {
        assertEquals("9090", serverPort);
    }
}
