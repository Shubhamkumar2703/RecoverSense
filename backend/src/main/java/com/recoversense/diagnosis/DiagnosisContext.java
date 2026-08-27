package com.recoversense.diagnosis;

import com.recoversense.domain.CustomerStatus;

/**
 * Deterministic diagnosis input. Deliberately plain values, not JPA
 * entities, so DiagnosisEngine stays a pure, dependency-free function
 * (mirrors PolicyEngine's own separation of pure logic from entity-touching
 * orchestration).
 */
public record DiagnosisContext(
        String failureReason,
        String subscriptionStatus,
        CustomerStatus customerStatus,
        int retryCount
) {
}
