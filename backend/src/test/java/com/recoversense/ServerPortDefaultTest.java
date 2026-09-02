package com.recoversense;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.35: server.port=${PORT:8081} must still resolve to 8081 for local
 * development, where no PORT env var is set.
 */
@SpringBootTest
class ServerPortDefaultTest {

    @Value("${server.port}")
    private String serverPort;

    @Test
    void defaultsTo8081WhenPortNotSet() {
        assertEquals("8081", serverPort);
    }
}
