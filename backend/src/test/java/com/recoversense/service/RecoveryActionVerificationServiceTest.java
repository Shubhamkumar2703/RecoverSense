package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RecoveryActionVerificationServiceTest {

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
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private RecoveryActionVerificationService recoveryActionVerificationService;

    private RecoveryDecision seedDecision(String suffix) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        paymentRepository.save(payment);

        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));

        return recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "MANDATE_REVOKED", new BigDecimal("0.9000"), "raw", "RETRY"));
    }

    private RecoveryAction seedExecutedAction(String suffix) {
        RecoveryDecision decision = seedDecision(suffix);
        RecoveryAction action = recoveryActionRepository.save(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED));
        action.setExecutionStatus(ExecutionStatus.EXECUTED);
        action.setExecutedAt(Instant.now());
        return recoveryActionRepository.save(action);
    }

    private RecoveryAction seedPendingAction(String suffix) {
        RecoveryDecision decision = seedDecision(suffix);
        return recoveryActionRepository.save(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED));
    }

    @Test
    void realVerifierIsNotImplemented_throwsAndLeavesActionUntouched() {
        RecoveryAction action = seedExecutedAction("1");

        assertThrows(UnsupportedOperationException.class,
                () -> recoveryActionVerificationService.attemptVerification(action.getId()));

        RecoveryAction reloaded = recoveryActionRepository.findById(action.getId()).orElseThrow();
        assertEquals(VerificationStatus.UNVERIFIED, reloaded.getVerificationStatus());
        assertNull(reloaded.getVerifiedAt());

        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(action.getRecoveryDecision().getRecoveryCase().getId()))
                .toList();
        assertTrue(events.stream().anyMatch(e -> "ACTION_VERIFICATION_UNAVAILABLE".equals(e.getEventType())));
    }

    @Test
    void fakeVerifierSuccess_transitionsToVerifiedAndSetsTimestamp() {
        RecoveryAction action = seedExecutedAction("2");
        RecoveryActionVerificationService serviceWithFake = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, a -> VerificationStatus.VERIFIED);

        RecoveryAction result = serviceWithFake.attemptVerification(action.getId());

        assertEquals(VerificationStatus.VERIFIED, result.getVerificationStatus());
        assertNotNull(result.getVerifiedAt());
    }

    @Test
    void pendingAction_cannotBeVerified() {
        RecoveryAction action = seedPendingAction("3");

        assertThrows(InvalidActionTransitionException.class,
                () -> recoveryActionVerificationService.attemptVerification(action.getId()));
    }

    /**
     * M1.26: FAILED is not terminal - see the class javadoc. Re-verifying a
     * FAILED action is allowed and, when the provider now confirms it,
     * genuinely transitions to VERIFIED.
     */
    @Test
    void failedAction_canBeReVerifiedAndSucceed() {
        RecoveryAction action = seedExecutedAction("5");
        RecoveryActionVerificationService failingThenSucceeding = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, a -> VerificationStatus.FAILED);
        failingThenSucceeding.attemptVerification(action.getId());
        assertEquals(VerificationStatus.FAILED, recoveryActionRepository.findById(action.getId()).orElseThrow().getVerificationStatus());

        RecoveryActionVerificationService nowSucceeding = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, a -> VerificationStatus.VERIFIED);
        RecoveryAction result = nowSucceeding.attemptVerification(action.getId());

        assertEquals(VerificationStatus.VERIFIED, result.getVerificationStatus());
    }

    @Test
    void alreadyVerifiedAction_cannotBeReVerified() {
        RecoveryAction action = seedExecutedAction("4");
        RecoveryActionVerificationService serviceWithFake = new RecoveryActionVerificationService(
                recoveryActionRepository, auditEventRepository, a -> VerificationStatus.VERIFIED);
        serviceWithFake.attemptVerification(action.getId());

        assertThrows(InvalidActionTransitionException.class,
                () -> serviceWithFake.attemptVerification(action.getId()));
    }

    @Test
    void unknownAction_isRejected() {
        assertThrows(RecoveryActionNotFoundException.class,
                () -> recoveryActionVerificationService.attemptVerification(-1L));
    }
}
