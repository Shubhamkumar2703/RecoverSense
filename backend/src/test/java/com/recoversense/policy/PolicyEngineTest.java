package com.recoversense.policy;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine();
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant FAILED_AT = NOW.minusSeconds(600); // 10 minutes before NOW, past the 5-minute window

    private Customer activeCustomer() {
        Customer customer = new Customer("cust_1", "customer@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        return customer;
    }

    private Payment validPayment(Customer customer, BigDecimal amount) {
        Payment payment = new Payment("pay_1", customer, amount, "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailedAt(FAILED_AT);
        return payment;
    }

    private PolicyContext allPassingContext() {
        Customer customer = activeCustomer();
        Payment payment = validPayment(customer, new BigDecimal("1000"));
        return new PolicyContext(customer, payment, 2, false, false, NOW);
    }

    @Test
    void allChecksPassing_resultsInAllow() {
        PolicyDecision decision = engine.evaluate(allPassingContext());

        assertEquals(PolicyResult.ALLOWED, decision.result());
        assertEquals(7, decision.checks().size());
        assertTrue(decision.checks().stream().allMatch(PolicyCheckResult::passed));
        assertTrue(decision.failedReasons().isEmpty());
    }

    @Test
    void retryLimitExceeded_blocks() {
        PolicyContext base = allPassingContext();
        PolicyContext context = new PolicyContext(base.customer(), base.payment(), 3, false, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "retry_limit_not_exceeded");
    }

    @Test
    void retryCountBoundary_twoAllowed_threeBlocked() {
        PolicyContext base = allPassingContext();

        PolicyDecision atTwo = engine.evaluate(new PolicyContext(base.customer(), base.payment(), 2, false, false, NOW));
        PolicyDecision atThree = engine.evaluate(new PolicyContext(base.customer(), base.payment(), 3, false, false, NOW));

        assertEquals(PolicyResult.ALLOWED, atTwo.result());
        assertEquals(PolicyResult.BLOCKED, atThree.result());
    }

    @Test
    void subscriptionNotActive_blocks() {
        Customer customer = activeCustomer();
        Payment payment = validPayment(customer, new BigDecimal("1000"));
        payment.setSubscriptionStatus("cancelled");
        PolicyContext context = new PolicyContext(customer, payment, 2, false, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "subscription_state_valid");
    }

    @Test
    void customerInactive_blocks() {
        Customer customer = activeCustomer();
        customer.setStatus(CustomerStatus.INACTIVE);
        Payment payment = validPayment(customer, new BigDecimal("1000"));
        PolicyContext context = new PolicyContext(customer, payment, 2, false, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "customer_active");
    }

    @Test
    void pendingReacquisitionExists_blocks() {
        PolicyContext base = allPassingContext();
        PolicyContext context = new PolicyContext(base.customer(), base.payment(), 2, true, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "no_pending_reacquisition");
    }

    @Test
    void amountOverLimit_blocks() {
        Customer customer = activeCustomer();
        Payment payment = validPayment(customer, new BigDecimal("50000.01"));
        PolicyContext context = new PolicyContext(customer, payment, 2, false, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "amount_within_policy");
    }

    @Test
    void amountBoundary_exactlyFiftyThousandAllowed_aboveBlocked() {
        Customer customer = activeCustomer();

        Payment atLimit = validPayment(customer, new BigDecimal("50000"));
        PolicyDecision atLimitDecision = engine.evaluate(new PolicyContext(customer, atLimit, 2, false, false, NOW));

        Payment overLimit = validPayment(customer, new BigDecimal("50000.01"));
        PolicyDecision overLimitDecision = engine.evaluate(new PolicyContext(customer, overLimit, 2, false, false, NOW));

        assertEquals(PolicyResult.ALLOWED, atLimitDecision.result());
        assertEquals(PolicyResult.BLOCKED, overLimitDecision.result());
    }

    @Test
    void alreadySettledElsewhere_blocks() {
        PolicyContext base = allPassingContext();
        PolicyContext context = new PolicyContext(base.customer(), base.payment(), 2, false, true, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "not_already_settled_elsewhere");
    }

    @Test
    void webhookFreshnessWindowNotElapsed_blocks() {
        Customer customer = activeCustomer();
        Payment payment = validPayment(customer, new BigDecimal("1000"));
        payment.setFailedAt(NOW.minusSeconds(60)); // only 1 minute elapsed, window is 5 minutes
        PolicyContext context = new PolicyContext(customer, payment, 2, false, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "webhook_delay_window_respected");
    }

    @Test
    void multipleFailures_blocksAndReportsAllReasons() {
        Customer customer = activeCustomer();
        customer.setStatus(CustomerStatus.INACTIVE);
        Payment payment = validPayment(customer, new BigDecimal("999999"));
        payment.setSubscriptionStatus("cancelled");
        payment.setFailedAt(NOW); // 0 elapsed, breaks the webhook freshness window too
        PolicyContext context = new PolicyContext(customer, payment, 5, true, true, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertEquals(7, decision.failedReasons().size());
        assertFailed(decision, "retry_limit_not_exceeded");
        assertFailed(decision, "subscription_state_valid");
        assertFailed(decision, "customer_active");
        assertFailed(decision, "no_pending_reacquisition");
        assertFailed(decision, "amount_within_policy");
        assertFailed(decision, "not_already_settled_elsewhere");
        assertFailed(decision, "webhook_delay_window_respected");
    }

    @Test
    void missingRequiredState_failsClosed() {
        // No customer, no payment, no derived flags, no evaluation clock: everything unknown.
        PolicyContext context = new PolicyContext(null, null, null, null, null, null);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertEquals(7, decision.failedReasons().size());
    }

    @Test
    void unknownRetryCount_failsClosedRatherThanTreatedAsZero() {
        PolicyContext base = allPassingContext();
        PolicyContext context = new PolicyContext(base.customer(), base.payment(), null, false, false, NOW);

        PolicyDecision decision = engine.evaluate(context);

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "retry_limit_not_exceeded");
    }

    private void assertFailed(PolicyDecision decision, String checkName) {
        boolean failed = decision.checks().stream()
                .anyMatch(check -> check.checkName().equals(checkName) && !check.passed());
        assertTrue(failed, "expected check to fail: " + checkName);
    }
}
