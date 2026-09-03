package com.recoversense.repository;

import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RecoveryDecisionRepository extends JpaRepository<RecoveryDecision, Long> {

    Optional<RecoveryDecision> findTopByRecoveryCaseOrderByDecidedAtDesc(RecoveryCase recoveryCase);

    void deleteByRecoveryCaseIn(List<RecoveryCase> recoveryCases);

    @Query("select rd.strategy as strategy, count(rd) as total from RecoveryDecision rd group by rd.strategy")
    List<StrategyCount> countGroupedByStrategy();

    interface StrategyCount {
        String getStrategy();

        long getTotal();
    }
}
