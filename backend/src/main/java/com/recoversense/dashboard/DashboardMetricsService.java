package com.recoversense.dashboard;

import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
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

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final AuditEventRepository auditEventRepository;

    public DashboardMetricsService(RecoveryCaseRepository recoveryCaseRepository,
                                    RecoveryActionRepository recoveryActionRepository,
                                    RecoveryDecisionRepository recoveryDecisionRepository,
                                    AuditEventRepository auditEventRepository) {
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.auditEventRepository = auditEventRepository;
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

    private DashboardSummary buildSummary() {
        BigDecimal revenueAtRisk = recoveryCaseRepository.sumPaymentAmount();
        BigDecimal recoveredRevenue = recoveryCaseRepository.sumPaymentAmountByStatus(RecoveryCaseStatus.RECOVERED);
        BigDecimal recoveryRate = revenueAtRisk.signum() == 0
                ? BigDecimal.ZERO
                : recoveredRevenue.divide(revenueAtRisk, 4, RoundingMode.HALF_UP);
        long verifiedActions = recoveryActionRepository.countByVerificationStatus(VerificationStatus.VERIFIED);
        long policyBlocks = auditEventRepository.countByEventType(POLICY_BLOCK_EVENT_TYPE);
        return new DashboardSummary(revenueAtRisk, recoveredRevenue, recoveryRate, verifiedActions, policyBlocks);
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

        if (latestDecision.isPresent()) {
            RecoveryDecision decision = latestDecision.get();
            diagnosisCategory = decision.getDiagnosisCategory();
            diagnosisConfidence = decision.getDiagnosisConfidence();
            strategy = decision.getStrategy();

            List<RecoveryAction> actions = recoveryActionRepository
                    .findByRecoveryDecisionAndActionType(decision, decision.getStrategy());
            if (actions.isEmpty()) {
                policyResult = PolicyResult.BLOCKED.name();
            } else {
                RecoveryAction action = actions.get(0);
                policyResult = action.getPolicyResult().name();
                executionStatus = action.getExecutionStatus().name();
                verificationStatus = action.getVerificationStatus().name();
            }
        }

        return new RecentCaseSummary(
                recoveryCase.getId(),
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
                recoveryCase.getOpenedAt()
        );
    }
}
