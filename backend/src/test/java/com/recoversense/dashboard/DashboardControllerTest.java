package com.recoversense.dashboard;

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
import com.recoversense.service.RecoveryLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the HTTP boundary: response shape and, critically, that hitting
 * the read-only dashboard endpoints never creates a RecoveryCase/RecoveryAction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RecoveryCaseRepository recoveryCaseRepository;
    @Autowired
    private RecoveryActionRepository recoveryActionRepository;
    @Autowired
    private RecoveryLifecycleService recoveryLifecycleService;

    private static final Instant STALE_ENOUGH = Instant.now().minus(Duration.ofMinutes(10));

    private Payment seedFailedPayment(String suffix) {
        Customer customer = new Customer("cust_atrisk_" + suffix, "user+atrisk" + suffix + "@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        Payment payment = new Payment("pay_atrisk_" + suffix, customer, new BigDecimal("100.00"), "INR", PaymentStatus.FAILED);
        payment.setFailureReason("card_declined");
        payment.setFailedAt(STALE_ENOUGH);
        return paymentRepository.save(payment);
    }

    /**
     * M1.22 Part 5: proves the at-risk endpoint's filtering, not just its
     * shape - a FAILED payment with no case is included, one with an OPEN
     * case is excluded, one with a RECOVERED case is excluded, and a
     * SUCCEEDED payment is excluded regardless of case state.
     */
    @Test
    void atRiskEndpoint_includesOnlyFailedPaymentsWithNoActiveRecovery() throws Exception {
        Payment noCasePayment = seedFailedPayment("nocase");

        Payment openCasePayment = seedFailedPayment("open");
        recoveryCaseRepository.save(new RecoveryCase(openCasePayment));

        Payment recoveredCasePayment = seedFailedPayment("recovered");
        RecoveryCase recoveredCase = recoveryCaseRepository.save(new RecoveryCase(recoveredCasePayment));
        recoveryLifecycleService.transitionCase(recoveredCase.getId(), RecoveryCaseStatus.RECOVERED);

        Customer succeededCustomer = new Customer("cust_atrisk_succeeded", "user+atrisksucceeded@example.com");
        succeededCustomer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(succeededCustomer);
        Payment succeededPayment = new Payment("pay_atrisk_succeeded", succeededCustomer, new BigDecimal("100.00"), "INR", PaymentStatus.SUCCEEDED);
        paymentRepository.save(succeededPayment);

        mockMvc.perform(get("/api/dashboard/payments/at-risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.paymentId == " + noCasePayment.getId() + ")]").exists())
                .andExpect(jsonPath("$[?(@.paymentId == " + openCasePayment.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$[?(@.paymentId == " + recoveredCasePayment.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$[?(@.paymentId == " + succeededPayment.getId() + ")]").doesNotExist());
    }

    @Test
    void metricsEndpoint_returnsStructuredResponse() throws Exception {
        Customer customer = new Customer("cust_api", "user+api@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        paymentRepository.save(new Payment("pay_api", customer, new BigDecimal("100.00"), "INR", PaymentStatus.FAILED));

        mockMvc.perform(get("/api/dashboard/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.revenueAtRisk").exists())
                .andExpect(jsonPath("$.summary.recoveredRevenue").exists())
                .andExpect(jsonPath("$.summary.recoveryRate").exists())
                .andExpect(jsonPath("$.summary.verifiedActions").exists())
                .andExpect(jsonPath("$.summary.policyBlocks").exists())
                .andExpect(jsonPath("$.strategyMix").isArray())
                .andExpect(jsonPath("$.recentCases").isArray())
                // the payment above has no RecoveryCase yet, so it must not appear
                .andExpect(jsonPath("$.recentCases", empty()));
    }

    @Test
    void auditTrailEndpoint_returnsEmptyForUnknownCase() throws Exception {
        mockMvc.perform(get("/api/dashboard/cases/{id}/audit", -1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void loadingDashboard_mutatesNothing() throws Exception {
        long casesBefore = recoveryCaseRepository.count();
        long actionsBefore = recoveryActionRepository.count();

        mockMvc.perform(get("/api/dashboard/metrics")).andExpect(status().isOk());
        mockMvc.perform(get("/api/dashboard/cases/{id}/audit", 1L)).andExpect(status().isOk());

        assertEquals(casesBefore, recoveryCaseRepository.count());
        assertEquals(actionsBefore, recoveryActionRepository.count());
    }
}
