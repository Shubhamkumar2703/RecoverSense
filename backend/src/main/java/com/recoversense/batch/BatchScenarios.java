package com.recoversense.batch;

import com.recoversense.domain.CustomerStatus;
import com.recoversense.settlement.SettlementState;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fixed, deterministic, demo-oriented evaluation dataset - ten representative
 * scenarios, one per major diagnosis/policy outcome the real pipeline can
 * produce (CLAUDE.md #4's lifecycle, POLICY_SPEC.md's seven checks). Not a
 * second business-rule engine: every scenario is evaluated by the real
 * DiagnosisEngine/StrategyRouter/PolicyEngine in {@link BatchEvaluationService}.
 * Deliberately small and hardcoded rather than generated - this is an
 * evaluation dataset, not a general-purpose simulator.
 */
final class BatchScenarios {

    static final List<BatchScenario> ALL = List.of(
            new BatchScenario("batch_repeated_failure_link",
                    "Repeated card failure - Payment Link executed, not yet paid/verified",
                    new BigDecimal("1500.00"), "Repeated card failures on retry",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 30, false),
            new BatchScenario("batch_insufficient_funds",
                    "Insufficient funds - policy allows, but wait/retry has no immediate executable action",
                    new BigDecimal("800.00"), "Insufficient funds in customer account",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 30, null),
            new BatchScenario("batch_mandate_invalid",
                    "Mandate revoked - policy allows, but mandate reacquisition has no server-side execution path",
                    new BigDecimal("2200.00"), "Mandate revoked by issuing bank",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 30, null),
            new BatchScenario("batch_inactive_customer",
                    "Inactive customer - blocked before any action is considered",
                    new BigDecimal("1200.00"), "Card declined - insufficient funds",
                    CustomerStatus.INACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 30, null),
            new BatchScenario("batch_unknown_settlement",
                    "Settlement state unknown - blocked rather than guessed",
                    new BigDecimal("1000.00"), "Repeated payment failure - card declined multiple times",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.UNKNOWN, 30, null),
            new BatchScenario("batch_unknown_subscription",
                    "Subscription state unknown - blocked rather than guessed",
                    new BigDecimal("900.00"), "Repeated payment failure - card declined multiple times",
                    CustomerStatus.ACTIVE, null, 0, false, SettlementState.NOT_SETTLED, 30, null),
            new BatchScenario("batch_retry_limit_exceeded",
                    "Retry limit already reached for this action type - blocked",
                    new BigDecimal("1300.00"), "Card declined - third consecutive attempt",
                    CustomerStatus.ACTIVE, "active", 3, false, SettlementState.NOT_SETTLED, 30, null),
            new BatchScenario("batch_amount_over_limit",
                    "Amount exceeds merchant policy limit - blocked",
                    new BigDecimal("75000.00"), "Repeated payment failure - card declined multiple times",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 30, null),
            new BatchScenario("batch_freshness_violation",
                    "Failure too recent - webhook/state freshness window not respected",
                    new BigDecimal("1100.00"), "Repeated payment failure - card declined multiple times",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 1, null),
            new BatchScenario("batch_verified_recovery",
                    "Successful, independently verified recovery - only this counts toward revenue recovered",
                    new BigDecimal("2000.00"), "Repeated payment failure - card declined multiple times",
                    CustomerStatus.ACTIVE, "active", 0, false, SettlementState.NOT_SETTLED, 30, true)
    );

    private BatchScenarios() {
    }
}
