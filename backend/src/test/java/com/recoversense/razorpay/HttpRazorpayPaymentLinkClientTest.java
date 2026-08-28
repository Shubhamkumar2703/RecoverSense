package com.recoversense.razorpay;

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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises HttpRazorpayPaymentLinkClient against a real HTTP server (JDK's
 * built-in com.sun.net.httpserver, no new test dependency) rather than
 * mocking RestClient - proves the actual HTTP status/timeout/malformed-body
 * handling, not just that a mock was configured correctly.
 */
class HttpRazorpayPaymentLinkClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpRazorpayPaymentLinkClient clientWithReadTimeout(Duration readTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth("test_key", "test_secret");
                    return execution.execute(request, body);
                })
                .build();
        return new HttpRazorpayPaymentLinkClient(restClient);
    }

    private void respond(int status, String body) {
        server.createContext("/payment_links", exchange -> {
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
        server.createContext("/payment_links", exchange -> {
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
    void create_success_parsesPaymentLink() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(200, """
                {"id":"plink_abc123","reference_id":"rs-action-1","status":"created","amount":100000,"amount_paid":0,"short_url":"https://rzp.io/i/x"}
                """);

        RazorpayPaymentLink link = client.create(new CreatePaymentLinkRequest(100000, "INR", "rs-action-1", "desc", null));

        assertEquals("plink_abc123", link.id());
        assertEquals(RazorpayPaymentLinkStatus.CREATED, link.status());
        assertEquals(100000, link.amountInSmallestUnit());
    }

    @Test
    void create_4xxRejection_throwsProviderRejected() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"A required parameter is missing","field":"amount"}}
                """);

        ProviderRejectedException ex = assertThrows(ProviderRejectedException.class,
                () -> client.create(new CreatePaymentLinkRequest(100000, "INR", "rs-action-1", "desc", null)));
        assertEquals("BAD_REQUEST_ERROR", ex.getRazorpayErrorCode());
    }

    @Test
    void create_duplicateReferenceIdRejection_throwsProviderUnavailable() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(400, """
                {"error":{"code":"BAD_REQUEST_ERROR","description":"An existing reference id has been passed","field":"reference_id"}}
                """);

        // Must be ambiguous, not a plain rejection - an earlier attempt may have
        // actually succeeded server-side.
        assertThrows(ProviderUnavailableException.class,
                () -> client.create(new CreatePaymentLinkRequest(100000, "INR", "rs-action-1", "desc", null)));
    }

    @Test
    void create_5xx_throwsProviderUnavailable() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(500, """
                {"error":{"code":"SERVER_ERROR","description":"trouble completing your request"}}
                """);

        assertThrows(ProviderUnavailableException.class,
                () -> client.create(new CreatePaymentLinkRequest(100000, "INR", "rs-action-1", "desc", null)));
    }

    @Test
    void create_readTimeout_throwsProviderUnavailable() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofMillis(200));
        respondSlowly(Duration.ofSeconds(3));

        assertThrows(ProviderUnavailableException.class,
                () -> client.create(new CreatePaymentLinkRequest(100000, "INR", "rs-action-1", "desc", null)));
    }

    @Test
    void create_malformedResponseBody_throwsProviderUnavailable() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(200, "not json at all {{{");

        assertThrows(ProviderUnavailableException.class,
                () -> client.create(new CreatePaymentLinkRequest(100000, "INR", "rs-action-1", "desc", null)));
    }

    @Test
    void fetchById_success() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/payment_links/plink_abc123", exchange -> {
            byte[] bytes = """
                    {"id":"plink_abc123","reference_id":"rs-action-1","status":"paid","amount":100000,"amount_paid":100000,"short_url":"https://rzp.io/i/x"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .build();
        HttpRazorpayPaymentLinkClient client = new HttpRazorpayPaymentLinkClient(restClient);

        RazorpayPaymentLink link = client.fetchById("plink_abc123");

        assertEquals(RazorpayPaymentLinkStatus.PAID, link.status());
        assertEquals(100000, link.amountPaidInSmallestUnit());
    }

    @Test
    void findByReferenceId_found_returnsLink() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/payment_links", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("reference_id=rs-action-1"));
            byte[] bytes = """
                    {"payment_links":[{"id":"plink_abc123","reference_id":"rs-action-1","status":"created","amount":100000,"amount_paid":0,"short_url":"https://rzp.io/i/x"}]}
                    """.getBytes(StandardCharsets.UTF_8);
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
        HttpRazorpayPaymentLinkClient client = new HttpRazorpayPaymentLinkClient(restClient);

        Optional<RazorpayPaymentLink> found = client.findByReferenceId("rs-action-1");

        assertTrue(found.isPresent());
        assertEquals("plink_abc123", found.get().id());
    }

    @Test
    void findByReferenceId_notFound_returnsEmpty() throws IOException {
        HttpRazorpayPaymentLinkClient client = clientWithReadTimeout(Duration.ofSeconds(5));
        respond(200, """
                {"payment_links":[]}
                """);

        Optional<RazorpayPaymentLink> found = client.findByReferenceId("rs-action-999");

        assertTrue(found.isEmpty());
    }
}
