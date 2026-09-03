package com.recoversense.demo;

import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Demo-only operator convenience: resets exactly the hero demo payment
 * ({@link DemoSettlementVerifier#DEMO_PAYMENT_LINK_EXTERNAL_ID}) back to the
 * FAILED state {@link DemoDataSeeder} originally seeded it in, so the
 * Recover -> Payment Link -> Verify walkthrough can be repeated during a
 * live demo without shell/database access.
 * <p>
 * {@code @Profile("demo")}: this bean - and therefore the whole capability -
 * does not exist outside the demo profile. Mirrors scripts/reset-demo-payment-link.sql
 * exactly (same FK-ordered deletes, same restored failure_reason/failed_at),
 * just reachable over HTTP instead of psql. Never accepts a payment/case id
 * from the caller - the target is always the one hardcoded external id.
 */
@Service
@Profile("demo")
public class DemoResetService {

    static final String FAILURE_REASON = "Repeated payment failure - card declined multiple times";

    private final PaymentRepository paymentRepository;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final AuditEventRepository auditEventRepository;

    public DemoResetService(PaymentRepository paymentRepository,
                             RecoveryCaseRepository recoveryCaseRepository,
                             RecoveryDecisionRepository recoveryDecisionRepository,
                             RecoveryActionRepository recoveryActionRepository,
                             AuditEventRepository auditEventRepository) {
        this.paymentRepository = paymentRepository;
        this.recoveryCaseRepository = recoveryCaseRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * One transaction: deletes this payment's recovery pipeline rows in FK
     * order (audit_events -> recovery_actions -> recovery_decisions ->
     * recovery_cases, matching V1/V3's foreign keys) then restores the
     * payments row. Idempotent - re-running against an already-FAILED,
     * case-less payment simply finds nothing to delete. Never resets any
     * sequence, never touches any other payment/customer.
     */
    @Transactional
    public DemoResetResponse resetHeroPaymentLink() {
        Payment payment = paymentRepository.findByExternalPaymentId(DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        DemoSettlementVerifier.DEMO_PAYMENT_LINK_EXTERNAL_ID + " is not seeded on this server"));

        List<RecoveryCase> cases = recoveryCaseRepository.findByPayment(payment);
        if (!cases.isEmpty()) {
            auditEventRepository.deleteByRecoveryCaseIn(cases);
            recoveryActionRepository.deleteByRecoveryCaseIn(cases);
            recoveryDecisionRepository.deleteByRecoveryCaseIn(cases);
            recoveryCaseRepository.deleteAll(cases);
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(FAILURE_REASON);
        payment.setFailedAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        paymentRepository.save(payment);

        return new DemoResetResponse(true, payment.getExternalPaymentId(), payment.getStatus().name());
    }
}
