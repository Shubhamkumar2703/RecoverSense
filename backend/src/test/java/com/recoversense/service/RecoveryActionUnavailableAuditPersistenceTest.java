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
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately NOT @Transactional at the class level: every other test class
 * touching these services wraps the whole test method in one shared
 * transaction, which masks the real production behavior (reads inside that
 * same open transaction see uncommitted writes even after Spring has marked
 * it rollback-only). This class reproduces the real application call
 * boundary - RecoveryActionExecutionService/RecoveryActionVerificationService
 * as the true outermost transaction owner, exactly how
 * RecoveryOrchestrationService.recover() calls them - so the audit query
 * below can only see what was actually committed to the database.
 * <p>
 * Regression coverage for the fix in UnavailableAuditRecorder: before that
 * fix, the "provider unavailable" audit event was saved and then discarded
 * by the same rollback that unwound the UnsupportedOperationException, so
 * both assertions here would fail against the pre-fix code.
 */
@SpringBootTest
class RecoveryActionUnavailableAuditPersistenceTest {

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
    private RecoveryActionExecutionService recoveryActionExecutionService;
    @Autowired
    private RecoveryActionVerificationService recoveryActionVerificationService;

    private RecoveryDecision seedDecision(String suffix) {
        Customer customer = new Customer("cust_audit_" + suffix, "user+audit" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_audit_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        paymentRepository.save(payment);

        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));

        return recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "MANDATE_REVOKED", new BigDecimal("0.9000"), "raw", "RETRY"));
    }

    private void cleanUp(RecoveryDecision decision, RecoveryAction action) {
        auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(decision.getRecoveryCase().getId()))
                .forEach(e -> auditEventRepository.deleteById(e.getId()));
        recoveryActionRepository.deleteById(action.getId());
        recoveryDecisionRepository.deleteById(decision.getId());
        recoveryCaseRepository.deleteById(decision.getRecoveryCase().getId());
        paymentRepository.deleteById(decision.getRecoveryCase().getPayment().getId());
        customerRepository.deleteById(decision.getRecoveryCase().getPayment().getCustomer().getId());
    }

    @Test
    void executionUnavailable_auditEventIsActuallyCommitted_notJustReturnedInMemory() {
        RecoveryDecision decision = seedDecision("exec");
        RecoveryAction action = recoveryActionRepository.save(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED));
        Long caseId = decision.getRecoveryCase().getId();

        assertThrows(UnsupportedOperationException.class,
                () -> recoveryActionExecutionService.attemptExecution(action.getId()));

        // Fresh query, no shared transaction/connection with the call above:
        // can only see what was actually committed.
        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(caseId))
                .toList();
        assertTrue(events.stream().anyMatch(e -> "ACTION_EXECUTION_UNAVAILABLE".equals(e.getEventType())),
                "ACTION_EXECUTION_UNAVAILABLE must survive the rollback of the attemptExecution transaction it was written in");

        cleanUp(decision, action);
    }

    @Test
    void verificationUnavailable_auditEventIsActuallyCommitted_notJustReturnedInMemory() {
        RecoveryDecision decision = seedDecision("verify");
        RecoveryAction action = recoveryActionRepository.save(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED));
        action.setExecutionStatus(ExecutionStatus.EXECUTED);
        action.setExecutedAt(Instant.now());
        recoveryActionRepository.save(action);
        Long caseId = decision.getRecoveryCase().getId();

        assertThrows(UnsupportedOperationException.class,
                () -> recoveryActionVerificationService.attemptVerification(action.getId()));

        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(caseId))
                .toList();
        assertTrue(events.stream().anyMatch(e -> "ACTION_VERIFICATION_UNAVAILABLE".equals(e.getEventType())),
                "ACTION_VERIFICATION_UNAVAILABLE must survive the rollback of the attemptVerification transaction it was written in");

        cleanUp(decision, action);
    }
}
