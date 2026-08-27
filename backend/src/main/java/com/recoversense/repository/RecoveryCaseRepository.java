package com.recoversense.repository;

import com.recoversense.domain.Payment;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecoveryCaseRepository extends JpaRepository<RecoveryCase, Long> {

    Optional<RecoveryCase> findByPaymentAndStatus(Payment payment, RecoveryCaseStatus status);
}
