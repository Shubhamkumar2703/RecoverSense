package com.recoversense.demo;

import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SettlementVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M1.25: SIMULATED demo-only settlement evidence for exactly one deterministic
 * demo payment ({@link #DEMO_PAYMENT_LINK_EXTERNAL_ID}). Every other payment
 * id still answers UNKNOWN, identically to {@link com.recoversense.settlement.UnavailableSettlementVerifier}.
 * <p>
 * Why this exists: PolicyEngine's not_already_settled_elsewhere check (P06)
 * asks whether a payment has settled elsewhere - a question the Razorpay
 * Payment Link API cannot itself answer (see docs/RAZORPAY_INTEGRATION.md).
 * With no real settlement source wired, {@code UnavailableSettlementVerifier}
 * honestly answers UNKNOWN, and PolicyEngine fails closed to BLOCKED - correct
 * production behavior, but it means P06 can never pass in a normally-running
 * instance, so the real Razorpay Payment Link demo path can never reach
 * execution at all. This bean supplies one explicit, clearly-labeled
 * SIMULATED answer (NOT_SETTLED) so that one demo payment's policy
 * evaluation can legitimately reach ALLOWED, while every other payment -
 * demo profile or not - remains governed by the same fail-closed rule as
 * production.
 * <p>
 * {@code @Profile("demo")}: never active outside the demo profile, so
 * default/production boot is completely unaffected - {@code
 * UnavailableSettlementVerifier} remains the only bean in every other
 * profile. {@code @Primary} resolves the two-candidate-bean ambiguity that
 * would otherwise exist ONLY under the demo profile (both this bean and the
 * unconditional {@code UnavailableSettlementVerifier} would be present at
 * once) - it does not touch, condition, or replace
 * {@code UnavailableSettlementVerifier}'s own file in any way.
 * <p>
 * This class performs no provider call whatsoever and must never be
 * mistaken for real Razorpay settlement truth - the financial action itself
 * (Payment Link creation, hosted checkout, independent re-fetch and
 * verification) remains entirely real Razorpay Test Mode; only this one P06
 * input is simulated. See README.md's Real vs Simulated table and
 * docs/RAZORPAY_INTEGRATION.md.
 * <p>
 * M1.26: the whitelist is extensible via {@code demo.settlement.extra-not-settled-payment-ids}
 * (comma-separated external payment ids, empty by default) so an operator
 * running a real Razorpay Test Mode batch can explicitly designate one of
 * their own real synced payments as the demo's simulated-settlement success
 * case, instead of only ever the one hardcoded seeded id. This is still an
 * explicit, per-id opt-in - never automatic, never applied to arbitrary
 * payments, and never active outside the demo profile.
 */
@Component
@Profile("demo")
@Primary
public class DemoSettlementVerifier implements SettlementVerifier {

    public static final String DEMO_PAYMENT_LINK_EXTERNAL_ID = "pay_demo_payment_link";

    private final Set<String> notSettledPaymentIds;

    public DemoSettlementVerifier(
            @Value("${demo.settlement.extra-not-settled-payment-ids:}") String extraNotSettledPaymentIds) {
        this.notSettledPaymentIds = Arrays.stream(extraNotSettledPaymentIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .collect(Collectors.collectingAndThen(Collectors.toSet(), extras -> {
                    extras.add(DEMO_PAYMENT_LINK_EXTERNAL_ID);
                    return Set.copyOf(extras);
                }));
    }

    @Override
    public SettlementState checkSettlement(String externalPaymentId) {
        if (notSettledPaymentIds.contains(externalPaymentId)) {
            return SettlementState.NOT_SETTLED;
        }
        return SettlementState.UNKNOWN;
    }
}
