package com.recoversense.recovery;

import com.recoversense.service.InvalidPaymentStateException;
import com.recoversense.service.PaymentNotFoundException;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryOutcome;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP front door for the recovery pipeline proven in M1.16-M1.19. Contains
 * no recovery logic of its own - the only call into recovery machinery is
 * {@link RecoveryOrchestrationService#recover}, which already owns the exact
 * Diagnosis -> Lifecycle -> Policy -> Execution -> Verification -> Case
 * transition -> Audit sequence. Never wires a provider client, executor, or
 * verifier directly (compare RazorpayAutoConfiguration/ClaudeAutoConfiguration -
 * this class is deliberately unaware either exists).
 * <p>
 * Status mapping: RECOVERED/BLOCKED/EXECUTION_FAILED/VERIFICATION_FAILED are
 * all complete, honest outcomes of a pipeline that ran to completion - none
 * of them is a server error, so all return 200 with {@code outcome} telling
 * the caller what actually happened (never collapse EXECUTED-but-unverified
 * into "recovered"). EXECUTION_UNAVAILABLE/VERIFICATION_UNAVAILABLE mean a
 * required provider isn't wired up at all (NotImplementedRecoveryAction*) -
 * that is a genuine service-availability problem, so those map to 503.
 */
@RestController
@RequestMapping("/api/recovery")
public class RecoveryController {

    private final RecoveryOrchestrationService recoveryOrchestrationService;

    public RecoveryController(RecoveryOrchestrationService recoveryOrchestrationService) {
        this.recoveryOrchestrationService = recoveryOrchestrationService;
    }

    @PostMapping("/payments/{paymentId}/recover")
    public ResponseEntity<RecoveryResponse> recover(@PathVariable Long paymentId) {
        RecoveryOrchestrationResult result = recoveryOrchestrationService.recover(paymentId);
        return ResponseEntity.status(statusFor(result.outcome()))
                .body(RecoveryResponse.from(paymentId, result));
    }

    private HttpStatus statusFor(RecoveryOutcome outcome) {
        return switch (outcome) {
            case EXECUTION_UNAVAILABLE, VERIFICATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RECOVERED, BLOCKED, EXECUTION_FAILED, VERIFICATION_FAILED -> HttpStatus.OK;
        };
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaymentState(InvalidPaymentStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * The documented TOCTOU-loser path (see RecoveryActionService/V1/V3
     * migration Javadoc): two concurrent recover() calls for the same
     * payment can both pass their own in-transaction checks before either
     * commits: the DB's partial unique indexes are the real guarantee, and
     * the loser here is expected to retry the whole request - never treated
     * as a server error, and never exposing the underlying constraint name.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentRecoveryAttempt(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("A concurrent recovery attempt is already in progress for this payment; retry."));
    }
}
