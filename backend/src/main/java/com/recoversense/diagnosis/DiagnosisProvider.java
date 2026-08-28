package com.recoversense.diagnosis;

/**
 * Port for anything that can classify a failed payment into a {@link
 * RecoveryDiagnosis}. Implementations: {@code ClaudeDiagnosisProvider} (real,
 * in {@code com.recoversense.claude}) and {@link SimulatedDiagnosisProvider}
 * (deterministic, always-available default). Neither this interface nor any
 * implementation chooses a strategy or touches execution - see {@link
 * StrategyRouter}.
 */
public interface DiagnosisProvider {

    /**
     * @throws DiagnosisUnavailableException if diagnosis cannot be safely
     *                                        produced (provider failure,
     *                                        invalid/untrusted output). The
     *                                        caller must not proceed into
     *                                        policy/execution when this is
     *                                        thrown.
     */
    RecoveryDiagnosis diagnose(DiagnosisContext context);
}
