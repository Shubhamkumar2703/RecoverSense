package com.recoversense.dashboard;

import com.recoversense.diagnosis.DiagnosisSource;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Read-only aggregation over already-persisted recovery data. Never creates,
 * updates, or deletes anything - every method here is a query. See
 * DashboardController for the HTTP boundary that keeps it that way.
 */
@Service
public class DashboardMetricsService {

    private static final String POLICY_BLOCK_EVENT_TYPE = "ACTION_NOT_CREATED";
    private static final String EXECUTION_UNAVAILABLE_EVENT_TYPE = "ACTION_EXECUTION_UNAVAILABLE";
    private static final int AT_RISK_LIMIT = 20;
    private static final List<RecoveryCaseStatus> ACTIVE_CASE_STATUSES = List.of(RecoveryCaseStatus.OPEN, RecoveryCaseStatus.RECOVERED);
    // M1.26: DemoDataSeeder's ids are fixed, well-known constants ("pay_demo_...");
    // a real Razorpay payment id is provider-generated and can never collide
    // with this prefix - see DemoDataSeeder/DemoSettlementVerifier.
    private static final String DEMO_SEEDED_ID_PREFIX = "pay_demo_";

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final AuditEventRepository auditEventRepository;
    private final PaymentRepository paymentRepository;

    public DashboardMetricsService(RecoveryCaseRepository recoveryCaseRepository,
                                    RecoveryActionRepository recoveryActionRepository,
                                    RecoveryDecisionRepository recoveryDecisionRepository,
                                    AuditEventRepository auditEventRepository,
                                    PaymentRepository paymentRepository) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.auditEventRepository = auditEventRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse buildDashboard() {
        return new DashboardResponse(buildSummary(), buildStrategyMix(), buildRecentCases());
    }

    @Transactional(readOnly = true)
    public List<AuditEventSummary> auditTrailFor(Long recoveryCaseId) {
        return auditEventRepository.findByRecoveryCase_IdOrderByCreatedAtAsc(recoveryCaseId).stream()
                .map(event -> new AuditEventSummary(event.getEventType(), event.getEventPayload(), event.getCreatedAt()))
                .toList();
    }

    /**
     * FAILED payments with no OPEN or RECOVERED RecoveryCase - see M1.22
     * ADR-012 (docs/DECISIONS.md). Never includes a payment RecoverSense
     * already recovered, even though Payment.status stays FAILED for it.
     */
    @Transactional(readOnly = true)
    public List<AtRiskPaymentSummary> atRiskPayments() {
        return paymentRepository
                .findAtRiskPayments(PaymentStatus.FAILED, ACTIVE_CASE_STATUSES, PageRequest.of(0, AT_RISK_LIMIT))
                .stream()
                .map(payment -> new AtRiskPaymentSummary(
                        payment.getId(),
                        payment.getExternalPaymentId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getFailureReason(),
                        payment.getFailedAt(),
                        classifyDataSource(payment.getExternalPaymentId())))
                .toList();
    }

    private DashboardSummary buildSummary() {
        BigDecimal revenueAtRisk = recoveryCaseRepository.sumPaymentAmount();
        BigDecimal recoveredRevenue = recoveryCaseRepository.sumPaymentAmountByStatus(RecoveryCaseStatus.RECOVERED);
        BigDecimal recoveryRate = revenueAtRisk.signum() == 0
                ? BigDecimal.ZERO
                : recoveredRevenue.divide(revenueAtRisk, 4, RoundingMode.HALF_UP);
        long verifiedActions = recoveryActionRepository.countByVerificationStatus(VerificationStatus.VERIFIED);
        long policyBlocks = auditEventRepository.countByEventType(POLICY_BLOCK_EVENT_TYPE);
        long failedPaymentsCount = paymentRepository.countByStatus(PaymentStatus.FAILED);
        long recoveredCasesCount = recoveryCaseRepository.countByStatus(RecoveryCaseStatus.RECOVERED);
        long pendingVerificationCount = recoveryActionRepository
                .countByExecutionStatusAndVerificationStatus(ExecutionStatus.EXECUTED, VerificationStatus.UNVERIFIED);
        long executionIssuesCount = recoveryActionRepository.countByExecutionStatus(ExecutionStatus.FAILED)
                + auditEventRepository.countByEventType(EXECUTION_UNAVAILABLE_EVENT_TYPE);
        return new DashboardSummary(revenueAtRisk, recoveredRevenue, recoveryRate, verifiedActions, policyBlocks,
                failedPaymentsCount, recoveredCasesCount, pendingVerificationCount, executionIssuesCount);
    }

    /**
     * M1.26: display-only classification, never persisted - see
     * AtRiskPaymentSummary/RecentCaseSummary javadoc.
     */
    private String classifyDataSource(String externalPaymentId) {
        return externalPaymentId != null && externalPaymentId.startsWith(DEMO_SEEDED_ID_PREFIX) ? "DEMO" : "REAL";
    }

    private List<StrategyMixEntry> buildStrategyMix() {
        return recoveryDecisionRepository.countGroupedByStrategy().stream()
                .map(row -> new StrategyMixEntry(row.getStrategy(), row.getTotal()))
                .toList();
    }

    private List<RecentCaseSummary> buildRecentCases() {
        return recoveryCaseRepository.findTop20ByOrderByOpenedAtDesc().stream()
                .map(this::toRecentCaseSummary)
                .toList();
    }

    private RecentCaseSummary toRecentCaseSummary(RecoveryCase recoveryCase) {
        Payment payment = recoveryCase.getPayment();
        Optional<RecoveryDecision> latestDecision =
                recoveryDecisionRepository.findTopByRecoveryCaseOrderByDecidedAtDesc(recoveryCase);

        String diagnosisCategory = null;
        BigDecimal diagnosisConfidence = null;
        String strategy = null;
        String policyResult = null;
        String executionStatus = null;
        String verificationStatus = null;
        String diagnosisSource = null;
        String providerUrl = null;

        if (latestDecision.isPresent()) {
            RecoveryDecision decision = latestDecision.get();
            diagnosisCategory = decision.getDiagnosisCategory();
            diagnosisConfidence = decision.getDiagnosisConfidence();
            strategy = decision.getStrategy();
            DiagnosisSource source = DiagnosisSource.parsePrefix(decision.getDiagnosisRaw());
            diagnosisSource = source == null ? null : source.name();

            List<RecoveryAction> actions = recoveryActionRepository
                    .findByRecoveryDecisionAndActionType(decision, decision.getStrategy());
            if (actions.isEmpty()) {
                policyResult = PolicyResult.BLOCKED.name();
            } else {
                RecoveryAction action = actions.get(0);
                policyResult = action.getPolicyResult().name();
                executionStatus = action.getExecutionStatus().name();
                verificationStatus = action.getVerificationStatus().name();
                providerUrl = action.getProviderUrl();
            }
        }

        return new RecentCaseSummary(
                recoveryCase.getId(),
                payment.getId(),
                payment.getExternalPaymentId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getFailureReason(),
                diagnosisCategory,
                diagnosisConfidence,
                strategy,
                policyResult,
                executionStatus,
                verificationStatus,
                recoveryCase.getStatus().name(),
                recoveryCase.getOpenedAt(),
                classifyDataSource(payment.getExternalPaymentId()),
                diagnosisSource,
                providerUrl
        );
    }
}
