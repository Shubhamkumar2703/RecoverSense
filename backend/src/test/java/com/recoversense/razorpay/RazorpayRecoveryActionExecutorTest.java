package com.recoversense.razorpay;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Phase 8 timeout-reconciliation safety property using a
 * hand-written fake RazorpayPaymentLinkClient - not a real HTTP server, since
 * this class's job is orchestration logic (what to do with the client's
 * answer), already separately proven against real HTTP in
 * HttpRazorpayPaymentLinkClientTest.
 */
class RazorpayRecoveryActionExecutorTest {

    private RecoveryAction seedAction(String actionType, String suffix) {
        Customer customer = new Customer("cust_exec_" + suffix, "user+exec" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        Payment payment = new Payment("pay_exec_" + suffix, customer, new BigDecimal("1000.00"), "INR", PaymentStatus.FAILED);
        RecoveryCase recoveryCase = new RecoveryCase(payment);
        RecoveryDecision decision = new RecoveryDecision(recoveryCase, "MANDATE_INVALID", new BigDecimal("0.9000"), "raw", actionType);
        RecoveryAction action = new RecoveryAction(decision, actionType, PolicyResult.ALLOWED);
        setId(action, 42L);
        return action;
    }

    // Tests construct entities directly (no DB) - id is normally DB-generated,
    // so it's set via reflection here purely to give the deterministic
    // reference_id something stable to derive from.
    private void setId(RecoveryAction action, Long id) {
        try {
            Field field = RecoveryAction.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(action, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * findResponses is consumed one per findByReferenceId() call (queue) so
     * tests can script a sequence of misses followed by a hit - the bounded
     * retry (M1.19) makes call *count*, not just the final answer, part of
     * what these tests must prove. An empty queue yields Optional.empty(),
     * matching the pre-M1.19 default.
     */
    private static final class FakeClient implements RazorpayPaymentLinkClient {
        CreatePaymentLinkRequest lastCreateRequest;
        int createCallCount;
        RuntimeException createThrows;
        RazorpayPaymentLink createReturns;

        RuntimeException findThrows;
        int findCallCount;
        final Deque<Optional<RazorpayPaymentLink>> findResponses = new ArrayDeque<>();

        @Override
        public RazorpayPaymentLink create(CreatePaymentLinkRequest request) {
            createCallCount++;
            lastCreateRequest = request;
            if (createThrows != null) {
                throw createThrows;
            }
            return createReturns;
        }

        @Override
        public RazorpayPaymentLink fetchById(String paymentLinkId) {
            throw new UnsupportedOperationException("not used by executor");
        }

        @Override
        public Optional<RazorpayPaymentLink> findByReferenceId(String referenceId) {
            findCallCount++;
            if (findThrows != null) {
                throw findThrows;
            }
            return findResponses.isEmpty() ? Optional.empty() : findResponses.pollFirst();
        }
    }

    @Test
    void paymentLinkAction_createSucceeds_setsExternalReferenceAndReturnsExecuted() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "1");
        FakeClient client = new FakeClient();
        client.createReturns = new RazorpayPaymentLink("plink_1", "rs-action-42", RazorpayPaymentLinkStatus.CREATED, 100000, 0, "url");
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ExecutionStatus outcome = executor.execute(action);

        assertEquals(ExecutionStatus.EXECUTED, outcome);
        assertEquals("plink_1", action.getExternalReference());
        assertEquals("rs-action-42", client.lastCreateRequest.referenceId());
        assertEquals(100000, client.lastCreateRequest.amountInSmallestUnit());
        assertEquals(1, client.createCallCount);
        assertEquals(0, client.findCallCount, "no ambiguity - reconciliation must never run");
    }

    @Test
    void paymentLinkAction_createRejected_returnsFailed_noReconciliationAttempted() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "2");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderRejectedException("BAD_REQUEST_ERROR", "invalid amount", null);
        client.findThrows = new IllegalStateException("reconciliation must not be attempted for a genuine rejection");
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ExecutionStatus outcome = executor.execute(action);

        assertEquals(ExecutionStatus.FAILED, outcome);
        assertEquals(1, client.createCallCount);
    }

    @Test
    void createTimesOut_reconciliationFindsLinkFirstAttempt_returnsExecuted_caseA() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "3");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("timeout");
        client.findResponses.add(Optional.of(new RazorpayPaymentLink("plink_3", "rs-action-42", RazorpayPaymentLinkStatus.CREATED, 100000, 0, "url")));
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ExecutionStatus outcome = executor.execute(action);

        assertEquals(ExecutionStatus.EXECUTED, outcome);
        assertEquals("plink_3", action.getExternalReference());
        assertEquals(1, client.createCallCount, "create() must be attempted exactly once, ever");
        assertEquals(1, client.findCallCount, "found on the first lookup - no further attempts needed");
    }

    /**
     * M1.19: the resource is not yet visible on the first two reconciliation
     * lookups (a real Razorpay eventual-consistency window) but is found on
     * the third - the bounded retry must not give up early, and it must
     * still never re-issue create().
     */
    @Test
    void createTimesOut_reconciliationFindsLinkOnThirdAttempt_returnsExecuted_eventualConsistency() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "3b");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("timeout");
        RazorpayPaymentLink link = new RazorpayPaymentLink("plink_3b", "rs-action-42", RazorpayPaymentLinkStatus.CREATED, 100000, 0, "url");
        client.findResponses.add(Optional.empty());
        client.findResponses.add(Optional.empty());
        client.findResponses.add(Optional.of(link));
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ExecutionStatus outcome = executor.execute(action);

        assertEquals(ExecutionStatus.EXECUTED, outcome);
        assertEquals("plink_3b", action.getExternalReference());
        assertEquals(1, client.createCallCount, "create() must be attempted exactly once, ever");
        assertEquals(3, client.findCallCount, "must have retried past the first two misses before finding it");
    }

    @Test
    void createTimesOut_reconciliationFindsNothing_throwsUnresolvedAndDoesNotFabricateFailed_caseB() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "4");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("timeout");
        client.findResponses.add(Optional.empty());
        client.findResponses.add(Optional.empty());
        client.findResponses.add(Optional.empty());
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ProviderUnavailableException thrown = assertThrows(ProviderUnavailableException.class, () -> executor.execute(action));

        // Must be the SAME exception type as the original ambiguity - never
        // silently converted into a fabricated FAILED result, and it must
        // still be an UnsupportedOperationException so the existing
        // RecoveryActionExecutionService catch/PENDING/audit path applies.
        assertTrue(thrown instanceof UnsupportedOperationException);
        assertEquals(1, client.createCallCount, "create() must be attempted exactly once, ever");
        assertEquals(3, client.findCallCount, "must exhaust exactly the bounded retry budget, not more, not less");
        assertEquals(ExecutionStatus.PENDING, action.getExecutionStatus(), "unresolved ambiguity must leave the action PENDING, never FAILED");
    }

    /**
     * Reconciliation's own lookup being unavailable (not just "not found") is
     * a different, more certain failure than an eventual-consistency miss -
     * preserved exactly as before M1.19: not retried, original exception
     * carries the lookup failure as a suppressed exception.
     */
    @Test
    void createTimesOut_reconciliationAlsoTimesOut_throwsWithoutRetrying_caseC() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "5");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("create timed out");
        client.findThrows = new ProviderUnavailableException("reconciliation also timed out");
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        assertThrows(ProviderUnavailableException.class, () -> executor.execute(action));
        // Only one create() attempt is expected: the executor must not retry
        // creation after a failed reconciliation.
        assertEquals(1, client.createCallCount);
        // A lookup failure (not a miss) is not retried - unchanged from
        // pre-M1.19 behavior, since it signals Razorpay is unavailable, not
        // that the resource simply isn't visible yet.
        assertEquals(1, client.findCallCount);
    }

    @Test
    void nonPaymentLinkActionType_throwsUnsupportedOperationException_clientNeverCalled() {
        RecoveryAction action = seedAction("RETRY_PAYMENT", "6");
        FakeClient client = new FakeClient();
        client.createThrows = new IllegalStateException("client must not be called for an unimplemented action type");
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        assertThrows(UnsupportedOperationException.class, () -> executor.execute(action));
    }
}
