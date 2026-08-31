package com.recoversense.recovery;

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
import com.recoversense.policy.PolicyCheckResult;
import com.recoversense.policy.PolicyDecision;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves RecoveryController's outcome -> HTTP status/body mapping for
 * results the default (no Razorpay/Claude credentials) application context
 * cannot naturally produce - RECOVERED/EXECUTION_FAILED/VERIFICATION_FAILED/
 * *_UNAVAILABLE all require a real or fake provider. RecoveryOrchestrationService
 * is stubbed here for exactly that reason; RecoveryControllerTest instead
 * proves the guards/BLOCKED path against the real, unmocked service. The
 * pipeline logic itself (diagnosis/policy/execution/verification) is already
 * proven elsewhere (M1.16-M1.19) - this class only proves the HTTP layer
 * reports each of those proven outcomes honestly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecoveryControllerOutcomeMappingTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private RecoveryOrchestrationService recoveryOrchestrationService;

    private RecoveryCase fixtureCase(RecoveryCaseStatus status) {
        Customer customer = new Customer("cust_outcome", "user+outcome@example.com");
        customer.setStatus(CustomerStatus.ACTIVE);
        Payment payment = new Payment("pay_outcome", customer, new BigDecimal("500.00"), "INR", PaymentStatus.FAILED);
        RecoveryCase recoveryCase = new RecoveryCase(payment);
        setId(recoveryCase, 1001L);
        recoveryCase.setStatus(status);
        return recoveryCase;
    }

    private RecoveryDecision fixtureDecision(RecoveryCase recoveryCase, String diagnosisCategory, String strategy) {
        return new RecoveryDecision(recoveryCase, diagnosisCategory, new BigDecimal("0.9000"), "raw", strategy);
    }

    private RecoveryAction fixtureAction(RecoveryDecision decision, String actionType, ExecutionStatus executionStatus,
                                          VerificationStatus verificationStatus, String externalReference) {
        RecoveryAction action = new RecoveryAction(decision, actionType, PolicyResult.ALLOWED);
        setId(action, 2002L);
        action.setExecutionStatus(executionStatus);
        action.setVerificationStatus(verificationStatus);
        action.setExternalReference(externalReference);
        return action;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private PolicyDecision allowed() {
        return new PolicyDecision(PolicyResult.ALLOWED, List.of(new PolicyCheckResult("P01", true, "within retry limit")));
    }

    private PolicyDecision blocked() {
        return new PolicyDecision(PolicyResult.BLOCKED, List.of(new PolicyCheckResult("P06", false, "settlement state unknown")));
    }

    @Test
    void recoverFailedPayment_recoveredOutcome_returnsOkWithMappedResponse() throws Exception {
        RecoveryCase recoveryCase = fixtureCase(RecoveryCaseStatus.RECOVERED);
        RecoveryDecision decision = fixtureDecision(recoveryCase, "REPEATED_FAILURE", "PAYMENT_LINK");
        RecoveryAction action = fixtureAction(decision, "PAYMENT_LINK", ExecutionStatus.EXECUTED, VerificationStatus.VERIFIED, "plink_demo123");
        Mockito.when(recoveryOrchestrationService.recover(42L)).thenReturn(
                new RecoveryOrchestrationResult(recoveryCase, decision, allowed(), Optional.of(action), RecoveryOutcome.RECOVERED));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(42))
                .andExpect(jsonPath("$.recoveryCaseId").value(1001))
                .andExpect(jsonPath("$.caseStatus").value("RECOVERED"))
                .andExpect(jsonPath("$.diagnosisCategory").value("REPEATED_FAILURE"))
                .andExpect(jsonPath("$.strategy").value("PAYMENT_LINK"))
                .andExpect(jsonPath("$.policyResult").value("ALLOWED"))
                .andExpect(jsonPath("$.executionStatus").value("EXECUTED"))
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.externalReference").value("plink_demo123"))
                .andExpect(jsonPath("$.outcome").value("RECOVERED"));
    }

    /**
     * The exact M1.18 negative-path shape: execution genuinely succeeded but
     * verification did not confirm it. The response must distinguish
     * "executed" from "verified" rather than collapsing both into one
     * pass/fail flag - this is the property the M1.18 core principle exists
     * to protect.
     */
    @Test
    void recoverFailedPayment_verificationFailedOutcome_distinguishesExecutedFromVerified() throws Exception {
        RecoveryCase recoveryCase = fixtureCase(RecoveryCaseStatus.OPEN);
        RecoveryDecision decision = fixtureDecision(recoveryCase, "REPEATED_FAILURE", "PAYMENT_LINK");
        RecoveryAction action = fixtureAction(decision, "PAYMENT_LINK", ExecutionStatus.EXECUTED, VerificationStatus.FAILED, "plink_unpaid456");
        Mockito.when(recoveryOrchestrationService.recover(43L)).thenReturn(
                new RecoveryOrchestrationResult(recoveryCase, decision, allowed(), Optional.of(action), RecoveryOutcome.VERIFICATION_FAILED));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 43L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionStatus").value("EXECUTED"))
                .andExpect(jsonPath("$.verificationStatus").value("FAILED"))
                .andExpect(jsonPath("$.caseStatus").value("OPEN"))
                .andExpect(jsonPath("$.outcome").value("VERIFICATION_FAILED"));
    }

    @Test
    void recoverFailedPayment_executionFailedOutcome_returnsOk() throws Exception {
        RecoveryCase recoveryCase = fixtureCase(RecoveryCaseStatus.OPEN);
        RecoveryDecision decision = fixtureDecision(recoveryCase, "REPEATED_FAILURE", "PAYMENT_LINK");
        RecoveryAction action = fixtureAction(decision, "PAYMENT_LINK", ExecutionStatus.FAILED, VerificationStatus.UNVERIFIED, null);
        Mockito.when(recoveryOrchestrationService.recover(44L)).thenReturn(
                new RecoveryOrchestrationResult(recoveryCase, decision, allowed(), Optional.of(action), RecoveryOutcome.EXECUTION_FAILED));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 44L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionStatus").value("FAILED"))
                .andExpect(jsonPath("$.outcome").value("EXECUTION_FAILED"));
    }

    @Test
    void recoverFailedPayment_policyBlockedOutcome_returnsOkNoActionInBody() throws Exception {
        RecoveryCase recoveryCase = fixtureCase(RecoveryCaseStatus.OPEN);
        RecoveryDecision decision = fixtureDecision(recoveryCase, "MANDATE_INVALID", "REACQUIRE_MANDATE");
        Mockito.when(recoveryOrchestrationService.recover(45L)).thenReturn(
                new RecoveryOrchestrationResult(recoveryCase, decision, blocked(), Optional.empty(), RecoveryOutcome.BLOCKED));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 45L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BLOCKED"))
                .andExpect(jsonPath("$.executionStatus").doesNotExist());
    }

    @Test
    void recoverFailedPayment_executionUnavailableOutcome_returnsServiceUnavailable() throws Exception {
        RecoveryCase recoveryCase = fixtureCase(RecoveryCaseStatus.OPEN);
        RecoveryDecision decision = fixtureDecision(recoveryCase, "REPEATED_FAILURE", "PAYMENT_LINK");
        RecoveryAction pendingAction = fixtureAction(decision, "PAYMENT_LINK", ExecutionStatus.PENDING, VerificationStatus.UNVERIFIED, null);
        Mockito.when(recoveryOrchestrationService.recover(46L)).thenReturn(
                new RecoveryOrchestrationResult(recoveryCase, decision, allowed(), Optional.of(pendingAction), RecoveryOutcome.EXECUTION_UNAVAILABLE));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 46L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.outcome").value("EXECUTION_UNAVAILABLE"));
    }

    @Test
    void recoverFailedPayment_verificationUnavailableOutcome_returnsServiceUnavailable() throws Exception {
        RecoveryCase recoveryCase = fixtureCase(RecoveryCaseStatus.OPEN);
        RecoveryDecision decision = fixtureDecision(recoveryCase, "REPEATED_FAILURE", "PAYMENT_LINK");
        RecoveryAction executedAction = fixtureAction(decision, "PAYMENT_LINK", ExecutionStatus.EXECUTED, VerificationStatus.UNVERIFIED, "plink_novf789");
        Mockito.when(recoveryOrchestrationService.recover(47L)).thenReturn(
                new RecoveryOrchestrationResult(recoveryCase, decision, allowed(), Optional.of(executedAction), RecoveryOutcome.VERIFICATION_UNAVAILABLE));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 47L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.outcome").value("VERIFICATION_UNAVAILABLE"));
    }

    /**
     * The documented TOCTOU-loser path (RecoveryActionService/V1/V3 Javadoc):
     * proves the HTTP layer reports it as a retryable conflict, never a raw
     * constraint name and never a 500.
     */
    @Test
    void recoverFailedPayment_concurrentAttempt_returnsConflictWithoutLeakingConstraintName() throws Exception {
        Mockito.when(recoveryOrchestrationService.recover(48L))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_recovery_actions_pending_case_action_type\""));

        mockMvc.perform(post("/api/recovery/payments/{paymentId}/recover", 48L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uq_recovery_actions"))));
    }
}
