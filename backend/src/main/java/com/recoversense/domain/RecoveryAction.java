package com.recoversense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "recovery_actions")
public class RecoveryAction extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recovery_decision_id", nullable = false, updatable = false)
    private RecoveryDecision recoveryDecision;

    // Denormalized from recoveryDecision.getRecoveryCase() (see constructor) -
    // not a new relationship to maintain, just the join a partial unique
    // index can't express across recovery_decisions. See V3 migration.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recovery_case_id", nullable = false, updatable = false)
    private RecoveryCase recoveryCase;

    @Column(name = "action_type", nullable = false, updatable = false)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_result", nullable = false, updatable = false)
    private PolicyResult policyResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false)
    private ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "external_reference")
    private String externalReference;

    /**
     * The real hosted Payment Link URL (Razorpay's short_url). Set once, by
     * the executor, the same moment externalReference is set (see
     * RazorpayRecoveryActionExecutor) - never re-fetched, never regenerated,
     * never touched by verify(). Left null for every action that isn't a
     * PAYMENT_LINK.
     * <p>
     * M1.35: persisted (previously @Transient, request-scoped only) - an
     * operator who didn't act on the link in the same HTTP response that
     * created it had no way to retrieve it again; this column is what lets
     * RecentCaseSummary expose the same link on every later read, including
     * after a page refresh, with zero additional provider calls.
     */
    @Column(name = "provider_url")
    private String providerUrl;

    protected RecoveryAction() {
    }

    public RecoveryAction(RecoveryDecision recoveryDecision, String actionType, PolicyResult policyResult) {
        this.recoveryDecision = recoveryDecision;
        this.recoveryCase = recoveryDecision.getRecoveryCase();
        this.actionType = actionType;
        this.policyResult = policyResult;
    }

    public Long getId() {
        return id;
    }

    public RecoveryDecision getRecoveryDecision() {
        return recoveryDecision;
    }

    public RecoveryCase getRecoveryCase() {
        return recoveryCase;
    }

    public String getActionType() {
        return actionType;
    }

    public PolicyResult getPolicyResult() {
        return policyResult;
    }

    public ExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(ExecutionStatus executionStatus) {
        this.executionStatus = executionStatus;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }

    public String getProviderUrl() {
        return providerUrl;
    }

    public void setProviderUrl(String providerUrl) {
        this.providerUrl = providerUrl;
    }
}
