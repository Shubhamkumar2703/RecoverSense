package com.recoversense.dashboard;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import com.recoversense.service.DiagnosisService;
import com.recoversense.service.RecoveryActionExecutionService;
import com.recoversense.service.RecoveryActionExecutor;
import com.recoversense.service.RecoveryActionService;
import com.recoversense.service.RecoveryActionVerificationService;
import com.recoversense.service.RecoveryActionVerifier;
import com.recoversense.service.RecoveryLifecycleService;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryOutcome;
import com.recoversense.service.RecoveryPolicyService;
import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SimulatedSettlementVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives real recovery outcomes through RecoveryOrchestrationService (same
 * helper pattern as RecoveryOrchestrationServiceTest) and asserts the
 * DashboardMetricsService aggregations computed from the resulting persisted
 * state - never asserts on hand-crafted numbers.
 */
@SpringBootTest
@Transactional
class DashboardMetricsServiceTest {

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
    private DiagnosisService diagnosisService;
    @Autowired
    private DashboardMetricsService dashboardMetricsService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    private Payment seedFailedPayment(String externalPaymentId, String failureReason, BigDecimal amount) {
        Customer customer = new Customer("cust_" + externalPaymentId, "user+" + externalPaymentId + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);

        Payment payment = new Payment(externalPaymentId, customer, amount, "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailureReason(failureReason);
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    private RecoveryOrchestrationService orchestrationServiceWith(SettlementState settlementState, String externalPaymentId,
                                                                    RecoveryActionExecutor executor, RecoveryActionVerifier verifier) {
        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState(externalPaymentId, settlementState);
        RecoveryPolicyService policyService = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);
        RecoveryLifecycleService lifecycleService = new RecoveryLifecycleService(
                paymentRepository, recoveryCaseRepository, recoveryDecisionRepository, auditEventRepository,
                policyService, new RecoveryActionService(recoveryActionRepository, auditEventRepository));
        RecoveryActionExecutionService executionService =
                new RecoveryActionExecutionService(recoveryActionRepository, auditEventRepository, executor);
        RecoveryActionVerificationService verificationService =
                new RecoveryActionVerificationService(recoveryActionRepository, auditEventRepository, verifier);
        return new RecoveryOrchestrationService(diagnosisService, lifecycleService, executionService, verificationService);
    }

    @Test
    void noRecoveryCases_reportsZeroes() {
        DashboardSummary summary = dashboardMetricsService.buildDashboard().summary();

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.revenueAtRisk()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.recoveredRevenue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.recoveryRate()));
        assertEquals(0, summary.verifiedActions());
        assertEquals(0, summary.policyBlocks());
        assertTrue(dashboardMetricsService.buildDashboard().recentCases().isEmpty());
    }

    @Test
    void blockedCase_countsTowardRiskButNotRecovered() {
        // No settlement seeded for this payment id: SETTLED forces policy to
        // BLOCK regardless of the other checks (mirrors
        // RecoveryOrchestrationServiceTest.policyRejection_neverExecutes).
        Payment payment = seedFailedPayment("pay_blocked", "card_declined", new BigDecimal("1000.00"));
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.SETTLED, "pay_blocked",
                a -> { throw new AssertionError("executor must not be called"); },
                a -> { throw new AssertionError("verifier must not be called"); });

        orchestration.recover(payment.getId());

        DashboardResponse dashboard = dashboardMetricsService.buildDashboard();
        assertEquals(0, new BigDecimal("1000.00").compareTo(dashboard.summary().revenueAtRisk()));
        assertEquals(0, BigDecimal.ZERO.compareTo(dashboard.summary().recoveredRevenue()));
        assertEquals(1, dashboard.summary().policyBlocks());
        assertEquals(0, dashboard.summary().verifiedActions());

        RecentCaseSummary row = onlyRow(dashboard, "pay_blocked");
        assertEquals("BLOCKED", row.policyResult());
        assertNull(row.executionStatus());
        assertNull(row.verificationStatus());
        assertEquals(RecoveryCaseStatus.OPEN.name(), row.caseStatus());
    }

    @Test
    void verifiedRecovery_countsAsRecoveredRevenueAndVerifiedAction() {
        Payment payment = seedFailedPayment("pay_recovered", "mandate_revoked", new BigDecimal("2500.00"));
        RecoveryOrchestrationService orchestration = orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_recovered",
                a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.VERIFIED);

        RecoveryOrchestrationResult result = orchestration.recover(payment.getId());
        assertEquals(RecoveryOutcome.RECOVERED, result.outcome());

        DashboardResponse dashboard = dashboardMetricsService.buildDashboard();
        assertEquals(0, new BigDecimal("2500.00").compareTo(dashboard.summary().revenueAtRisk()));
        assertEquals(0, new BigDecimal("2500.00").compareTo(dashboard.summary().recoveredRevenue()));
        assertEquals(0, BigDecimal.ONE.compareTo(dashboard.summary().recoveryRate()));
        assertEquals(1, dashboard.summary().verifiedActions());
        assertEquals(0, dashboard.summary().policyBlocks());

        RecentCaseSummary row = onlyRow(dashboard, "pay_recovered");
        assertEquals("ALLOWED", row.policyResult());
        assertEquals(ExecutionStatus.EXECUTED.name(), row.executionStatus());
        assertEquals(VerificationStatus.VERIFIED.name(), row.verificationStatus());
        assertEquals(RecoveryCaseStatus.RECOVERED.name(), row.caseStatus());
        assertEquals("REACQUIRE_MANDATE", row.strategy());
    }

    @Test
    void recoveryRate_reflectsPartialRecoveryAcrossCases() {
        Payment recoveredPayment = seedFailedPayment("pay_partial_recovered", "mandate_revoked", new BigDecimal("1000.00"));
        orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_partial_recovered",
                a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.VERIFIED)
                .recover(recoveredPayment.getId());

        Payment unrecoveredPayment = seedFailedPayment("pay_partial_unrecovered", "mandate_revoked", new BigDecimal("1000.00"));
        orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_partial_unrecovered",
                a -> ExecutionStatus.FAILED, a -> { throw new AssertionError("verifier must not be called"); })
                .recover(unrecoveredPayment.getId());

        DashboardSummary summary = dashboardMetricsService.buildDashboard().summary();
        assertEquals(0, new BigDecimal("2000.00").compareTo(summary.revenueAtRisk()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(summary.recoveredRevenue()));
        assertEquals(0, new BigDecimal("0.5000").compareTo(summary.recoveryRate()));
    }

    @Test
    void strategyMix_groupsByDecisionStrategy() {
        Payment mandatePayment = seedFailedPayment("pay_strategy_mandate", "mandate_revoked", new BigDecimal("500.00"));
        orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_strategy_mandate",
                a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.VERIFIED)
                .recover(mandatePayment.getId());

        Payment waitPayment = seedFailedPayment("pay_strategy_wait", "insufficient_funds", new BigDecimal("500.00"));
        orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_strategy_wait",
                a -> { throw new UnsupportedOperationException("not implemented"); },
                a -> { throw new AssertionError("verifier must not be called"); })
                .recover(waitPayment.getId());

        List<StrategyMixEntry> strategyMix = dashboardMetricsService.buildDashboard().strategyMix();
        Map<String, Long> byStrategy = strategyMix.stream()
                .collect(Collectors.toMap(StrategyMixEntry::strategy, StrategyMixEntry::count));

        assertEquals(1L, byStrategy.get("REACQUIRE_MANDATE"));
        assertEquals(1L, byStrategy.get("WAIT_RETRY"));
    }

    @Test
    void auditTrailFor_returnsExistingVocabularyInOrder() {
        Payment payment = seedFailedPayment("pay_audit", "mandate_revoked", new BigDecimal("100.00"));
        RecoveryOrchestrationResult result = orchestrationServiceWith(SettlementState.NOT_SETTLED, "pay_audit",
                a -> ExecutionStatus.EXECUTED, a -> VerificationStatus.VERIFIED)
                .recover(payment.getId());

        List<AuditEventSummary> trail = dashboardMetricsService.auditTrailFor(result.recoveryCase().getId());

        List<String> eventTypes = trail.stream().map(AuditEventSummary::eventType).toList();
        assertEquals(List.of("RECOVERY_CASE_OPENED", "RECOVERY_DECISION_RECORDED", "POLICY_EVALUATED",
                "ACTION_CREATED", "ACTION_EXECUTION_ATTEMPTED", "ACTION_VERIFICATION_ATTEMPTED",
                "CASE_STATUS_CHANGED"), eventTypes);
    }

    private RecentCaseSummary onlyRow(DashboardResponse dashboard, String externalPaymentId) {
        return dashboard.recentCases().stream()
                .filter(row -> row.externalPaymentId().equals(externalPaymentId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no recent-case row for " + externalPaymentId));
    }
}
