package com.recoversense.service;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.repository.AuditEventRepository;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import com.recoversense.settlement.SettlementState;
import com.recoversense.settlement.SimulatedSettlementVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deterministic concurrency proof for the V3 partial unique index - no
 * sleeps. Two real transactions, each running the exact real
 * RecoveryPolicyService.evaluate() (the no_pending_reacquisition read from
 * the M1.11 follow-up investigation) then the exact real
 * RecoveryActionService.createIfAllowed(...) (the write), forced by a
 * CountDownLatch to both complete their read before either is allowed to
 * proceed to the write. That barrier reproduces the precise TOCTOU
 * precondition - both transactions observing "no pending action" - rather
 * than relying on incidental thread scheduling to (maybe) hit it. Without
 * the barrier this test could pass "by luck" via the pre-existing,
 * per-decision RecoveryActionService.createIfAllowed check alone, without
 * ever exercising the new DB constraint at all.
 * <p>
 * Deliberately NOT @Transactional at the class level: each race attempt
 * manages its own transaction explicitly via TransactionTemplate on its own
 * thread, reproducing genuinely independent connections - the same
 * requirement that made the audit-loss regression test in the M1.11
 * follow-up need to avoid a shared test transaction.
 */
@SpringBootTest
class RecoveryActionConcurrentPendingCreationTest {

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
    private RecoveryActionService recoveryActionService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    @Test
    void twoConcurrentTransactions_bothPassingThePendingCheck_onlyOneCreatesAPendingAction() throws Exception {
        Customer customer = new Customer("cust_concurrent_race", "user+concurrentrace@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_concurrent_race", customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailedAt(STALE_ENOUGH);
        paymentRepository.save(payment);
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        RecoveryDecision decisionA = recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "MANDATE_INVALID", new BigDecimal("0.9000"), "raw", "REACQUIRE_MANDATE"));
        RecoveryDecision decisionB = recoveryDecisionRepository.save(new RecoveryDecision(
                recoveryCase, "MANDATE_INVALID", new BigDecimal("0.9000"), "raw", "REACQUIRE_MANDATE"));

        SimulatedSettlementVerifier simulator = new SimulatedSettlementVerifier()
                .seedSettlementState("pay_concurrent_race", SettlementState.NOT_SETTLED);
        RecoveryPolicyService policyServiceWithSimulator = new RecoveryPolicyService(
                recoveryCaseRepository, recoveryActionRepository, auditEventRepository, simulator);

        CountDownLatch bothCheckedPending = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean succeededA;
        boolean succeededB;
        try {
            Future<Boolean> resultA = executor.submit(
                    raceAttempt(recoveryCase.getId(), decisionA, policyServiceWithSimulator, bothCheckedPending));
            Future<Boolean> resultB = executor.submit(
                    raceAttempt(recoveryCase.getId(), decisionB, policyServiceWithSimulator, bothCheckedPending));

            succeededA = resultA.get(30, TimeUnit.SECONDS);
            succeededB = resultB.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertTrue(succeededA ^ succeededB,
                "exactly one of the two concurrent attempts must succeed in creating the PENDING action, got A=" + succeededA + " B=" + succeededB);

        List<RecoveryAction> pendingActions = recoveryActionRepository.findAll().stream()
                .filter(a -> a.getRecoveryCase().getId().equals(recoveryCase.getId()))
                .filter(a -> "REACQUIRE_MANDATE".equals(a.getActionType()))
                .filter(a -> a.getExecutionStatus() == ExecutionStatus.PENDING)
                .toList();
        assertEquals(1, pendingActions.size(), "exactly one PENDING RecoveryAction must survive the race");

        cleanUp(recoveryCase, decisionA, decisionB, payment, customer);
    }

    private Callable<Boolean> raceAttempt(Long caseId, RecoveryDecision decision, RecoveryPolicyService policyService,
                                           CountDownLatch bothCheckedPending) {
        return () -> {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            try {
                return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    PolicyDecision policyDecision = policyService.evaluate(caseId, "REACQUIRE_MANDATE");
                    awaitBoth(bothCheckedPending);
                    return recoveryActionService.createIfAllowed(decision, "REACQUIRE_MANDATE", policyDecision).isPresent();
                }));
            } catch (DataIntegrityViolationException lostTheRace) {
                return false;
            }
        };
    }

    private void awaitBoth(CountDownLatch latch) {
        latch.countDown();
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                fail("both concurrent attempts must reach the pending-check barrier within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting at the pending-check barrier");
        }
    }

    private void cleanUp(RecoveryCase recoveryCase, RecoveryDecision decisionA, RecoveryDecision decisionB,
                          Payment payment, Customer customer) {
        recoveryActionRepository.findAll().stream()
                .filter(a -> a.getRecoveryCase().getId().equals(recoveryCase.getId()))
                .forEach(a -> recoveryActionRepository.deleteById(a.getId()));
        auditEventRepository.findAll().stream()
                .filter(e -> e.getRecoveryCase().getId().equals(recoveryCase.getId()))
                .forEach(e -> auditEventRepository.deleteById(e.getId()));
        recoveryDecisionRepository.deleteById(decisionA.getId());
        recoveryDecisionRepository.deleteById(decisionB.getId());
        recoveryCaseRepository.deleteById(recoveryCase.getId());
        paymentRepository.deleteById(payment.getId());
        customerRepository.deleteById(customer.getId());
    }
}
