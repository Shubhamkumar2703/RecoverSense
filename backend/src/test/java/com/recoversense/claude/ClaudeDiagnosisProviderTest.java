package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisContext;
import com.recoversense.diagnosis.DiagnosisSource;
import com.recoversense.diagnosis.DiagnosisUnavailableException;
import com.recoversense.diagnosis.RecoveryDiagnosis;
import com.recoversense.domain.CustomerStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves Claude's response is treated as untrusted input end-to-end: HTTP
 * response body -> ClaudeHttpClient -> ClaudeDiagnosisProvider's validation.
 * Every invalid shape must fail closed (DiagnosisUnavailableException), never
 * fabricate a plausible RecoveryDiagnosis. Never calls the real Claude API.
 */
class ClaudeDiagnosisProviderTest {

    private static final DiagnosisContext CONTEXT =
            new DiagnosisContext("mandate_revoked", "active", CustomerStatus.ACTIVE, 0);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ClaudeDiagnosisProvider providerRespondingWithText(String claudeTextField) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/messages", exchange -> {
            String responseBody = """
                    {"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":%s}],"model":"claude-sonnet-5"}
                    """.formatted(jsonQuote(claudeTextField));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        return new ClaudeDiagnosisProvider(new ClaudeHttpClient(restClient, "claude-sonnet-5"));
    }

    /** Wraps a raw (already-serialized) JSON string as a JSON string literal for the outer envelope. */
    private String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void validResponse_producesClaudeTaggedDiagnosis() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"confidence\":0.94,\"reasoning\":\"mandate revoked while subscription active\"}");

        RecoveryDiagnosis diagnosis = provider.diagnose(CONTEXT);

        assertEquals("MANDATE_INVALID", diagnosis.failureType());
        assertEquals(0, new BigDecimal("0.94").compareTo(diagnosis.confidence()));
        assertEquals("mandate revoked while subscription active", diagnosis.reasoning());
        assertEquals(DiagnosisSource.CLAUDE, diagnosis.source());
    }

    @Test
    void unknownFailureType_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"SOMETHING_MADE_UP\",\"confidence\":0.9,\"reasoning\":\"x\"}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void missingFailureType_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"confidence\":0.9,\"reasoning\":\"x\"}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void missingConfidence_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"reasoning\":\"x\"}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void negativeConfidence_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"confidence\":-0.1,\"reasoning\":\"x\"}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void confidenceAboveOne_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"confidence\":1.1,\"reasoning\":\"x\"}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void missingReasoning_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"confidence\":0.9}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void blankReasoning_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"confidence\":0.9,\"reasoning\":\"   \"}");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void malformedJson_failsClosed() throws IOException {
        ClaudeDiagnosisProvider provider = providerRespondingWithText("not json at all {{{");

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }

    @Test
    void excessivelyLongReasoning_isBoundedNotRejected() throws IOException {
        String longReasoning = "x".repeat(5000);
        ClaudeDiagnosisProvider provider = providerRespondingWithText(
                "{\"failureType\":\"MANDATE_INVALID\",\"confidence\":0.9,\"reasoning\":\"" + longReasoning + "\"}");

        RecoveryDiagnosis diagnosis = provider.diagnose(CONTEXT);

        assertTrue(diagnosis.reasoning().length() <= 1000, "reasoning must be bounded");
    }

    @Test
    void emptyResponse_failsClosed() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/messages", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        ClaudeDiagnosisProvider provider = new ClaudeDiagnosisProvider(new ClaudeHttpClient(restClient, "claude-sonnet-5"));

        assertThrows(DiagnosisUnavailableException.class, () -> provider.diagnose(CONTEXT));
    }
}
