package com.recoversense.policy;

import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.PolicyResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic policy gate for recovery actions. Pure Java, no AI, no
 * payment-API calls, no side effects. Every required check must PASS for
 * ALLOW; any FAIL (including unknown/missing required state) results in
 * BLOCK. AI-produced diagnosis/confidence is not an input here by design.
 */
public final class PolicyEngine {

    static final int RETRY_LIMIT = 3;
    static final BigDecimal AMOUNT_LIMIT = new BigDecimal("50000");
    static final Duration WEBHOOK_FRESHNESS_WINDOW = Duration.ofMinutes(5);

    public PolicyDecision evaluate(PolicyContext context) {
        List<PolicyCheckResult> checks = new ArrayList<>();
        checks.add(retryLimitNotExceeded(context));
        checks.add(subscriptionStateValid(context));
        checks.add(customerActive(context));
        checks.add(noPendingReacquisition(context));
        checks.add(amountWithinPolicy(context));
        checks.add(notAlreadySettledElsewhere(context));
        checks.add(webhookDelayWindowRespected(context));

        boolean allPassed = checks.stream().allMatch(PolicyCheckResult::passed);
        return new PolicyDecision(allPassed ? PolicyResult.ALLOWED : PolicyResult.BLOCKED, checks);
    }

    private PolicyCheckResult retryLimitNotExceeded(PolicyContext context) {
        String name = "retry_limit_not_exceeded";
        Integer retryCount = context.retryCount();
        if (retryCount == null) {
            return new PolicyCheckResult(name, false, "retry count is unknown");
        }
        if (retryCount < RETRY_LIMIT) {
            return new PolicyCheckResult(name, true, "retry count " + retryCount + " is within limit");
        }
        return new PolicyCheckResult(name, false, "retry count " + retryCount + " has reached the limit of " + RETRY_LIMIT);
    }

    private PolicyCheckResult subscriptionStateValid(PolicyContext context) {
        String name = "subscription_state_valid";
        String subscriptionStatus = context.payment() == null ? null : context.payment().getSubscriptionStatus();
        if (subscriptionStatus == null) {
            return new PolicyCheckResult(name, false, "subscription status is unknown");
        }
        if ("active".equalsIgnoreCase(subscriptionStatus)) {
            return new PolicyCheckResult(name, true, "subscription status is active");
        }
        return new PolicyCheckResult(name, false, "subscription status is '" + subscriptionStatus + "', expected active");
    }

    private PolicyCheckResult customerActive(PolicyContext context) {
        String name = "customer_active";
        CustomerStatus status = context.customer() == null ? null : context.customer().getStatus();
        if (status == null) {
            return new PolicyCheckResult(name, false, "customer status is unknown");
        }
        if (status == CustomerStatus.ACTIVE) {
            return new PolicyCheckResult(name, true, "customer is active");
        }
        return new PolicyCheckResult(name, false, "customer status is " + status + ", expected ACTIVE");
    }

    private PolicyCheckResult noPendingReacquisition(PolicyContext context) {
        String name = "no_pending_reacquisition";
        Boolean pending = context.pendingReacquisitionExists();
        if (pending == null) {
            return new PolicyCheckResult(name, false, "pending re-acquisition state is unknown");
        }
        if (!pending) {
            return new PolicyCheckResult(name, true, "no pending recovery/re-acquisition exists");
        }
        return new PolicyCheckResult(name, false, "a pending recovery/re-acquisition already exists");
    }

    private PolicyCheckResult amountWithinPolicy(PolicyContext context) {
        String name = "amount_within_policy";
        BigDecimal amount = context.payment() == null ? null : context.payment().getAmount();
        if (amount == null) {
            return new PolicyCheckResult(name, false, "amount is unknown");
        }
        if (amount.compareTo(AMOUNT_LIMIT) <= 0) {
            return new PolicyCheckResult(name, true, "amount " + amount + " is within policy limit of " + AMOUNT_LIMIT);
        }
        return new PolicyCheckResult(name, false, "amount " + amount + " exceeds policy limit of " + AMOUNT_LIMIT);
    }

    private PolicyCheckResult notAlreadySettledElsewhere(PolicyContext context) {
        String name = "not_already_settled_elsewhere";
        Boolean settledElsewhere = context.alreadySettledElsewhere();
        if (settledElsewhere == null) {
            return new PolicyCheckResult(name, false, "settlement state is unknown");
        }
        if (!settledElsewhere) {
            return new PolicyCheckResult(name, true, "payment has not settled elsewhere");
        }
        return new PolicyCheckResult(name, false, "payment has already settled through another channel");
    }

    private PolicyCheckResult webhookDelayWindowRespected(PolicyContext context) {
        String name = "webhook_delay_window_respected";
        var failedAt = context.payment() == null ? null : context.payment().getFailedAt();
        var evaluatedAt = context.evaluatedAt();
        if (failedAt == null || evaluatedAt == null) {
            return new PolicyCheckResult(name, false, "webhook freshness state is unknown");
        }
        Duration elapsed = Duration.between(failedAt, evaluatedAt);
        if (!elapsed.isNegative() && elapsed.compareTo(WEBHOOK_FRESHNESS_WINDOW) >= 0) {
            return new PolicyCheckResult(name, true, "webhook freshness window respected (" + elapsed + " elapsed)");
        }
        return new PolicyCheckResult(name, false,
                "webhook freshness window not respected (" + elapsed + " elapsed, required " + WEBHOOK_FRESHNESS_WINDOW + ")");
    }
}
