package com.recoversense.repository;

import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryCaseStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByExternalPaymentId(String externalPaymentId);

    /**
     * At-risk = failedStatus payments with no RecoveryCase in any of
     * activeStatuses (see M1.22 ADR-012 / docs/DECISIONS.md: OPEN means
     * already in recovery, RECOVERED means RecoverSense already fixed it -
     * both are excluded, regardless of Payment.status, which intentionally
     * never changes away from FAILED). Callers pass the status/statuses
     * explicitly rather than this query hardcoding them, so the "at risk"
     * definition stays visible at the call site.
     */
    @Query("""
            select p from Payment p
            where p.status = :failedStatus
            and not exists (
                select rc from RecoveryCase rc
                where rc.payment = p and rc.status in :activeStatuses
            )
            order by p.failedAt desc
            """)
    List<Payment> findAtRiskPayments(@Param("failedStatus") PaymentStatus failedStatus,
                                      @Param("activeStatuses") List<RecoveryCaseStatus> activeStatuses,
                                      Pageable pageable);
}
