package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.repository.AuditEventRepository;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RecoveryActionServiceTest {

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
    private RecoveryActionService recoveryActionService;

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

    private PolicyDecision allowed() {
        return new PolicyDecision(PolicyResult.ALLOWED, List.of());
    }

    private PolicyDecision blocked() {
        return new PolicyDecision(PolicyResult.BLOCKED, List.of());
    }

    @Test
    void allowedPolicy_createsPendingAction() {
        RecoveryDecision decision = seedDecision("1");

        Optional<RecoveryAction> result = recoveryActionService.createIfAllowed(decision, "RETRY_PAYMENT", allowed());

        assertTrue(result.isPresent());
        assertEquals(com.recoversense.domain.ExecutionStatus.PENDING, result.get().getExecutionStatus());
        assertEquals(PolicyResult.ALLOWED, result.get().getPolicyResult());
    }

    @Test
    void blockedPolicy_createsNoAction() {
        RecoveryDecision decision = seedDecision("2");

        Optional<RecoveryAction> result = recoveryActionService.createIfAllowed(decision, "RETRY_PAYMENT", blocked());

        assertTrue(result.isEmpty());
        List<RecoveryAction> actions = recoveryActionRepository.findByRecoveryDecisionAndActionType(decision, "RETRY_PAYMENT");
        assertTrue(actions.isEmpty());

        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(decision.getRecoveryCase().getId()))
                .toList();
        assertTrue(events.stream().anyMatch(e -> "ACTION_NOT_CREATED".equals(e.getEventType())));
    }

    @Test
    void repeatedCreateForSameDecisionAndType_isIdempotent() {
        RecoveryDecision decision = seedDecision("3");

        Optional<RecoveryAction> first = recoveryActionService.createIfAllowed(decision, "RETRY_PAYMENT", allowed());
        Optional<RecoveryAction> second = recoveryActionService.createIfAllowed(decision, "RETRY_PAYMENT", allowed());

        assertEquals(first.orElseThrow().getId(), second.orElseThrow().getId());
        List<RecoveryAction> actions = recoveryActionRepository.findByRecoveryDecisionAndActionType(decision, "RETRY_PAYMENT");
        assertEquals(1, actions.size());
    }

    @Test
    void directDuplicateInsert_sameDecisionAndActionType_isRejectedByTheDatabase() {
        RecoveryDecision decision = seedDecision("4");
        recoveryActionRepository.saveAndFlush(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED));

        assertThrows(DataIntegrityViolationException.class, () ->
                recoveryActionRepository.saveAndFlush(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED)));
    }

    @Test
    void differentActionTypes_sameDecision_bothAllowed() {
        RecoveryDecision decision = seedDecision("5");

        Optional<RecoveryAction> retry = recoveryActionService.createIfAllowed(decision, "RETRY_PAYMENT", allowed());
        Optional<RecoveryAction> notify = recoveryActionService.createIfAllowed(decision, "SEND_NOTIFICATION", allowed());

        assertTrue(retry.isPresent());
        assertTrue(notify.isPresent());
        assertTrue(!retry.get().getId().equals(notify.get().getId()));
    }

    @Test
    void sameActionType_differentDecisions_bothAllowed() {
        RecoveryDecision decisionA = seedDecision("6a");
        RecoveryDecision decisionB = seedDecision("6b");

        Optional<RecoveryAction> actionA = recoveryActionService.createIfAllowed(decisionA, "RETRY_PAYMENT", allowed());
        Optional<RecoveryAction> actionB = recoveryActionService.createIfAllowed(decisionB, "RETRY_PAYMENT", allowed());

        assertTrue(actionA.isPresent());
        assertTrue(actionB.isPresent());
        assertTrue(!actionA.get().getId().equals(actionB.get().getId()));
    }
}
