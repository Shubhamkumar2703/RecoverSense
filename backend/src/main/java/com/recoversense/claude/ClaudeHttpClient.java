package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisUnavailableException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Real HTTP integration against Anthropic's Messages API
 * (POST /v1/messages, confirmed against
 * https://platform.claude.com/docs/en/api/messages and
 * https://platform.claude.com/docs/en/build-with-claude/structured-outputs -
 * see the M1.15 report for what was verified vs. assumed). Owns every
 * Claude-specific detail: endpoint path, header shape, request/response JSON,
 * HTTP status handling. Nothing outside this class (and ClaudeDiagnosisProvider,
 * which owns the diagnosis-specific prompt/schema) knows /v1/messages exists.
 * <p>
 * Uses output_config.format (type: json_schema) rather than free-text
 * generation or tool-use forcing - Claude guarantees the returned text is
 * valid JSON matching the given schema, which is the right mechanism for a
 * closed failure-type taxonomy. The API cannot enforce numeric bounds
 * (confidence 0..1) via schema, so that validation still happens in
 * ClaudeDiagnosisProvider - this client never trusts the response beyond
 * "parses as the expected top-level shape."
 * <p>
 * Every failure mode - HTTP 4xx/5xx, timeout, network failure, malformed/empty
 * response - surfaces as DiagnosisUnavailableException. There is no separate
 * "rejected vs. ambiguous" distinction here (unlike Razorpay's
 * ProviderRejectedException/ProviderUnavailableException split): a diagnosis
 * failure has only one safe outcome - do not proceed - so one exception type
 * is sufficient.
 */
public class ClaudeHttpClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String model;

    public ClaudeHttpClient(RestClient restClient, String model) {
        this.restClient = restClient;
        this.model = model;
    }

    /**
     * Sends a single-turn request constrained to the given JSON schema and
     * returns the response's guaranteed-schema-valid JSON text (still an
     * unparsed String - the caller decides how strictly to trust/validate
     * field values beyond "this is well-formed JSON of the right shape").
     */
    public String createStructuredMessage(String systemPrompt, String userMessage, Map<String, Object> jsonSchema,
                                           int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userMessage)));
        body.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema", jsonSchema)));

        Map<String, Object> response = execute(() -> restClient.post()
                .uri("/messages")
                .body(body)
                .retrieve()
                .body(JSON_OBJECT));

        return extractText(response);
    }

    private String extractText(Map<String, Object> response) {
        Object contentRaw = response == null ? null : response.get("content");
        if (!(contentRaw instanceof List<?> blocks) || blocks.isEmpty()) {
            throw new DiagnosisUnavailableException("Claude response had no content blocks");
        }
        Object first = blocks.get(0);
        if (!(first instanceof Map<?, ?> block) || !"text".equals(block.get("type"))) {
            throw new DiagnosisUnavailableException("Claude response's first content block was not text");
        }
        Object text = block.get("text");
        if (!(text instanceof String textValue) || textValue.isBlank()) {
            throw new DiagnosisUnavailableException("Claude response text was empty");
        }
        return textValue;
    }

    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException clientError) {
            // Covers 401 authentication_error, 429 rate_limit_error, and
            // every other 4xx - all fail closed the same way for diagnosis.
            throw new DiagnosisUnavailableException(
                    "Claude request rejected: " + clientError.getStatusCode(), clientError);
        } catch (HttpServerErrorException serverError) {
            throw new DiagnosisUnavailableException(
                    "Claude server error: " + serverError.getStatusCode(), serverError);
        } catch (ResourceAccessException networkFailure) {
            throw new DiagnosisUnavailableException(
                    "Claude request failed (network/timeout): " + networkFailure.getMessage(), networkFailure);
        } catch (RestClientException unexpected) {
            throw new DiagnosisUnavailableException(
                    "Claude response could not be processed: " + unexpected.getMessage(), unexpected);
        }
    }
}
