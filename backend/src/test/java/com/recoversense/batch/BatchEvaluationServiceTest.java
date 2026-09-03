package com.recoversense.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests - BatchEvaluationService has a no-arg constructor and
 * calls no repository, no HTTP client, no Razorpay class (see its imports):
 * it is only DiagnosisEngine + StrategyRouter + PolicyEngine over a fixed,
 * non-persisted dataset, so no Spring context is needed to exercise it.
 */
class BatchEvaluationServiceTest {

    private final BatchEvaluationService service = new BatchEvaluationService();

    @Test
    void revenueAtRisk_isTheSumOfEveryScenarioAmount() {
        BatchEvaluationResponse response = service.evaluate();

        BigDecimal expected = BatchScenarios.ALL.stream()
                .map(BatchScenario::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, expected.compareTo(response.metrics().revenueAtRisk()));
        assertEquals(BatchScenarios.ALL.size(), response.metrics().batchSize());
    }

    @Test
    void policyEligibleAndBlocked_matchTheFixedDatasetsKnownPolicyOutcomes() {
        BatchEvaluationResponse response = service.evaluate();

        // batch_repeated_failure_link, batch_insufficient_funds,
        // batch_mandate_invalid, batch_verified_recovery are the only four
        // scenarios with every P01-P07 fact satisfied.
        assertEquals(4, response.metrics().policyEligible());
        assertEquals(6, response.metrics().policyBlocked());
        assertEquals(10, response.metrics().policyEligible() + response.metrics().policyBlocked());
    }

    @Test
    void verifiedRecoveries_countsOnlyTheOneScriptedAsVerified() {
        BatchEvaluationResponse response = service.evaluate();

        assertEquals(1, response.metrics().verifiedRecoveries());
        assertEquals(0, new BigDecimal("2000.00").compareTo(response.metrics().revenueRecovered()));
    }

    @Test
    void recoveryRate_isRevenueRecoveredDividedByRevenueAtRisk() {
        BatchEvaluationResponse response = service.evaluate();

        BigDecimal expected = BatchEvaluationService.recoveryRate(
                response.metrics().revenueRecovered(), response.metrics().revenueAtRisk());

        assertEquals(0, expected.compareTo(response.metrics().recoveryRate()));
        assertTrue(response.metrics().recoveryRate().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(response.metrics().recoveryRate().compareTo(BigDecimal.ONE) < 0);
    }

    @Test
    void unknownSettlementAndUnknownSubscription_bothResultInBlocked() {
        Map<String, BatchItemResult> byId = itemsById(service.evaluate());

        assertEquals("BLOCKED", byId.get("batch_unknown_settlement").policyResult());
        assertTrue(byId.get("batch_unknown_settlement").policyChecks().stream()
                .anyMatch(check -> "not_already_settled_elsewhere".equals(check.checkName()) && !check.passed()));

        assertEquals("BLOCKED", byId.get("batch_unknown_subscription").policyResult());
        assertTrue(byId.get("batch_unknown_subscription").policyChecks().stream()
                .anyMatch(check -> "subscription_state_valid".equals(check.checkName()) && !check.passed()));
    }

    @Test
    void executedButUnverifiedItem_doesNotCountTowardRecoveredRevenue() {
        Map<String, BatchItemResult> byId = itemsById(service.evaluate());
        BatchItemResult executedOnly = byId.get("batch_repeated_failure_link");

        assertEquals("EXECUTED_AWAITING_VERIFICATION", executedOnly.outcome());
        assertNull(executedOnly.recoveredAmount());
    }

    @Test
    void revenueRecovered_neverIncludesAnExecutedButUnverifiedItem() {
        BatchEvaluationResponse response = service.evaluate();

        BigDecimal sumOfNonVerifiedAmounts = response.items().stream()
                .filter(item -> !"VERIFIED_RECOVERED".equals(item.outcome()))
                .map(BatchItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // batch_repeated_failure_link (1500.00) is executed but unverified -
        // if revenueRecovered ever accidentally included it, this would fail.
        assertTrue(sumOfNonVerifiedAmounts.compareTo(BigDecimal.ZERO) > 0);
        assertEquals(0, new BigDecimal("2000.00").compareTo(response.metrics().revenueRecovered()));
    }

    @Test
    void recoveryRate_zeroRevenueAtRisk_returnsZeroWithoutDivisionByZero() {
        BigDecimal rate = BatchEvaluationService.recoveryRate(BigDecimal.ZERO, BigDecimal.ZERO);

        assertEquals(0, BigDecimal.ZERO.compareTo(rate));
    }

    @Test
    void safetyCounters_areAllZero_provingNoActionEverBypassesPolicyOrStrategy() {
        BatchEvaluationResponse response = service.evaluate();

        assertEquals(0, response.safety().unauthorizedActions());
        assertEquals(0, response.safety().policyViolations());
        assertEquals(0, response.safety().duplicatePendingActions());
        assertEquals(0, response.safety().unverifiedRecoveries());
    }

    @Test
    void everyBatchItem_carriesItemLevelEvidence_forItsAggregatedOutcome() {
        BatchEvaluationResponse response = service.evaluate();

        for (BatchItemResult item : response.items()) {
            assertTrue(item.diagnosisCategory() != null && !item.diagnosisCategory().isBlank());
            assertTrue(item.strategy() != null && !item.strategy().isBlank());
            assertTrue(item.policyResult() != null && !item.policyResult().isBlank());
            assertEquals(7, item.policyChecks().size(), "all 7 policy checks must be present for " + item.externalPaymentId());
        }
    }

    private Map<String, BatchItemResult> itemsById(BatchEvaluationResponse response) {
        return response.items().stream()
                .collect(java.util.stream.Collectors.toMap(BatchItemResult::externalPaymentId, item -> item));
    }
}
