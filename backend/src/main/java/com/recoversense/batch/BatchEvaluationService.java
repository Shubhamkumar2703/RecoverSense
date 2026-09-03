package com.recoversense.batch;

import com.recoversense.diagnosis.DiagnosisContext;
import com.recoversense.diagnosis.DiagnosisEngine;
import com.recoversense.diagnosis.DiagnosisResult;
import com.recoversense.diagnosis.StrategyRouter;
import com.recoversense.domain.Customer;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.policy.PolicyContext;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.policy.PolicyEngine;
import com.recoversense.settlement.SettlementState;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Batch Recovery Evaluation (Track 03: "measured money recovered across a
 * batch, with compliant escalation, stopping rules, and an audit trail").
 * Never persists anything, never calls Razorpay, never touches a real
 * RecoveryCase/RecoveryAction - runs {@link BatchScenarios#ALL} (fixed,
 * non-persisted, demo-labeled) through the real, unmodified
 * {@link DiagnosisEngine}, {@link StrategyRouter} and {@link PolicyEngine} to
 * derive each item's outcome exactly as the live single-payment pipeline
 * would (see DiagnosisService/RecoveryPolicyService, whose fact-gathering
 * this mirrors, but against in-memory Payment/Customer objects instead of
 * repository-loaded ones).
 * <p>
 * Execution is never real: an item can only reach EXECUTED_AWAITING_VERIFICATION
 * or VERIFIED_RECOVERED when policy is ALLOWED and the strategy is
 * PAYMENT_LINK (the only strategy the real executor implements), and even
 * then the terminal state is the scenario's own scripted, honestly-labeled
 * simulated outcome (see BatchScenario) - never a real Razorpay call.
 * revenueRecovered is summed only from VERIFIED_RECOVERED items, matching the
 * same "execution never implies recovery" rule the real pipeline enforces.
 */
@Service
public class BatchEvaluationService {

    static final String EXECUTABLE_STRATEGY = "PAYMENT_LINK";
    private static final String DATASET_LABEL =
            "BATCH EVALUATION - SIMULATED / EVALUATION DATASET (not real Razorpay transactions)";

    private final DiagnosisEngine diagnosisEngine = new DiagnosisEngine();
    private final StrategyRouter strategyRouter = new StrategyRouter();
    private final PolicyEngine policyEngine = new PolicyEngine();

    public BatchEvaluationResponse evaluate() {
        Instant evaluatedAt = Instant.now();
        List<BatchItemResult> items = BatchScenarios.ALL.stream()
                .map(scenario -> evaluateOne(scenario, evaluatedAt))
                .toList();

        return new BatchEvaluationResponse(DATASET_LABEL, buildMetrics(items), buildSafety(items), items);
    }

    private BatchItemResult evaluateOne(BatchScenario scenario, Instant evaluatedAt) {
        DiagnosisContext diagnosisContext = new DiagnosisContext(
                scenario.failureReason(), scenario.subscriptionStatus(), scenario.customerStatus(),
                scenario.retryCountForProposedAction());
        DiagnosisResult diagnosis = diagnosisEngine.diagnose(diagnosisContext);
        String strategy = strategyRouter.route(diagnosis.diagnosisCategory());

        Customer customer = new Customer("batch-eval-" + scenario.externalPaymentId(), null);
        customer.setStatus(scenario.customerStatus());
        Payment payment = new Payment(scenario.externalPaymentId(), customer, scenario.amount(), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus(scenario.subscriptionStatus());
        payment.setFailureReason(scenario.failureReason());
        payment.setFailedAt(evaluatedAt.minus(scenario.minutesSinceFailure(), ChronoUnit.MINUTES));

        Boolean alreadySettledElsewhere = switch (scenario.settlementState()) {
            case SETTLED -> Boolean.TRUE;
            case NOT_SETTLED -> Boolean.FALSE;
            case UNKNOWN -> null;
        };

        PolicyContext policyContext = new PolicyContext(customer, payment, scenario.retryCountForProposedAction(),
                scenario.pendingReacquisitionExists(), alreadySettledElsewhere, evaluatedAt);
        PolicyDecision decision = policyEngine.evaluate(policyContext);

        String outcome;
        BigDecimal recoveredAmount = null;
        if (decision.result() == PolicyResult.BLOCKED) {
            outcome = "BLOCKED";
        } else if (!EXECUTABLE_STRATEGY.equals(strategy)) {
            outcome = "ALLOWED_NO_EXECUTABLE_ACTION";
        } else if (Boolean.TRUE.equals(scenario.scriptedVerified())) {
            outcome = "VERIFIED_RECOVERED";
            recoveredAmount = scenario.amount();
        } else {
            outcome = "EXECUTED_AWAITING_VERIFICATION";
        }

        return new BatchItemResult(scenario.externalPaymentId(), scenario.description(), scenario.amount(),
                scenario.failureReason(), diagnosis.diagnosisCategory(), diagnosis.confidence(), strategy,
                decision.result().name(), decision.checks(), outcome, recoveredAmount);
    }

    private BatchMetrics buildMetrics(List<BatchItemResult> items) {
        BigDecimal revenueAtRisk = items.stream().map(BatchItemResult::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int policyEligible = (int) items.stream().filter(i -> "ALLOWED".equals(i.policyResult())).count();
        int policyBlocked = items.size() - policyEligible;
        int actionsAttempted = (int) items.stream().filter(BatchEvaluationService::wasExecuted).count();
        BigDecimal revenueRecovered = items.stream()
                .filter(i -> "VERIFIED_RECOVERED".equals(i.outcome()))
                .map(BatchItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int verifiedRecoveries = (int) items.stream().filter(i -> "VERIFIED_RECOVERED".equals(i.outcome())).count();

        return new BatchMetrics(items.size(), revenueAtRisk, policyEligible, policyBlocked, actionsAttempted,
                verifiedRecoveries, revenueRecovered, recoveryRate(revenueRecovered, revenueAtRisk));
    }

    private BatchSafetySummary buildSafety(List<BatchItemResult> items) {
        int unauthorizedActions = (int) items.stream().filter(BatchEvaluationService::executedWhileBlocked).count();
        int policyViolations = (int) items.stream().filter(BatchEvaluationService::executedForNonExecutableStrategy).count();
        // The evaluator never persists a RecoveryAction (see class javadoc),
        // so a duplicate pending action is structurally impossible here -
        // this is a fixed 0, not a placeholder.
        int duplicatePendingActions = 0;
        int unverifiedRecoveries = (int) items.stream()
                .filter(i -> "VERIFIED_RECOVERED".equals(i.outcome()) && i.recoveredAmount() == null)
                .count();

        return new BatchSafetySummary(unauthorizedActions, policyViolations, duplicatePendingActions, unverifiedRecoveries);
    }

    private static boolean wasExecuted(BatchItemResult item) {
        return "EXECUTED_AWAITING_VERIFICATION".equals(item.outcome()) || "VERIFIED_RECOVERED".equals(item.outcome());
    }

    private static boolean executedWhileBlocked(BatchItemResult item) {
        return wasExecuted(item) && "BLOCKED".equals(item.policyResult());
    }

    private static boolean executedForNonExecutableStrategy(BatchItemResult item) {
        return wasExecuted(item) && !EXECUTABLE_STRATEGY.equals(item.strategy());
    }

    /** Package-private so BatchEvaluationServiceTest can exercise the zero-revenue-at-risk guard directly. */
    static BigDecimal recoveryRate(BigDecimal revenueRecovered, BigDecimal revenueAtRisk) {
        if (revenueAtRisk.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return revenueRecovered.divide(revenueAtRisk, 4, RoundingMode.HALF_UP);
    }
}
