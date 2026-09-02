package com.recoversense.recovery;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryVerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.28 regression coverage for the LazyInitializationException on
 * RecoveryResponse.fromVerification's decision.getDiagnosisCategory().
 * <p>
 * Deliberately NOT @Transactional at the class level (see
 * RecoveryActionUnavailableAuditPersistenceTest for the established pattern
 * this follows): every other verify()-touching test wraps the whole test
 * method in one shared transaction/session, which hides the real production
 * behavior - RecoveryController.verify() calls the real, Spring-managed
 * RecoveryOrchestrationService.verify() (never @Transactional itself, by
 * design - each phase owns its own transaction) and then builds the response
 * strictly after that call returns, with no session left open. This class
 * reproduces exactly that call boundary.
 * <p>
 * Both scenarios below use the real (Spring-managed) RecoveryOrchestrationService
 * bean and the environment's default NotImplementedRecoveryActionVerifier (no
 * Razorpay credentials configured in tests) - they seed the RecoveryAction
 * directly into the state verify() would find it in, rather than trying to
 * drive a real EXECUTED/VERIFIED transition through recover(), which isn't
 * reachable without a real provider wired up.
 */
@SpringBootTest
class RecoveryVerificationResponseLazyLoadingTest {

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
    private RecoveryOrchestrationService recoveryOrchestrationService;

    private final java.util.List<RecoveryCase> seededCases = new java.util.ArrayList<>();

    // Deliberately NOT @Transactional (see class Javadoc) - each seeded row is
    // committed for real, so it must be cleaned up for real too, or a re-run
    // against the same shared dev database fails on the customers/payments
    // unique constraints (same pattern as RecoveryActionUnavailableAuditPersistenceTest).
    @AfterEach
    void cleanUp() {
        for (RecoveryCase recoveryCase : seededCases) {
            auditEventRepository.findAll().stream()
                    .filter(e -> e.getRecoveryCase().getId().equals(recoveryCase.getId()))
                    .forEach(e -> auditEventRepository.deleteById(e.getId()));
            recoveryActionRepository.findTopByRecoveryCaseOrderByIdDesc(recoveryCase)
                    .ifPresent(action -> recoveryActionRepository.deleteById(action.getId()));
            recoveryDecisionRepository.findAll().stream()
                    .filter(d -> d.getRecoveryCase().getId().equals(recoveryCase.getId()))
                    .forEach(d -> recoveryDecisionRepository.deleteById(d.getId()));
            recoveryCaseRepository.deleteById(recoveryCase.getId());
            paymentRepository.deleteById(recoveryCase.getPayment().getId());
            customerRepository.deleteById(recoveryCase.getPayment().getCustomer().getId());
        }
        seededCases.clear();
    }

    private RecoveryCase seedCase(String suffix, String diagnosisCategory) {
        Customer customer = new Customer("cust_lazy_" + suffix, "user+lazy" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_lazy_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        paymentRepository.save(payment);

        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        seededCases.add(recoveryCase);
        return recoveryCase;
    }

    private RecoveryAction seedExecutedAction(RecoveryCase recoveryCase, String diagnosisCategory, VerificationStatus verificationStatus) {
        RecoveryDecision decision = recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, diagnosisCategory, new BigDecimal("0.9000"), "raw", "PAYMENT_LINK"));
        RecoveryAction action = new RecoveryAction(decision, "PAYMENT_LINK", PolicyResult.ALLOWED);
        action.setExecutionStatus(ExecutionStatus.EXECUTED);
        action.setVerificationStatus(verificationStatus);
        return recoveryActionRepository.save(action);
    }

    /**
     * The exact scenario reported in M1.28: a case whose verification already
     * succeeded (VERIFIED/RECOVERED persisted by an earlier verify() call)
     * hits RecoveryOrchestrationService.verify()'s idempotent short-circuit
     * (RecoveryOrchestrationService.java ~116-118), which returns the action
     * fetched fresh by RecoveryActionRepository.findTopByRecoveryCaseOrderByIdDesc
     * with no other lazy-touching step in between - before the M1.28 fix,
     * this action's RecoveryDecision was never initialized, so building the
     * response after verify() returns threw LazyInitializationException.
     */
    @Test
    void repeatedVerifyOfAlreadyVerifiedCase_responseBuildsWithoutLazyInitializationException() {
        RecoveryCase recoveryCase = seedCase("verified", "MANDATE_REVOKED");
        seedExecutedAction(recoveryCase, "MANDATE_REVOKED", VerificationStatus.VERIFIED);
        recoveryCase.setStatus(RecoveryCaseStatus.RECOVERED);
        recoveryCaseRepository.save(recoveryCase);

        RecoveryVerificationResult result = recoveryOrchestrationService.verify(recoveryCase.getId());

        RecoveryResponse response = assertDoesNotThrow(
                () -> RecoveryResponse.fromVerification(recoveryCase.getPayment().getId(), result),
                "building the verify() response must not require an open Hibernate session");

        assertEquals("MANDATE_REVOKED", response.diagnosisCategory());
        assertEquals("VERIFIED", response.verificationStatus());
        assertEquals("RECOVERED", response.caseStatus());
        assertEquals("RECOVERED", response.outcome());
    }

    /**
     * The other branch that reads the same untouched, freshly-fetched
     * RecoveryAction directly (RecoveryOrchestrationService.java ~123-124):
     * no real provider is configured in this test context, so verifying an
     * EXECUTED-but-not-yet-VERIFIED action hits VERIFICATION_UNAVAILABLE.
     * Same fix, same regression risk, different outcome branch.
     */
    @Test
    void verifyWithNoProviderConfigured_responseBuildsWithoutLazyInitializationException() {
        RecoveryCase recoveryCase = seedCase("unavailable", "CARD_DECLINED");
        seedExecutedAction(recoveryCase, "CARD_DECLINED", VerificationStatus.UNVERIFIED);

        RecoveryVerificationResult result = recoveryOrchestrationService.verify(recoveryCase.getId());

        RecoveryResponse response = assertDoesNotThrow(
                () -> RecoveryResponse.fromVerification(recoveryCase.getPayment().getId(), result),
                "building the verify() response must not require an open Hibernate session");

        assertEquals("CARD_DECLINED", response.diagnosisCategory());
        assertEquals("UNVERIFIED", response.verificationStatus());
        assertEquals("OPEN", response.caseStatus());
        assertEquals("VERIFICATION_UNAVAILABLE", response.outcome());
    }
}
