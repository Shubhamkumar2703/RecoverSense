package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RecoveryLifecycleServiceTest {

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private RecoveryLifecycleService recoveryLifecycleService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    private Payment seedFailedPayment(String suffix) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    private RecoveryDiagnosisInput diagnosisInput() {
        return new RecoveryDiagnosisInput("MANDATE_REVOKED", new BigDecimal("0.9000"), "raw diagnosis text", "RETRY");
    }

    @Test
    void firstProcessing_createsExactlyOneRecoveryCase() {
        Payment payment = seedFailedPayment("1");

        RecoveryLifecycleResult result = recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput());

        assertNotNull(result.recoveryCase().getId());
        assertEquals(RecoveryCaseStatus.OPEN, result.recoveryCase().getStatus());
        List<RecoveryCase> cases = recoveryCaseRepository.findAll().stream()
                .filter(c -> c.getPayment().getId().equals(payment.getId()))
                .toList();
        assertEquals(1, cases.size());
    }

    @Test
    void repeatedProcessing_reusesTheSameOpenCase() {
        Payment payment = seedFailedPayment("2");

        RecoveryLifecycleResult first = recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput());
        RecoveryLifecycleResult second = recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput());

        assertEquals(first.recoveryCase().getId(), second.recoveryCase().getId());
        List<RecoveryCase> openCases = recoveryCaseRepository.findAll().stream()
                .filter(c -> c.getPayment().getId().equals(payment.getId()) && c.getStatus() == RecoveryCaseStatus.OPEN)
                .toList();
        assertEquals(1, openCases.size());
    }

    @Test
    void duplicateOpenCaseInsert_isRejectedByTheDatabaseConstraint() {
        Payment payment = seedFailedPayment("3");
        recoveryCaseRepository.saveAndFlush(new RecoveryCase(payment));

        assertThrows(DataIntegrityViolationException.class,
                () -> recoveryCaseRepository.saveAndFlush(new RecoveryCase(payment)));
    }

    @Test
    void validTransition_openToRecovered_succeedsAndSetsClosedAt() {
        Payment payment = seedFailedPayment("4");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));

        RecoveryCase updated = recoveryLifecycleService.transitionCase(recoveryCase.getId(), RecoveryCaseStatus.RECOVERED);

        assertEquals(RecoveryCaseStatus.RECOVERED, updated.getStatus());
        assertNotNull(updated.getClosedAt());
    }

    @Test
    void invalidTransition_terminalToAnything_isRejected() {
        Payment payment = seedFailedPayment("5");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        recoveryLifecycleService.transitionCase(recoveryCase.getId(), RecoveryCaseStatus.CLOSED);

        assertThrows(InvalidCaseTransitionException.class,
                () -> recoveryLifecycleService.transitionCase(recoveryCase.getId(), RecoveryCaseStatus.RECOVERED));
    }

    @Test
    void invalidTransition_selfTransition_isRejected() {
        Payment payment = seedFailedPayment("6");
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));

        assertThrows(InvalidCaseTransitionException.class,
                () -> recoveryLifecycleService.transitionCase(recoveryCase.getId(), RecoveryCaseStatus.OPEN));
    }

    @Test
    void processFailedPayment_neverCreatesARecoveryAction() {
        Payment payment = seedFailedPayment("7");

        recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput());

        assertEquals(0, recoveryActionRepository.count());
    }

    @Test
    void policyEvaluationIsDelegated_andUnknownSettlementFailsClosed() {
        Payment payment = seedFailedPayment("8");

        RecoveryLifecycleResult result = recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput());
        PolicyDecision decision = result.policyDecision();

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertEquals(7, decision.checks().size());
        assertTrue(decision.failedReasons().stream().anyMatch(r -> r.contains("settlement")));
    }

    @Test
    void lifecycleAndPolicyAuditEventsArePersisted() {
        Payment payment = seedFailedPayment("9");

        RecoveryLifecycleResult result = recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput());

        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(result.recoveryCase().getId()))
                .toList();
        Set<String> eventTypes = events.stream().map(AuditEvent::getEventType).collect(Collectors.toSet());
        assertTrue(eventTypes.contains("RECOVERY_CASE_OPENED"));
        assertTrue(eventTypes.contains("RECOVERY_DECISION_RECORDED"));
        assertTrue(eventTypes.contains("POLICY_EVALUATED"));
    }

    @Test
    void nonFailedPayment_isRejected() {
        Customer customer = new Customer("cust_10", "user10@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_10", customer, new BigDecimal("1000"), "INR", PaymentStatus.SUCCEEDED);
        paymentRepository.save(payment);

        assertThrows(InvalidPaymentStateException.class,
                () -> recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput()));
    }

    @Test
    void unknownPayment_isRejected() {
        assertThrows(PaymentNotFoundException.class,
                () -> recoveryLifecycleService.processFailedPayment(-1L, diagnosisInput()));
    }
}
