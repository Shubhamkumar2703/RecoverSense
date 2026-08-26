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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Append-only audit trail entry. No setters are exposed and every column is
 * updatable = false: once written, an audit event must not be mutated.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recovery_case_id", nullable = false, updatable = false)
    private RecoveryCase recoveryCase;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload", updatable = false)
    private String eventPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(RecoveryCase recoveryCase, String eventType, String eventPayload) {
        this.recoveryCase = recoveryCase;
        this.eventType = eventType;
        this.eventPayload = eventPayload;
    }

    public Long getId() {
        return id;
    }

    public RecoveryCase getRecoveryCase() {
        return recoveryCase;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventPayload() {
        return eventPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
