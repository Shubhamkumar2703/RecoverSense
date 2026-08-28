package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisContext;
import com.recoversense.diagnosis.DiagnosisProvider;
import com.recoversense.diagnosis.DiagnosisSource;
import com.recoversense.diagnosis.DiagnosisUnavailableException;
import com.recoversense.diagnosis.RecoveryDiagnosis;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Real Claude-backed DiagnosisProvider. Only wired up when claude.api-key is
 * configured - see ClaudeAutoConfiguration.
 * <p>
 * Claude's response is untrusted input, exactly like any external provider
 * response (compare HttpRazorpayPaymentLinkClient): this class validates
 * failureType against the closed taxonomy, confidence against the 0..1
 * range, and requires non-blank reasoning, bounded in length so an unbounded
 * model response can never become an unbounded audit payload. Any validation
 * failure - or any ClaudeHttpClient failure - surfaces as
 * DiagnosisUnavailableException; nothing here ever fabricates a plausible
 * result to paper over a Claude failure.
 * <p>
 * Never returns a strategy: only failureType/confidence/reasoning. Strategy
 * selection is StrategyRouter's job alone (see DiagnosisService) - even if
 * a future prompt change caused Claude to echo a "suggested strategy" in its
 * text, this class has no schema field to carry it and would drop it.
 */
public class ClaudeDiagnosisProvider implements DiagnosisProvider {

    // Bounds how much of Claude's reasoning ends up in diagnosis_raw / the
    // RECOVERY_DECISION_RECORDED audit payload - an unbounded model response
    // must never become an unbounded DB/audit payload (Phase 4).
    private static final int MAX_REASONING_LENGTH = 1000;
    private static final int MAX_TOKENS = 512;

    private final ClaudeHttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ClaudeDiagnosisProvider(ClaudeHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public RecoveryDiagnosis diagnose(DiagnosisContext context) {
        String rawJson = httpClient.createStructuredMessage(
                ClaudeDiagnosisPrompt.SYSTEM_PROMPT,
                ClaudeDiagnosisPrompt.userMessageFor(context),
                ClaudeDiagnosisPrompt.RESPONSE_SCHEMA,
                MAX_TOKENS);
        return validate(parse(rawJson));
    }

    private Map<String, Object> parse(String rawJson) {
        try {
            return jsonMapper.readValue(rawJson, MAP_TYPE);
        } catch (RuntimeException malformed) {
            throw new DiagnosisUnavailableException("Claude diagnosis response was not valid JSON", malformed);
        }
    }

    private RecoveryDiagnosis validate(Map<String, Object> parsed) {
        String failureType = validateFailureType(parsed.get("failureType"));
        BigDecimal confidence = validateConfidence(parsed.get("confidence"));
        String reasoning = validateReasoning(parsed.get("reasoning"));
        return new RecoveryDiagnosis(failureType, confidence, reasoning, DiagnosisSource.CLAUDE);
    }

    private String validateFailureType(Object raw) {
        if (!(raw instanceof String failureType) || !ClaudeDiagnosisPrompt.SUPPORTED_FAILURE_TYPES.contains(failureType)) {
            throw new DiagnosisUnavailableException("Claude returned an unsupported or missing failureType: " + raw);
        }
        return failureType;
    }

    private BigDecimal validateConfidence(Object raw) {
        if (!(raw instanceof Number number)) {
            throw new DiagnosisUnavailableException("Claude diagnosis response was missing a numeric confidence");
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new DiagnosisUnavailableException("Claude diagnosis response returned a non-finite confidence");
        }
        BigDecimal confidence = BigDecimal.valueOf(value);
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new DiagnosisUnavailableException(
                    "Claude diagnosis response returned confidence outside [0,1]: " + confidence);
        }
        return confidence;
    }

    private String validateReasoning(Object raw) {
        if (!(raw instanceof String reasoning) || reasoning.isBlank()) {
            throw new DiagnosisUnavailableException("Claude diagnosis response was missing reasoning");
        }
        return reasoning.length() > MAX_REASONING_LENGTH ? reasoning.substring(0, MAX_REASONING_LENGTH) : reasoning;
    }

    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new tools.jackson.core.type.TypeReference<>() {
            };
}
