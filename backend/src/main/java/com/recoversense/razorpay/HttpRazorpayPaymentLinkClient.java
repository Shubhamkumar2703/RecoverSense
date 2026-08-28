package com.recoversense.razorpay;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Real HTTP implementation of {@link RazorpayPaymentLinkClient} against
 * Razorpay's REST API. Owns every provider-specific detail: endpoint paths,
 * request/response JSON shape, HTTP status handling, error-body parsing.
 * Nothing outside this class knows /v1/payment_links exists.
 * <p>
 * Deliberately parses JSON as plain Map&lt;String,Object&gt; rather than
 * annotated DTOs - Razorpay's field names (snake_case) and this adapter's
 * needs are simple enough that hand-mapped Maps avoid a dependency on
 * getting Jackson annotation wiring exactly right for a handful of fields,
 * consistent with "smallest justified change".
 */
public class HttpRazorpayPaymentLinkClient implements RazorpayPaymentLinkClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };
    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> JACKSON_JSON_OBJECT =
            new tools.jackson.core.type.TypeReference<>() {
            };

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public HttpRazorpayPaymentLinkClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public RazorpayPaymentLink create(CreatePaymentLinkRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amountInSmallestUnit());
        body.put("currency", request.currency());
        body.put("reference_id", request.referenceId());
        body.put("description", request.description());
        if (request.customerEmail() != null) {
            body.put("customer", Map.of("email", request.customerEmail()));
        }

        Map<String, Object> response = execute(() -> restClient.post()
                .uri("/payment_links")
                .body(body)
                .retrieve()
                .body(JSON_OBJECT));
        return toDomain(response);
    }

    @Override
    public RazorpayPaymentLink fetchById(String paymentLinkId) {
        Map<String, Object> response = execute(() -> restClient.get()
                .uri("/payment_links/{id}", paymentLinkId)
                .retrieve()
                .body(JSON_OBJECT));
        return toDomain(response);
    }

    @Override
    public Optional<RazorpayPaymentLink> findByReferenceId(String referenceId) {
        Map<String, Object> response = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/payment_links").queryParam("reference_id", referenceId).build())
                .retrieve()
                .body(JSON_OBJECT));

        Object linksRaw = response == null ? null : response.get("payment_links");
        if (!(linksRaw instanceof List<?> links) || links.isEmpty()) {
            return Optional.empty();
        }
        if (links.size() > 1) {
            throw new ProviderUnavailableException(
                    "Razorpay returned " + links.size() + " payment links for reference_id " + referenceId + " - expected at most one");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> onlyMatch = (Map<String, Object>) links.get(0);
        return Optional.of(toDomain(onlyMatch));
    }

    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException clientError) {
            Map<String, Object> errorBody = parseErrorBody(clientError);
            if (isDuplicateReferenceId(errorBody)) {
                throw new ProviderUnavailableException(
                        "Razorpay rejected payment link creation because reference_id was already used - "
                                + "this may mean an earlier ambiguous attempt actually succeeded; reconciliation is required",
                        clientError);
            }
            throw new ProviderRejectedException(
                    String.valueOf(errorBody.get("code")),
                    String.valueOf(errorBody.getOrDefault("description", clientError.getMessage())),
                    clientError);
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

    private Map<String, Object> parseErrorBody(HttpStatusCodeException ex) {
        try {
            String responseBody = ex.getResponseBodyAsString();
            if (responseBody == null || responseBody.isBlank()) {
                return Map.of();
            }
            Map<String, Object> parsed = jsonMapper.readValue(responseBody, JACKSON_JSON_OBJECT);
            Object error = parsed.get("error");
            @SuppressWarnings("unchecked")
            Map<String, Object> errorMap = error instanceof Map<?, ?> ? (Map<String, Object>) error : Map.of();
            return errorMap;
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    private boolean isDuplicateReferenceId(Map<String, Object> errorBody) {
        return "reference_id".equals(errorBody.get("field"));
    }

    private RazorpayPaymentLink toDomain(Map<String, Object> raw) {
        return new RazorpayPaymentLink(
                (String) raw.get("id"),
                (String) raw.get("reference_id"),
                RazorpayPaymentLinkStatus.fromRaw((String) raw.get("status")),
                toLong(raw.get("amount")),
                toLong(raw.get("amount_paid")),
                (String) raw.get("short_url"));
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
