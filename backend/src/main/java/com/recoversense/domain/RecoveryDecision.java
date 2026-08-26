package com.recoversense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "recovery_decisions")
public class RecoveryDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recovery_case_id", nullable = false, updatable = false)
    private RecoveryCase recoveryCase;

    @Column(name = "diagnosis_category", nullable = false, updatable = false)
    private String diagnosisCategory;

    @Column(name = "diagnosis_confidence", precision = 5, scale = 4, updatable = false)
    private BigDecimal diagnosisConfidence;

    @Column(name = "diagnosis_raw", updatable = false)
    private String diagnosisRaw;

    @Column(nullable = false, updatable = false)
    private String strategy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt = Instant.now();

    protected RecoveryDecision() {
    }

    public RecoveryDecision(RecoveryCase recoveryCase, String diagnosisCategory, BigDecimal diagnosisConfidence,
                             String diagnosisRaw, String strategy) {
        this.recoveryCase = recoveryCase;
        this.diagnosisCategory = diagnosisCategory;
        this.diagnosisConfidence = diagnosisConfidence;
        this.diagnosisRaw = diagnosisRaw;
        this.strategy = strategy;
    }

    public Long getId() {
        return id;
    }

    public RecoveryCase getRecoveryCase() {
        return recoveryCase;
    }

    public String getDiagnosisCategory() {
        return diagnosisCategory;
    }

    public BigDecimal getDiagnosisConfidence() {
        return diagnosisConfidence;
    }

    public String getDiagnosisRaw() {
        return diagnosisRaw;
    }

    public String getStrategy() {
        return strategy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
