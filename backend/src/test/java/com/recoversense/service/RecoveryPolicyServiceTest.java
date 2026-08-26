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
import com.recoversense.policy.PolicyCheckResult;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the real Postgres instance configured for this project (same
 * pattern as RecoversenseBackendApplicationTests) so the nested-association
 * repository queries are exercised for real. @Transactional rolls each test
 * back, so no fixture data survives between tests.
 */
@SpringBootTest
@Transactional
class RecoveryPolicyServiceTest {

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
    private RecoveryPolicyService recoveryPolicyService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private RecoveryCase seedRecoveryCase(String suffix, BigDecimal amount, String subscriptionStatus,
                                           CustomerStatus customerStatus, Instant failedAt) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(customerStatus);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_" + suffix, customer, amount, "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus(subscriptionStatus);
        payment.setFailedAt(failedAt);
        paymentRepository.save(payment);

        RecoveryCase recoveryCase = new RecoveryCase(payment);
        recoveryCaseRepository.save(recoveryCase);
        return recoveryCase;
    }

    private void addAction(RecoveryCase recoveryCase, ExecutionStatus executionStatus) {
        RecoveryDecision decision = new RecoveryDecision(recoveryCase, "MANDATE_REVOKED",
                new BigDecimal("0.9000"), "raw diagnosis", "RETRY");
        recoveryDecisionRepository.save(decision);

        RecoveryAction action = new RecoveryAction(decision, "RETRY_PAYMENT", PolicyResult.ALLOWED);
        action.setExecutionStatus(executionStatus);
        recoveryActionRepository.save(action);
    }

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    @Test
    void allAvailableChecksPassing_stillBlocksBecauseSettlementStateIsUnknown() {
        RecoveryCase recoveryCase = seedRecoveryCase("1", new BigDecimal("1000"), "active",
                CustomerStatus.ACTIVE, STALE_ENOUGH);

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId());

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertEquals(1, decision.failedReasons().size());
        assertFailed(decision, "not_already_settled_elsewhere");
        assertPassed(decision, "retry_limit_not_exceeded");
        assertPassed(decision, "subscription_state_valid");
        assertPassed(decision, "customer_active");
        assertPassed(decision, "no_pending_reacquisition");
        assertPassed(decision, "amount_within_policy");
        assertPassed(decision, "webhook_delay_window_respected");
    }

    @Test
    void realPolicyFailure_isPersistedWithFailedCheckAndReason() {
        RecoveryCase recoveryCase = seedRecoveryCase("2", new BigDecimal("1000"), "active",
                CustomerStatus.INACTIVE, STALE_ENOUGH);

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId());

        assertEquals(PolicyResult.BLOCKED, decision.result());
        assertFailed(decision, "customer_active");

        AuditEvent persisted = onlyAuditEventFor(recoveryCase);
        Map<String, Object> payload = parsePayload(persisted);
        assertEquals("BLOCKED", payload.get("result"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) payload.get("checks");
        boolean customerCheckFailedInPayload = checks.stream()
                .anyMatch(c -> "customer_active".equals(c.get("checkName")) && Boolean.FALSE.equals(c.get("passed")));
        assertTrue(customerCheckFailedInPayload);
    }

    @Test
    void retryCountIsDerivedFromExecutedActions_notPendingOnes() {
        RecoveryCase recoveryCase = seedRecoveryCase("3", new BigDecimal("1000"), "active",
                CustomerStatus.ACTIVE, STALE_ENOUGH);
        addAction(recoveryCase, ExecutionStatus.EXECUTED);
        addAction(recoveryCase, ExecutionStatus.EXECUTED);

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId());

        assertPassed(decision, "retry_limit_not_exceeded");
    }

    @Test
    void retryCountAtLimit_blocksRetryCheck() {
        RecoveryCase recoveryCase = seedRecoveryCase("4", new BigDecimal("1000"), "active",
                CustomerStatus.ACTIVE, STALE_ENOUGH);
        addAction(recoveryCase, ExecutionStatus.EXECUTED);
        addAction(recoveryCase, ExecutionStatus.EXECUTED);
        addAction(recoveryCase, ExecutionStatus.EXECUTED);

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId());

        assertFailed(decision, "retry_limit_not_exceeded");
    }

    @Test
    void pendingActionOnPayment_blocksNoPendingReacquisitionCheck() {
        RecoveryCase recoveryCase = seedRecoveryCase("5", new BigDecimal("1000"), "active",
                CustomerStatus.ACTIVE, STALE_ENOUGH);
        addAction(recoveryCase, ExecutionStatus.PENDING);

        PolicyDecision decision = recoveryPolicyService.evaluate(recoveryCase.getId());

        assertFailed(decision, "no_pending_reacquisition");
    }

    @Test
    void auditEventPersistsResultAndAllSevenCheckResults() {
        RecoveryCase recoveryCase = seedRecoveryCase("6", new BigDecimal("1000"), "active",
                CustomerStatus.ACTIVE, STALE_ENOUGH);

        recoveryPolicyService.evaluate(recoveryCase.getId());

        AuditEvent persisted = onlyAuditEventFor(recoveryCase);
        assertEquals("POLICY_EVALUATED", persisted.getEventType());
        Map<String, Object> payload = parsePayload(persisted);
        assertTrue(payload.containsKey("result"));
        assertTrue(payload.containsKey("evaluatedAt"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) payload.get("checks");
        assertEquals(7, checks.size());
    }

    @Test
    void unknownRecoveryCase_failsClosedWithExplicitError() {
        assertThrows(RecoveryCaseNotFoundException.class, () -> recoveryPolicyService.evaluate(-1L));
    }

    private AuditEvent onlyAuditEventFor(RecoveryCase recoveryCase) {
        List<AuditEvent> matches = auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(recoveryCase.getId()))
                .toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(AuditEvent event) {
        return jsonMapper.readValue(event.getEventPayload(), Map.class);
    }

    private void assertPassed(PolicyDecision decision, String checkName) {
        assertTrue(findCheck(decision, checkName).passed(), "expected check to pass: " + checkName);
    }

    private void assertFailed(PolicyDecision decision, String checkName) {
        assertFalse(findCheck(decision, checkName).passed(), "expected check to fail: " + checkName);
    }

    private PolicyCheckResult findCheck(PolicyDecision decision, String checkName) {
        return decision.checks().stream()
                .filter(c -> c.checkName().equals(checkName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("check not found: " + checkName));
    }
}
