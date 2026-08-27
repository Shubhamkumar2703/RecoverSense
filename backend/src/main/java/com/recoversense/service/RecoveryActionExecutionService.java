package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.RecoveryActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Execution boundary for a RecoveryAction. Enforces that only a PENDING,
 * policy-ALLOWED action can move to EXECUTED, and that an execution attempt
 * never fabricates a VERIFIED outcome - execution and verification are
 * always separate transitions.
 * <p>
 * No Razorpay/HTTP client is wired here or anywhere reachable from this
 * class; {@link NotImplementedRecoveryActionExecutor} always throws
 * {@link UnsupportedOperationException}, which this service surfaces
 * honestly (as an "unavailable" audit event, action left untouched) rather
 * than swallowing it into a fabricated result.
 * <p>
 * attemptExecution is annotated with noRollbackFor on that one exception
 * type: without it, a caller outside any ambient transaction (the real
 * application path - see RecoveryOrchestrationService) makes this method
 * the transaction's own owner, and rethrowing after the audit save would
 * trigger Spring's default rollback-on-RuntimeException behavior, discarding
 * the very audit event that records the failure - confirmed empirically.
 * noRollbackFor commits normally (including that audit row) while still
 * letting the exception propagate to the caller.
 */
@Service
public class RecoveryActionExecutionService {

    private final RecoveryActionRepository recoveryActionRepository;
    private final AuditEventRepository auditEventRepository;
    private final RecoveryActionExecutor recoveryActionExecutor;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RecoveryActionExecutionService(RecoveryActionRepository recoveryActionRepository,
                                           AuditEventRepository auditEventRepository,
                                           RecoveryActionExecutor recoveryActionExecutor) {
        this.recoveryActionRepository = recoveryActionRepository;
        this.auditEventRepository = auditEventRepository;
        this.recoveryActionExecutor = recoveryActionExecutor;
    }

    @Transactional(noRollbackFor = UnsupportedOperationException.class)
    public RecoveryAction attemptExecution(Long recoveryActionId) {
        RecoveryAction action = recoveryActionRepository.findById(recoveryActionId)
                .orElseThrow(() -> new RecoveryActionNotFoundException(recoveryActionId));

        if (action.getPolicyResult() != PolicyResult.ALLOWED) {
            throw new InvalidActionTransitionException(
                    "Cannot execute RecoveryAction " + recoveryActionId + ": policyResult is " + action.getPolicyResult() + ", expected ALLOWED");
        }
        if (action.getExecutionStatus() != ExecutionStatus.PENDING) {
            throw new InvalidActionTransitionException(
                    "Cannot execute RecoveryAction " + recoveryActionId + ": executionStatus is " + action.getExecutionStatus() + ", expected PENDING");
        }

        ExecutionStatus outcome;
        try {
            outcome = recoveryActionExecutor.execute(action);
        } catch (UnsupportedOperationException unavailable) {
            auditEventRepository.save(new AuditEvent(action.getRecoveryDecision().getRecoveryCase(), "ACTION_EXECUTION_UNAVAILABLE",
                    toJson(new ExecutionUnavailablePayload(action.getId(), Instant.now()))));
            throw unavailable;
        }
        if (outcome == ExecutionStatus.PENDING) {
            throw new IllegalStateException("RecoveryActionExecutor must not return PENDING");
        }

        action.setExecutionStatus(outcome);
        action.setExecutedAt(Instant.now());
        recoveryActionRepository.save(action);

        auditEventRepository.save(new AuditEvent(action.getRecoveryDecision().getRecoveryCase(), "ACTION_EXECUTION_ATTEMPTED",
                toJson(new ExecutionAttemptedPayload(action.getId(), outcome.name(), action.getExecutedAt()))));

        return action;
    }

    private String toJson(Object payload) {
        return jsonMapper.writeValueAsString(payload);
    }

    private record ExecutionAttemptedPayload(Long recoveryActionId, String outcome, Instant executedAt) {
    }

    private record ExecutionUnavailablePayload(Long recoveryActionId, Instant attemptedAt) {
    }
}
