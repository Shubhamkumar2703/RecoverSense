package com.recoversense.repository;

import com.recoversense.domain.RecoveryAction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryActionRepository extends JpaRepository<RecoveryAction, Long> {
}
