package com.recoversense.repository;

import com.recoversense.domain.RecoveryCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryCaseRepository extends JpaRepository<RecoveryCase, Long> {
}
