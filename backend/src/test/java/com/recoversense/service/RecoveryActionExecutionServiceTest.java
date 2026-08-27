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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RecoveryActionExecutionServiceTest {

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

    private RecoveryAction seedPendingAllowedAction(String suffix) {
        RecoveryDecision decision = seedDecision(suffix);
        return recoveryActionRepository.save(new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED));
    }

    @Test
    void realExecutorIsNotImplemented_throwsAndLeavesActionUntouched() {
        RecoveryAction action = seedPendingAllowedAction("1");

        assertThrows(UnsupportedOperationException.class,
                () -> recoveryActionExecutionService.attemptExecution(action.getId()));

        RecoveryAction reloaded = recoveryActionRepository.findById(action.getId()).orElseThrow();
        assertEquals(ExecutionStatus.PENDING, reloaded.getExecutionStatus());
        assertNull(reloaded.getExecutedAt());

        List<AuditEvent> events = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(action.getRecoveryDecision().getRecoveryCase().getId()))
                .toList();
        assertTrue(events.stream().anyMatch(e -> "ACTION_EXECUTION_UNAVAILABLE".equals(e.getEventType())));
    }

    @Test
    void fakeExecutorSuccess_transitionsToExecutedAndSetsTimestamp() {
        RecoveryAction action = seedPendingAllowedAction("2");
        RecoveryActionExecutionService serviceWithFake = new RecoveryActionExecutionService(
                recoveryActionRepository, auditEventRepository, a -> ExecutionStatus.EXECUTED);

        RecoveryAction result = serviceWithFake.attemptExecution(action.getId());

        assertEquals(ExecutionStatus.EXECUTED, result.getExecutionStatus());
        assertNotNull(result.getExecutedAt());
    }

    @Test
    void nonPendingAction_cannotBeExecutedAgain() {
        RecoveryAction action = seedPendingAllowedAction("3");
        RecoveryActionExecutionService serviceWithFake = new RecoveryActionExecutionService(
                recoveryActionRepository, auditEventRepository, a -> ExecutionStatus.EXECUTED);
        serviceWithFake.attemptExecution(action.getId());

        assertThrows(InvalidActionTransitionException.class,
                () -> serviceWithFake.attemptExecution(action.getId()));
    }

    @Test
    void blockedPolicyAction_cannotBeExecuted() {
        RecoveryDecision decision = seedDecision("4");
        RecoveryAction blockedAction = recoveryActionRepository.save(
                new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.BLOCKED));

        assertThrows(InvalidActionTransitionException.class,
                () -> recoveryActionExecutionService.attemptExecution(blockedAction.getId()));
    }

    @Test
    void unknownAction_isRejected() {
        assertThrows(RecoveryActionNotFoundException.class,
                () -> recoveryActionExecutionService.attemptExecution(-1L));
    }
}
