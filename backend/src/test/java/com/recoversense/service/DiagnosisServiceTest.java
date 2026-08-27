package com.recoversense.service;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.ExecutionStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.PolicyResult;
import com.recoversense.domain.RecoveryAction;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class DiagnosisServiceTest {

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
    private DiagnosisService diagnosisService;
    @Autowired
    private RecoveryLifecycleService recoveryLifecycleService;

    private Payment seedPayment(String suffix, String failureReason, CustomerStatus customerStatus) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(customerStatus);
        customerRepository.save(customer);

        Payment payment = new Payment("pay_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setFailureReason(failureReason);
        return paymentRepository.save(payment);
    }

    @Test
    void diagnose_mandateRevokedPayment_producesMandateInvalidInput() {
        Payment payment = seedPayment("1", "mandate_revoked", CustomerStatus.ACTIVE);

        RecoveryDiagnosisInput input = diagnosisService.diagnose(payment.getId());

        assertEquals("MANDATE_INVALID", input.diagnosisCategory());
        assertEquals("REACQUIRE_MANDATE", input.strategy());
        assertEquals("REACQUIRE_MANDATE", input.actionType());
    }

    @Test
    void diagnose_retryCountIsDerivedFromExecutedActions() {
        Payment payment = seedPayment("2", "card_declined", CustomerStatus.ACTIVE);
        RecoveryCase recoveryCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        for (int i = 0; i < 3; i++) {
            RecoveryDecision decision = recoveryDecisionRepository.save(new RecoveryDecision(
                    recoveryCase, "TEMPORARY_FAILURE", new BigDecimal("0.4000"), "raw", "WAIT_RETRY"));
            RecoveryAction action = recoveryActionRepository.save(
                    new RecoveryAction(decision, "WAIT_RETRY_" + i, PolicyResult.ALLOWED));
            action.setExecutionStatus(ExecutionStatus.EXECUTED);
            recoveryActionRepository.save(action);
        }

        RecoveryDiagnosisInput input = diagnosisService.diagnose(payment.getId());

        assertEquals("REPEATED_FAILURE", input.diagnosisCategory());
        assertEquals("PAYMENT_LINK", input.actionType());
    }

    @Test
    void diagnose_unknownPayment_isRejected() {
        assertThrows(PaymentNotFoundException.class, () -> diagnosisService.diagnose(-1L));
    }

    @Test
    void diagnosisOutput_feedsDirectlyIntoRecoveryLifecycleService() {
        Payment payment = seedPayment("3", "mandate_revoked", CustomerStatus.ACTIVE);

        RecoveryDiagnosisInput diagnosisInput = diagnosisService.diagnose(payment.getId());
        RecoveryLifecycleResult result = recoveryLifecycleService.processFailedPayment(payment.getId(), diagnosisInput);

        assertEquals("MANDATE_INVALID", result.decision().getDiagnosisCategory());
        assertEquals("REACQUIRE_MANDATE", result.decision().getStrategy());
        // Real policy flow still BLOCKs (settlement state unknown, M1.4) - the
        // diagnosis module does not and must not weaken that.
        assertEquals(PolicyResult.BLOCKED, result.policyDecision().result());
    }
}
