package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises ClaudeHttpClient against a real HTTP server (JDK's built-in
 * com.sun.net.httpserver, same approach as HttpRazorpayPaymentLinkClientTest)
 * rather than mocking RestClient - proves actual HTTP status/timeout/malformed
 * handling. Never calls the real Claude API.
 */
class ClaudeHttpClientTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ClaudeHttpClient clientWithReadTimeout(Duration readTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("x-api-key", "test-key");
                    request.getHeaders().set("anthropic-version", "2023-06-01");
                    return execution.execute(request, body);
                })
                .build();
        return new ClaudeHttpClient(restClient, "claude-sonnet-5");
    }

    private void respond(int status, String body) {
        server.createContext("/messages", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    private void respondSlowly(Duration delay) {
        server.createContext("/messages", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @Test
    void success_extractsTextFromFirstContentBlock() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(200, """
                {"id":"msg_1","type":"message","role":"assistant",
                 "content":[{"type":"text","text":"{\\"failureType\\":\\"MANDATE_INVALID\\",\\"confidence\\":0.9,\\"reasoning\\":\\"ok\\"}"}],
                 "model":"claude-sonnet-5","stop_reason":"end_turn"}
                """);

        String text = client.createStructuredMessage("system", "user message", SCHEMA, 512);

        assertEquals("{\"failureType\":\"MANDATE_INVALID\",\"confidence\":0.9,\"reasoning\":\"ok\"}", text);
    }

    @Test
    void status4xx_throwsDiagnosisUnavailable() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(401, """
                {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}
                """);

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }

    @Test
    void rateLimited429_throwsDiagnosisUnavailable() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(429, """
                {"type":"error","error":{"type":"rate_limit_error","message":"rate limited"}}
                """);

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }

    @Test
    void status5xx_throwsDiagnosisUnavailable() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(500, """
                {"type":"error","error":{"type":"api_error","message":"internal error"}}
                """);

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }

    @Test
    void readTimeout_throwsDiagnosisUnavailable() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofMillis(200));
        respondSlowly(Duration.ofSeconds(3));

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }

    @Test
    void malformedResponseBody_throwsDiagnosisUnavailable() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(200, "not json at all {{{");

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }

    @Test
    void emptyContentArray_throwsDiagnosisUnavailable() throws IOException {
        ClaudeHttpClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(200, """
                {"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-5"}
                """);

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }

    @Test
    void networkFailure_connectionRefused_throwsDiagnosisUnavailable() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        // Nothing is listening on this port - a real HttpServer was never
        // started for this test, unlike every other case here.
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestFactory(requestFactory)
                .build();
        ClaudeHttpClient client = new ClaudeHttpClient(restClient, "claude-sonnet-5");

        assertThrows(DiagnosisUnavailableException.class,
                () -> client.createStructuredMessage("system", "user", SCHEMA, 512));
    }
}
