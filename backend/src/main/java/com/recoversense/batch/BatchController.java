package com.recoversense.batch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTTP boundary for Batch Recovery Evaluation. GET, not POST -
 * evaluation is a pure, side-effect-free computation over a fixed dataset
 * (see BatchEvaluationService), not an action with an effect to trigger.
 */
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private final BatchEvaluationService batchEvaluationService;

    public BatchController(BatchEvaluationService batchEvaluationService) {
        this.batchEvaluationService = batchEvaluationService;
    }

    @GetMapping("/evaluate")
    public BatchEvaluationResponse evaluate() {
        return batchEvaluationService.evaluate();
    }
}
