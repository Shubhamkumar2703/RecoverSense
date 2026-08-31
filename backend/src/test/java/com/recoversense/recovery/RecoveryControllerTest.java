package com.recoversense.recovery;

import com.recoversense.domain.Customer;
import com.recoversense.domain.CustomerStatus;
import com.recoversense.domain.Payment;
import com.recoversense.domain.PaymentStatus;
import com.recoversense.domain.RecoveryCase;
import com.recoversense.domain.RecoveryCaseStatus;
import com.recoversense.repository.CustomerRepository;
import com.recoversense.repository.PaymentRepository;
import com.recoversense.repository.RecoveryActionRepository;
import com.recoversense.repository.RecoveryCaseRepository;
import com.recoversense.repository.RecoveryDecisionRepository;
import com.recoversense.service.RecoveryLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises RecoveryController against the REAL, unmocked application
 * context (same style as DashboardControllerTest) - no Razorpay/Claude
 * credentials configured, so this proves the guards that live inside
 * RecoveryOrchestrationService/DiagnosisService/RecoveryLifecycleService
 * (payment must exist, payment must be FAILED, PolicyEngine fails closed
 * without settlement info) are genuinely reached through the HTTP boundary,
 * not re-implemented or weakened by the controller. RECOVERED/EXECUTED
 * outcomes need a real or fake provider and are covered separately in
 * RecoveryControllerOutcomeMappingTest, where the orchestration service
 * itself is stubbed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private RecoveryDecisionRepository recoveryDecisionRepository;
    @Autowired
    private RecoveryLifecycleService recoveryLifecycleService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    @Test
    void recoverNonexistentPayment_returnsNotFound_andCreatesNothing() throws Exception {
        long actionsBefore = recoveryActionRepository.count();

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found: 999999999"));

        org.junit.jupiter.api.Assertions.assertEquals(actionsBefore, recoveryActionRepository.count());
    }

    @Test
    void recoverNonFailedPayment_returnsConflict_andCreatesNoRecoveryAction() throws Exception {
        Customer customer = new Customer("cust_api_notfailed", "user+apinotfailed@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_api_notfailed", customer, new BigDecimal("50.00"), "INR", PaymentStatus.SUCCEEDED);
        paymentRepository.save(payment);
        long actionsBefore = recoveryActionRepository.count();

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", payment.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        org.junit.jupiter.api.Assertions.assertEquals(actionsBefore, recoveryActionRepository.count());
    }

    /**
     * No settlement state is seeded (there is no HTTP-reachable way to seed
     * one - by design, that's test-only wiring), so PolicyEngine's
     * not_already_settled_elsewhere check fails closed to UNKNOWN -> BLOCKED,
     * exactly like RazorpayOrchestrationSafetyTest's proven pattern. This
     * proves a policy block is reported as a normal 200 response, not a
     * server error, and that no provider call is ever reached.
     */
    @Test
    void recoverFailedPayment_policyBlocked_returnsOkWithBlockedOutcome_noActionCreated() throws Exception {
        Customer customer = new Customer("cust_api_blocked", "user+apiblocked@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_api_blocked", customer, new BigDecimal("50.00"), "INR", PaymentStatus.FAILED);
        payment.setSubscriptionStatus("active");
        payment.setFailureReason("mandate_revoked");
        payment.setFailedAt(STALE_ENOUGH);
        paymentRepository.save(payment);
        long actionsBefore = recoveryActionRepository.count();

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", payment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BLOCKED"))
                .andExpect(jsonPath("$.executionStatus").doesNotExist())
                .andExpect(jsonPath("$.caseStatus").value("OPEN"));

        org.junit.jupiter.api.Assertions.assertEquals(actionsBefore, recoveryActionRepository.count());
    }

    /**
     * M1.22 Part 8: a payment that already has a RECOVERED case must be
     * rejected through the real HTTP boundary - 409, no raw constraint name,
     * and no new case/decision/action created (proving the guard, not
     * something downstream, is what stopped it).
     */
    @Test
    void recoverPaymentWithRecoveredCase_returnsConflict_createsNothingNew() throws Exception {
        Customer customer = new Customer("cust_api_already_recovered", "user+apialreadyrecovered@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_api_already_recovered", customer, new BigDecimal("50.00"), "INR", PaymentStatus.FAILED);
        payment.setFailureReason("mandate_revoked");
        payment.setFailedAt(STALE_ENOUGH);
        paymentRepository.save(payment);
        RecoveryCase recoveredCase = recoveryCaseRepository.save(new RecoveryCase(payment));
        recoveryLifecycleService.transitionCase(recoveredCase.getId(), RecoveryCaseStatus.RECOVERED);
        long casesBefore = recoveryCaseRepository.count();
        long decisionsBefore = recoveryDecisionRepository.count();
        long actionsBefore = recoveryActionRepository.count();

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", payment.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Payment " + payment.getId() + " already has a RECOVERED recovery case; re-recovery is not permitted"));

        org.junit.jupiter.api.Assertions.assertEquals(casesBefore, recoveryCaseRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(decisionsBefore, recoveryDecisionRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals(actionsBefore, recoveryActionRepository.count());
    }
}
