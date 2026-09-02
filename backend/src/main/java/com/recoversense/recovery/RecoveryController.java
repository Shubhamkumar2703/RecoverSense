package com.recoversense.recovery;

import com.recoversense.service.InvalidActionTransitionException;
import com.recoversense.service.InvalidPaymentStateException;
import com.recoversense.service.PaymentAlreadyRecoveredException;
import com.recoversense.service.PaymentNotFoundException;
import com.recoversense.service.RecoveryActionNotFoundException;
import com.recoversense.service.RecoveryCaseNotFoundException;
import com.recoversense.service.RecoveryOrchestrationResult;
import com.recoversense.service.RecoveryOrchestrationService;
import com.recoversense.service.RecoveryOutcome;
import com.recoversense.service.RecoveryVerificationResult;
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

    /**
     * M1.25 phase 2: independently re-verifies the action recover() already
     * executed for this case. Never creates a case/decision/action and never
     * calls an executor - see RecoveryOrchestrationService#verify. Keyed by
     * recoveryCaseId (not actionId) because that is the identifier the
     * initial recover() response already gave the caller.
     */
    @PostMapping("/cases/{recoveryCaseId}/verify")
    public ResponseEntity<RecoveryResponse> verify(@PathVariable Long recoveryCaseId) {
        RecoveryVerificationResult result = recoveryOrchestrationService.verify(recoveryCaseId);
        Long paymentId = result.recoveryCase().getPayment().getId();
        return ResponseEntity.status(statusFor(result.outcome()))
                .body(RecoveryResponse.fromVerification(paymentId, result));
    }

    private HttpStatus statusFor(RecoveryOutcome outcome) {
        return switch (outcome) {
            case EXECUTION_UNAVAILABLE, VERIFICATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RECOVERED, BLOCKED, EXECUTION_FAILED, VERIFICATION_FAILED, EXECUTED_AWAITING_VERIFICATION -> HttpStatus.OK;
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
     * M1.22: the payment already has a RECOVERED RecoveryCase. Same status
     * family as InvalidPaymentStateException - both are "this payment isn't
     * in a state that allows starting a new recovery" - reported as a
     * conflict, never a fabricated success or a raw 500.
     */
    @ExceptionHandler(PaymentAlreadyRecoveredException.class)
    public ResponseEntity<ErrorResponse> handlePaymentAlreadyRecovered(PaymentAlreadyRecoveredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * M1.25: the caseId path variable on POST /cases/{recoveryCaseId}/verify
     * does not identify a case at all.
     */
    @ExceptionHandler(RecoveryCaseNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRecoveryCaseNotFound(RecoveryCaseNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * M1.25: the case has no recovery action to verify at all (e.g. policy
     * blocked it, so nothing was ever created).
     */
    @ExceptionHandler(RecoveryActionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRecoveryActionNotFound(RecoveryActionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * M1.25: verify() was called for an action that isn't in a verifiable
     * state (e.g. never reached EXECUTED) - same "not currently in a state
     * that allows this operation" family as InvalidPaymentStateException.
     */
    @ExceptionHandler(InvalidActionTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidActionTransition(InvalidActionTransitionException ex) {
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
