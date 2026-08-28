package com.recoversense.service;

import com.recoversense.diagnosis.DiagnosisProvider;
import com.recoversense.diagnosis.DiagnosisSource;
import com.recoversense.diagnosis.DiagnosisUnavailableException;
import com.recoversense.diagnosis.RecoveryDiagnosis;
import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.15 safety proof: DiagnosisService never trusts a DiagnosisProvider
 * (Claude or otherwise) for anything beyond classification. Uses a
 * hand-written stub DiagnosisProvider - deliberately not ClaudeDiagnosisProvider,
 * since the point is to prove DiagnosisService's own composition (provider ->
 * StrategyRouter) is correct independent of Claude's own input validation.
 */
@SpringBootTest
@Transactional
class DiagnosisServiceProviderSeparationTest {

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;

    private Payment seedPayment(String suffix) {
        Customer customer = new Customer("cust_" + suffix, "user+" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_" + suffix, customer, new BigDecimal("1000"), "INR", PaymentStatus.FAILED);
        payment.setFailureReason("card_declined");
        return paymentRepository.save(payment);
    }

    @Test
    void strategyIsAlwaysDerivedByStrategyRouter_neverTakenFromTheProvider() {
        Payment payment = seedPayment("provider_sep_1");
        DiagnosisProvider stubProvider = context -> new RecoveryDiagnosis(
                "REPEATED_FAILURE", new BigDecimal("0.5000"), "stub reasoning", DiagnosisSource.CLAUDE);
        DiagnosisService diagnosisService = new DiagnosisService(paymentRepository, recoveryActionRepository, stubProvider);

        RecoveryDiagnosisInput input = diagnosisService.diagnose(payment.getId());

        assertEquals("REPEATED_FAILURE", input.diagnosisCategory());
        // PAYMENT_LINK is what STRATEGY_MATRIX.md maps REPEATED_FAILURE to -
        // this must come from StrategyRouter, not anything the stub returned
        // (the stub has no way to supply a strategy at all - RecoveryDiagnosis
        // has no such field).
        assertEquals("PAYMENT_LINK", input.strategy());
        assertEquals("PAYMENT_LINK", input.actionType());
        assertTrue(input.diagnosisRaw().startsWith("[CLAUDE]"), "raw diagnosis must be tagged with its source");
    }

    @Test
    void unrecognizedFailureType_fromAMisbehavingProvider_failsClosedToEscalate() {
        Payment payment = seedPayment("provider_sep_2");
        // Simulates a provider that skipped its own taxonomy validation -
        // StrategyRouter is the second, independent line of defense.
        DiagnosisProvider misbehavingProvider = context -> new RecoveryDiagnosis(
                "SOMETHING_A_COMPROMISED_PROVIDER_INVENTED", BigDecimal.ONE, "x", DiagnosisSource.CLAUDE);
        DiagnosisService diagnosisService = new DiagnosisService(paymentRepository, recoveryActionRepository, misbehavingProvider);

        RecoveryDiagnosisInput input = diagnosisService.diagnose(payment.getId());

        assertEquals("ESCALATE", input.strategy());
        assertEquals("ESCALATE", input.actionType());
    }

    @Test
    void providerFailure_propagatesUncaught_neverFabricatesADiagnosis() {
        Payment payment = seedPayment("provider_sep_3");
        DiagnosisProvider failingProvider = context -> {
            throw new DiagnosisUnavailableException("simulated Claude outage");
        };
        DiagnosisService diagnosisService = new DiagnosisService(paymentRepository, recoveryActionRepository, failingProvider);

        assertThrows(DiagnosisUnavailableException.class, () -> diagnosisService.diagnose(payment.getId()));
    }
}
