package com.recoversense.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the at-risk payments list - a FAILED payment with no OPEN or
 * RECOVERED RecoveryCase (see PaymentRepository.findAtRiskPayments). Exposes
 * only what the frontend needs to show the payment and let the operator
 * trigger recovery on it - no customer PII beyond what RecentCaseSummary
 * already exposes, no provider details.
 */
public record AtRiskPaymentSummary(
        Long paymentId,
        String externalPaymentId,
        BigDecimal amount,
        String currency,
        String failureReason,
        Instant failedAt
) {
}
