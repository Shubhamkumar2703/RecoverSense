package com.recoversense.service;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the V3 partial unique index (recovery_case_id, action_type) WHERE
 * execution_status = 'PENDING' - not RecoveryActionService's own
 * find-before-create check, which is scoped per-decision and cannot see a
 * sibling action created under a different decision on the same case (see
 * RecoveryActionService.createIfAllowed's Javadoc). Each test here inserts
 * directly through the repository under two different decisions belonging
 * to the same case, bypassing the per-decision application check entirely,
 * so only the DB constraint is what's actually being exercised.
 */
@SpringBootTest
@Transactional
class RecoveryActionCaseUniquenessTest {

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private RecoveryDecisionRepository recoveryDecisionRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    private Payment seedFailedPayment(String suffix) {
        Customer customer = new Customer("cust_case_uniq_" + suffix, "user+caseuniq" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_case_uniq_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    private RecoveryDecision seedDecisionForCase(RecoveryCase recoveryCase) {
        return recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "MANDATE_REVOKED", new BigDecimal("0.9000"), "raw", "RETRY"));
    }

    @Test
    void duplicatePendingSameActionType_sameCase_differentDecisions_isRejectedByTheDatabase() {
        Payment payment = seedFailedPayment("a");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        RecoveryDecision decisionA = seedDecisionForCase(recoveryCase);
        RecoveryDecision decisionB = seedDecisionForCase(recoveryCase);

        recoveryActionRepository.saveAndFlush(new RecoveryAction(decisionA, "RETRY_PAYMENT", PolicyResult.ALLOWED));

        assertThrows(DataIntegrityViolationException.class, () ->
                recoveryActionRepository.saveAndFlush(new RecoveryAction(decisionB, "RETRY_PAYMENT", PolicyResult.ALLOWED)));
    }

    @Test
    void differentActionTypes_sameCase_bothAllowed() {
        Payment payment = seedFailedPayment("b");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        RecoveryDecision decisionA = seedDecisionForCase(recoveryCase);
        RecoveryDecision decisionB = seedDecisionForCase(recoveryCase);

        RecoveryAction retry = recoveryActionRepository.saveAndFlush(
                new RecoveryAction(decisionA, "RETRY_PAYMENT", PolicyResult.ALLOWED));
        RecoveryAction paymentLink = recoveryActionRepository.saveAndFlush(
                new RecoveryAction(decisionB, "PAYMENT_LINK", PolicyResult.ALLOWED));

        assertNotEquals(retry.getId(), paymentLink.getId());
    }

    @Test
    void resolvedAction_freesTheSlotForANewPendingActionOfTheSameType() {
        Payment payment = seedFailedPayment("c");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        RecoveryDecision decisionA = seedDecisionForCase(recoveryCase);
        RecoveryDecision decisionB = seedDecisionForCase(recoveryCase);

        RecoveryAction first = recoveryActionRepository.saveAndFlush(
                new RecoveryAction(decisionA, "RETRY_PAYMENT", PolicyResult.ALLOWED));
        first.setExecutionStatus(ExecutionStatus.EXECUTED);
        first.setExecutedAt(Instant.now());
        recoveryActionRepository.saveAndFlush(first);

        RecoveryAction second = recoveryActionRepository.saveAndFlush(
                new RecoveryAction(decisionB, "RETRY_PAYMENT", PolicyResult.ALLOWED));

        assertEquals(ExecutionStatus.PENDING, second.getExecutionStatus());
        List<RecoveryAction> pendingRetryActions = recoveryActionRepository.findAll().stream()
                .filter(a -> a.getRecoveryCase().getId().equals(recoveryCase.getId()))
                .filter(a -> "RETRY_PAYMENT".equals(a.getActionType()))
                .filter(a -> a.getExecutionStatus() == ExecutionStatus.PENDING)
                .toList();
        assertEquals(1, pendingRetryActions.size());
    }

    @Test
    void differentRecoveryCases_remainIndependent() {
        Payment paymentA = seedFailedPayment("d1");
        Payment paymentB = seedFailedPayment("d2");
        RecoveryCase caseA = recoveryCaseRepository.save(new RecoveryCase(paymentA));
        RecoveryCase caseB = recoveryCaseRepository.save(new RecoveryCase(paymentB));
        RecoveryDecision decisionA = seedDecisionForCase(caseA);
        RecoveryDecision decisionB = seedDecisionForCase(caseB);

        RecoveryAction actionA = recoveryActionRepository.saveAndFlush(
                new RecoveryAction(decisionA, "RETRY_PAYMENT", PolicyResult.ALLOWED));
        RecoveryAction actionB = recoveryActionRepository.saveAndFlush(
                new RecoveryAction(decisionB, "RETRY_PAYMENT", PolicyResult.ALLOWED));

        assertNotEquals(actionA.getId(), actionB.getId());
    }
}
