package com.recoversense.dashboard;

import java.time.Instant;

/**
 * One entry of a case's audit trail, exposing the existing audit vocabulary
 * (RECOVERY_CASE_OPENED, RECOVERY_DECISION_RECORDED, POLICY_EVALUATED,
 * ACTION_CREATED/ACTION_NOT_CREATED, ACTION_EXECUTION_ATTEMPTED/UNAVAILABLE,
 * ACTION_VERIFICATION_ATTEMPTED/UNAVAILABLE, CASE_STATUS_CHANGED) as-is - see
 * AuditEvent. payload is the raw JSON string already stored on the event;
 * this endpoint does not reshape it.
 */
public record AuditEventSummary(String eventType, String payload, Instant createdAt) {
}
