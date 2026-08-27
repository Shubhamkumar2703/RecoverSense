package com.recoversense.settlement;

import org.springframework.stereotype.Component;

/**
 * The only wired SettlementVerifier implementation today. No Razorpay
 * HTTP/SDK call exists: docs/RAZORPAY_INTEGRATION.md explicitly states
 * "exact endpoint names, SDK behavior, authentication requirements and
 * test-mode capabilities must be verified against current official
 * Razorpay documentation before implementation" - that verification has
 * not happened, so no request is made rather than guessing at a schema.
 * <p>
 * Always answers UNKNOWN. This is not a placeholder for a future "success"
 * value - UNKNOWN is itself the correct, honest answer while no real
 * settlement source is integrated, and PolicyEngine already fails closed
 * on it.
 */
@Component
class UnavailableSettlementVerifier implements SettlementVerifier {

    @Override
    public SettlementState checkSettlement(String externalPaymentId) {
        return SettlementState.UNKNOWN;
    }
}
