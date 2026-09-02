package com.recoversense.razorpay;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Real HTTP implementation of {@link RazorpayPaymentClient} against
 * Razorpay's REST API (GET /v1/payments, paginated collection response
 * {@code {"entity":"collection","count":N,"items":[...]}}). Field names
 * (status/amount/currency/error_description/created_at/order_id) follow
 * Razorpay's documented Payments entity shape, consistent with how {@link
 * HttpRazorpayPaymentLinkClient} already parses the sibling Payment Links
 * entity - not independently re-verified against live docs in this change
 * (see CLAUDE.md's source-of-truth discipline: label this an IMPLEMENTATION
 * FACT pending real-provider confirmation, exactly like every other Razorpay
 * adapter here was validated by the real-provider tests before being
 * trusted).
 * <p>
 * Deliberately parses JSON as plain Map&lt;String,Object&gt; rather than
 * annotated DTOs, matching {@link HttpRazorpayPaymentLinkClient}'s existing
 * convention.
 */
public class HttpRazorpayPaymentClient implements RazorpayPaymentClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public HttpRazorpayPaymentClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<RazorpayPayment> listRecent(int count) {
        Map<String, Object> response = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/payments").queryParam("count", count).build())
                .retrieve()
                .body(JSON_OBJECT));

        Object itemsRaw = response == null ? null : response.get("items");
        if (!(itemsRaw instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> toDomain((Map<?, ?>) item))
                .toList();
    }

    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException clientError) {
            throw new ProviderUnavailableException(
                    "Razorpay payments request rejected: " + clientError.getStatusCode(), clientError);
        } catch (HttpServerErrorException serverError) {
            throw new ProviderUnavailableException(
                    "Razorpay server error: " + serverError.getStatusCode(), serverError);
        } catch (ResourceAccessException networkFailure) {
            throw new ProviderUnavailableException(
                    "Razorpay request failed (network/timeout): " + networkFailure.getMessage(), networkFailure);
        } catch (RestClientException unexpected) {
            throw new ProviderUnavailableException(
                    "Razorpay response could not be processed: " + unexpected.getMessage(), unexpected);
        }
    }

    private RazorpayPayment toDomain(Map<?, ?> raw) {
        return new RazorpayPayment(
                (String) raw.get("id"),
                (String) raw.get("status"),
                toLong(raw.get("amount")),
                (String) raw.get("currency"),
                (String) raw.get("error_description"),
                toInstant(raw.get("created_at")),
                (String) raw.get("order_id"));
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Instant toInstant(Object epochSeconds) {
        return epochSeconds instanceof Number number ? Instant.ofEpochSecond(number.longValue()) : null;
    }
}
