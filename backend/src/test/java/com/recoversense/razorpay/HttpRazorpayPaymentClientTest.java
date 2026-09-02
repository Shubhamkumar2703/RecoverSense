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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.26: same real-HTTP-server test pattern as HttpRazorpayPaymentLinkClientTest
 * (no mocked RestClient) - proves the actual GET /v1/payments response
 * mapping and failure handling.
 */
class HttpRazorpayPaymentClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpRazorpayPaymentClient clientReturning(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/payments", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .build();
        return new HttpRazorpayPaymentClient(restClient);
    }

    @Test
    void listRecent_success_mapsEveryField() throws IOException {
        HttpRazorpayPaymentClient client = clientReturning(200, """
                {"entity":"collection","count":1,"items":[
                    {"id":"pay_ABC123","status":"failed","amount":250000,"currency":"INR",
                     "error_description":"card declined","created_at":1735689600,"order_id":"order_XYZ"}
                ]}
                """);

        List<RazorpayPayment> payments = client.listRecent(20);

        assertEquals(1, payments.size());
        RazorpayPayment payment = payments.get(0);
        assertEquals("pay_ABC123", payment.id());
        assertEquals("failed", payment.status());
        assertEquals(250000, payment.amountInSmallestUnit());
        assertEquals("INR", payment.currency());
        assertEquals("card declined", payment.errorDescription());
        assertEquals("order_XYZ", payment.orderId());
        assertTrue(payment.createdAt() != null);
    }

    @Test
    void listRecent_mixedStatuses_returnsAllOfThem() throws IOException {
        HttpRazorpayPaymentClient client = clientReturning(200, """
                {"entity":"collection","count":2,"items":[
                    {"id":"pay_1","status":"captured","amount":1000,"currency":"INR"},
                    {"id":"pay_2","status":"failed","amount":2000,"currency":"INR"}
                ]}
                """);

        List<RazorpayPayment> payments = client.listRecent(20);

        assertEquals(2, payments.size());
    }

    @Test
    void listRecent_noItemsKey_returnsEmptyList() throws IOException {
        HttpRazorpayPaymentClient client = clientReturning(200, "{}");

        List<RazorpayPayment> payments = client.listRecent(20);

        assertTrue(payments.isEmpty());
    }

    @Test
    void listRecent_5xx_throwsProviderUnavailable() throws IOException {
        HttpRazorpayPaymentClient client = clientReturning(500, """
                {"error":{"code":"SERVER_ERROR","description":"trouble completing your request"}}
                """);

        assertThrows(ProviderUnavailableException.class, () -> client.listRecent(20));
    }

    @Test
    void listRecent_malformedBody_throwsProviderUnavailable() throws IOException {
        HttpRazorpayPaymentClient client = clientReturning(200, "not json at all {{{");

        assertThrows(ProviderUnavailableException.class, () -> client.listRecent(20));
    }
}
