package com.recoversense.settlement;

/**
 * Application-level port for answering one question: has this payment
 * already settled through another channel? Policy/application code depends
 * on this abstraction only - never on a concrete HTTP client or Razorpay
 * SDK type. An implementation must resolve every uncertain/error condition
 * (timeout, network failure, malformed response, provider unavailable, not
 * yet implemented) to {@link SettlementState#UNKNOWN} - never guess SETTLED
 * or NOT_SETTLED.
 * <p>
 * Takes the payment's external identifier only (not the Payment entity)
 * so this port carries no persistence-layer dependency.
 */
public interface SettlementVerifier {

    SettlementState checkSettlement(String externalPaymentId);
}
