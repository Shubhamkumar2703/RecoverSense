package com.recoversense.service;

import java.time.Instant;
import java.util.List;

/**
 * Structured, bounded shape written into audit_events.event_payload for a
 * policy evaluation. Contains only check names/results/reasons - no
 * payment/customer identifiers or credentials.
 */
record PolicyAuditPayload(String result, Instant evaluatedAt, List<CheckEntry> checks) {

    record CheckEntry(String checkName, boolean passed, String reason) {
    }
}
