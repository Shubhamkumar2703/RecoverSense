package com.recoversense.service;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.RecoveryActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Gates RecoveryAction creation on an already-computed PolicyDecision. Never
 * re-implements any of the 7 policy checks - it only reads the result.
 * <p>
 * ALLOWED creates a PENDING action; anything else (BLOCKED, which already
 * covers PolicyEngine's fail-closed-on-unknown-state behavior) creates no
 * executable action at all.
 */
@Service
public class RecoveryActionService {

    private final RecoveryActionRepository recoveryActionRepository;
    private final AuditEventRepository auditEventRepository;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RecoveryActionService(RecoveryActionRepository recoveryActionRepository,
                                  AuditEventRepository auditEventRepository) {
        this.recoveryActionRepository = recoveryActionRepository;
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Creates a PENDING RecoveryAction for the given decision/action type if
     * (and only if) policyDecision.result() is ALLOWED. Returns empty
     * otherwise - the caller must not treat an empty result as an error, it
     * is the expected outcome of a policy BLOCK.
     * <p>
     * Idempotency note: this method's own find-before-create check is scoped
     * to (recoveryDecision, actionType) and is TOCTOU-vulnerable on its own -
     * every processFailedPayment call mints a brand-new RecoveryDecision, so
     * this check alone never sees a sibling action created by a concurrent
     * call for the same payment/case. The real cross-call guarantee is the
     * DB-level uq_recovery_actions_pending_case_action_type partial unique
     * index (V3): at most one PENDING action per (recovery_case_id,
     * action_type). A losing concurrent INSERT here throws
     * DataIntegrityViolationException, which propagates out of this method
     * (and the caller's whole transaction) uncaught - by design, mirroring
     * the existing uq_recovery_cases_open_payment precedent: the loser
     * retries the full operation rather than being silently converted into a
     * fabricated BLOCKED result.
     */
    @Transactional
    public Optional<RecoveryAction> createIfAllowed(RecoveryDecision decision, String actionType, PolicyDecision policyDecision) {
        if (policyDecision.result() != PolicyResult.ALLOWED) {
            auditEventRepository.save(new AuditEvent(decision.getRecoveryCase(), "ACTION_NOT_CREATED",
                    toJson(new ActionNotCreatedPayload(decision.getId(), actionType, policyDecision.result().name(), Instant.now()))));
            return Optional.empty();
        }

        List<RecoveryAction> existing = recoveryActionRepository.findByRecoveryDecisionAndActionType(decision, actionType);
        if (!existing.isEmpty()) {
            return Optional.of(existing.get(0));
        }

        RecoveryAction action = recoveryActionRepository.save(new RecoveryAction(decision, actionType, PolicyResult.ALLOWED));
        auditEventRepository.save(new AuditEvent(decision.getRecoveryCase(), "ACTION_CREATED",
                toJson(new ActionCreatedPayload(action.getId(), decision.getId(), actionType, action.getCreatedAt()))));
        return Optional.of(action);
    }

    private String toJson(Object payload) {
        return jsonMapper.writeValueAsString(payload);
    }

    private record ActionCreatedPayload(Long recoveryActionId, Long recoveryDecisionId, String actionType, Instant createdAt) {
    }

    private record ActionNotCreatedPayload(Long recoveryDecisionId, String actionType, String policyResult, Instant attemptedAt) {
    }
}
