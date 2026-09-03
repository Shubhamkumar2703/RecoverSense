package com.recoversense.repository;

import com.recoversense.domain.AuditEvent;
import com.recoversense.domain.RecoveryCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    long countByEventType(String eventType);

    List<AuditEvent> findByRecoveryCase_IdOrderByCreatedAtAsc(Long recoveryCaseId);

    void deleteByRecoveryCaseIn(List<RecoveryCase> recoveryCases);
}
