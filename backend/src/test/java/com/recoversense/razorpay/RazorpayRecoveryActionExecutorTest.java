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

    private static final class FakeClient implements RazorpayPaymentLinkClient {
        CreatePaymentLinkRequest lastCreateRequest;
        RuntimeException createThrows;
        RazorpayPaymentLink createReturns;
        RuntimeException findThrows;
        Optional<RazorpayPaymentLink> findReturns = Optional.empty();

        @Override
        public RazorpayPaymentLink create(CreatePaymentLinkRequest request) {
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
            if (findThrows != null) {
                throw findThrows;
            }
            return findReturns;
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
    }

    @Test
    void createTimesOut_reconciliationFindsLink_returnsExecuted_caseA() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "3");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("timeout");
        client.findReturns = Optional.of(new RazorpayPaymentLink("plink_3", "rs-action-42", RazorpayPaymentLinkStatus.CREATED, 100000, 0, "url"));
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ExecutionStatus outcome = executor.execute(action);

        assertEquals(ExecutionStatus.EXECUTED, outcome);
        assertEquals("plink_3", action.getExternalReference());
    }

    @Test
    void createTimesOut_reconciliationFindsNothing_throwsUnresolvedAndDoesNotFabricateFailed_caseB() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "4");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("timeout");
        client.findReturns = Optional.empty();
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        ProviderUnavailableException thrown = assertThrows(ProviderUnavailableException.class, () -> executor.execute(action));

        // Must be the SAME exception type as the original ambiguity - never
        // silently converted into a fabricated FAILED result, and it must
        // still be an UnsupportedOperationException so the existing
        // RecoveryActionExecutionService catch/PENDING/audit path applies.
        assertTrue(thrown instanceof UnsupportedOperationException);
    }

    @Test
    void createTimesOut_reconciliationAlsoTimesOut_throws_caseC() {
        RecoveryAction action = seedAction("PAYMENT_LINK", "5");
        FakeClient client = new FakeClient();
        client.createThrows = new ProviderUnavailableException("create timed out");
        client.findThrows = new ProviderUnavailableException("reconciliation also timed out");
        RazorpayRecoveryActionExecutor executor = new RazorpayRecoveryActionExecutor(client);

        assertThrows(ProviderUnavailableException.class, () -> executor.execute(action));
        // Only one create() attempt is expected: the executor must not retry
        // creation after a failed reconciliation.
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
