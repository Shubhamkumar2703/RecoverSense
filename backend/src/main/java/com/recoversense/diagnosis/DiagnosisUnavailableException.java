package com.recoversense.diagnosis;

/**
 * Thrown by a {@link DiagnosisProvider} when a diagnosis cannot be safely
 * produced: provider HTTP/network failure, malformed response, or output
 * that fails validation (unsupported failureType, out-of-range confidence,
 * missing reasoning). Never thrown after fabricating a plausible-looking
 * result - the whole point is to fail closed instead.
 * <p>
 * Uncaught, this propagates out of DiagnosisService.diagnose() the same way
 * PaymentNotFoundException already does - no RecoveryCase/RecoveryAction is
 * ever created, so no financial action can follow a failed diagnosis.
 */
public class DiagnosisUnavailableException extends RuntimeException {

    public DiagnosisUnavailableException(String message) {
        super(message);
    }

    public DiagnosisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
