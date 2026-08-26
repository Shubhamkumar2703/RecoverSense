package com.recoversense.repository;

import com.recoversense.domain.RecoveryDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryDecisionRepository extends JpaRepository<RecoveryDecision, Long> {
}
