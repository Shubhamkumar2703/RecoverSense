package com.recoversense.policy;

import com.recoversense.domain.Customer;
import com.recoversense.domain.Payment;

import java.time.Instant;

/**
 * Input to a single policy evaluation. Fields not present on the existing
 * domain entities (retry count, pending re-acquisition, settlement elsewhere)
 * are supplied by the caller rather than invented as new persisted state.
 * Any of these boxed fields being null means "unknown" and must fail closed.
 */
public record PolicyContext(
        Customer customer,
        Payment payment,
        Integer retryCount,
        Boolean pendingReacquisitionExists,
        Boolean alreadySettledElsewhere,
        Instant evaluatedAt
) {
}
