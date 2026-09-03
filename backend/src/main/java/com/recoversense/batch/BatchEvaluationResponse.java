package com.recoversense.batch;

import java.util.List;

/**
 * {@code datasetLabel} is deliberately part of the payload, not left for the
 * frontend to assume - this is a SIMULATED/EVALUATION dataset (see
 * BatchScenarios), never real Razorpay transactions, and the label travels
 * with the data so no caller can accidentally present it as real money.
 */
public record BatchEvaluationResponse(
        String datasetLabel,
        BatchMetrics metrics,
        BatchSafetySummary safety,
        List<BatchItemResult> items
) {
}
