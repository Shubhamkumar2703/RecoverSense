package com.recoversense.settlement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIMULATED settlement verifier. Never calls Razorpay or any external
 * provider - it answers only with states explicitly registered via
 * {@link #seedSettlementState}. Deterministic: the same externalPaymentId
 * always answers the same way until re-seeded, no timing/randomness, and no
 * inference from substrings of the id itself.
 * <p>
 * This is NOT a Spring bean and is never wired into the application context
 * - {@link UnavailableSettlementVerifier} remains the only bean satisfying
 * {@link SettlementVerifier} in every profile. This class can only be
 * reached by code that explicitly constructs it (tests today; a future
 * explicitly-simulation-only tool if one is ever built). That is deliberate:
 * there is no configuration flag to flip that could accidentally activate
 * simulated settlement state in production.
 * <p>
 * Per docs/RAZORPAY_INTEGRATION.md's simulation rule ("every simulator
 * action must be visibly and technically labeled as SIMULATED... must not
 * be represented as a real Razorpay operation"), the class name and this
 * javadoc are that label - nothing here claims or resembles a real
 * Razorpay call.
 */
public final class SimulatedSettlementVerifier implements SettlementVerifier {

    private final Map<String, SettlementState> seededStates = new ConcurrentHashMap<>();

    /**
     * Registers the SIMULATED settlement state this verifier will answer for
     * a given external payment id.
     */
    public SimulatedSettlementVerifier seedSettlementState(String externalPaymentId, SettlementState state) {
        seededStates.put(externalPaymentId, state);
        return this;
    }

    @Override
    public SettlementState checkSettlement(String externalPaymentId) {
        // Not seeded = not configured = unknown. Never a silent NOT_SETTLED guess.
        return seededStates.getOrDefault(externalPaymentId, SettlementState.UNKNOWN);
    }
}
