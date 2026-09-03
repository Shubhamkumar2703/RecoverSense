package com.recoversense.dashboard;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.domain.VerificationStatus;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.35: regression coverage for persisting RecoveryAction.providerUrl
 * (previously @Transient - lost the moment the HTTP response that created it
 * was gone). Deliberately NOT @Transactional at the class level, same
 * reasoning as RecoveryActionUnavailableAuditPersistenceTest: a shared test
 * transaction would let a later read see the same in-memory/session state
 * even if the column were never actually persisted, masking exactly the bug
 * this class exists to catch. Seeds RecoveryAction state directly (rather
 * than driving it through RecoveryOrchestrationService) - the orchestration
 * wiring itself is already covered by RecoveryOrchestrationServiceTest; this
 * class only needs to prove providerUrl survives a real, independent
 * persistence round trip and is exposed by the dashboard endpoint.
 */
@SpringBootTest
class RecoveryActionProviderUrlPersistenceTest {

    private static final String PAYMENT_LINK_URL = "https://rzp.io/i/test123";

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private RecoveryDecisionRepository recoveryDecisionRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private DashboardMetricsService dashboardMetricsService;

    private RecoveryAction seedExecutedPaymentLinkAction(String suffix) {
        Customer customer = new Customer("cust_purl_" + suffix, "user+purl" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_purl_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        paymentRepository.save(payment);
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        RecoveryDecision decision = recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "REPEATED_FAILURE", new BigDecimal("0.9000"), "raw", "PAYMENT_LINK"));

        RecoveryAction action = new RecoveryAction(decision, "PAYMENT_LINK", PolicyResult.ALLOWED);
        action.setExecutionStatus(ExecutionStatus.EXECUTED);
        action.setExecutedAt(Instant.now());
        action.setExternalReference("plink_" + suffix);
        action.setProviderUrl(PAYMENT_LINK_URL);
        return recoveryActionRepository.save(action);
    }

    private void cleanUp(RecoveryDecision decision, RecoveryAction action) {
        auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(decision.getRecoveryCase().getId()))
                .forEach(e -> auditEventRepository.deleteById(e.getId()));
        recoveryActionRepository.deleteById(action.getId());
        recoveryDecisionRepository.deleteById(decision.getId());
        recoveryCaseRepository.deleteById(decision.getRecoveryCase().getId());
        paymentRepository.deleteById(decision.getRecoveryCase().getPayment().getId());
        customerRepository.deleteById(decision.getRecoveryCase().getPayment().getCustomer().getId());
    }

    private long countActionsForCase(Long recoveryCaseId) {
        return recoveryActionRepository.findAll().stream()
                .filter(a -> a.getRecoveryCase().getId().equals(recoveryCaseId))
                .count();
    }

    private RecentCaseSummary dashboardRowFor(Long recoveryCaseId) {
        return dashboardMetricsService.buildDashboard().recentCases().stream()
                .filter(c -> c.recoveryCaseId().equals(recoveryCaseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected case " + recoveryCaseId + " not present in dashboard recentCases"));
    }

    /**
     * The direct persistence proof: set providerUrl, save, then read it back
     * through a completely independent repository call (not the same Java
     * object) and through the dashboard endpoint's own DTO mapping - both
     * must see the real committed value, not just what's still sitting in
     * this test's local variable. Repeating the dashboard read proves a
     * later fetch returns the identical link without regenerating anything.
     */
    @Test
    void savedProviderUrl_survivesAFreshRepositoryReadAndIsExposedByTheDashboardEndpoint() {
        RecoveryAction action = seedExecutedPaymentLinkAction("1");
        RecoveryDecision decision = action.getRecoveryDecision();
        Long recoveryCaseId = decision.getRecoveryCase().getId();

        // Fresh query, independent of the object above - proves the DB
        // column round-trips, not just the in-memory field.
        RecoveryAction reloaded = recoveryActionRepository.findById(action.getId()).orElseThrow();
        assertEquals(PAYMENT_LINK_URL, reloaded.getProviderUrl(),
                "providerUrl must survive an independent re-fetch, not just live on the original object");

        RecentCaseSummary row = dashboardRowFor(recoveryCaseId);
        assertEquals(PAYMENT_LINK_URL, row.providerUrl(),
                "the dashboard endpoint's RecentCaseSummary must expose the same persisted providerUrl");
        assertEquals("EXECUTED", row.executionStatus());
        assertEquals("OPEN", row.caseStatus());

        RecentCaseSummary rowAgain = dashboardRowFor(recoveryCaseId);
        assertEquals(row.providerUrl(), rowAgain.providerUrl(), "repeated reads must return the identical Payment Link");
        assertEquals(1, countActionsForCase(recoveryCaseId), "still exactly one RecoveryAction for this case after two dashboard reads");

        cleanUp(decision, action);
    }

    /**
     * Verification semantics untouched: transitioning the action/case to
     * VERIFIED/RECOVERED (exactly what RecoveryActionVerificationService and
     * RecoveryLifecycleService.transitionCase do to real rows) must never
     * clear or regenerate the already-persisted Payment Link - it remains
     * visible as historical evidence, per the same source of truth.
     */
    @Test
    void reachingVerifiedAndRecovered_doesNotChangeOrClearThePersistedProviderUrl() {
        RecoveryAction action = seedExecutedPaymentLinkAction("2");
        RecoveryDecision decision = action.getRecoveryDecision();
        RecoveryCase recoveryCase = decision.getRecoveryCase();
        Long recoveryCaseId = recoveryCase.getId();

        action.setVerificationStatus(VerificationStatus.VERIFIED);
        action.setVerifiedAt(Instant.now());
        recoveryActionRepository.save(action);
        recoveryCase.setStatus(RecoveryCaseStatus.RECOVERED);
        recoveryCaseRepository.save(recoveryCase);

        RecoveryAction reloaded = recoveryActionRepository.findById(action.getId()).orElseThrow();
        assertEquals(PAYMENT_LINK_URL, reloaded.getProviderUrl(), "verification must never clear or regenerate the persisted Payment Link");
        assertEquals(VerificationStatus.VERIFIED, reloaded.getVerificationStatus());

        RecentCaseSummary row = dashboardRowFor(recoveryCaseId);
        assertEquals(PAYMENT_LINK_URL, row.providerUrl());
        assertEquals("VERIFIED", row.verificationStatus());
        assertEquals("RECOVERED", row.caseStatus());
        assertEquals(1, countActionsForCase(recoveryCaseId), "reaching RECOVERED must never create a second RecoveryAction");

        cleanUp(decision, action);
    }
}
