package com.recoversense.repository;

import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface RecoveryCaseRepository extends JpaRepository<RecoveryCase, Long> {

    Optional<RecoveryCase> findByPaymentAndStatus(Payment payment, RecoveryCaseStatus status);

    List<RecoveryCase> findByPayment(Payment payment);

    boolean existsByPaymentAndStatus(Payment payment, RecoveryCaseStatus status);

    List<RecoveryCase> findTop20ByOrderByOpenedAtDesc();

    long countByStatus(RecoveryCaseStatus status);

    @Query("select coalesce(sum(rc.payment.amount), 0) from RecoveryCase rc")
    BigDecimal sumPaymentAmount();

    @Query("select coalesce(sum(rc.payment.amount), 0) from RecoveryCase rc where rc.status = :status")
    BigDecimal sumPaymentAmountByStatus(@Param("status") RecoveryCaseStatus status);
}
