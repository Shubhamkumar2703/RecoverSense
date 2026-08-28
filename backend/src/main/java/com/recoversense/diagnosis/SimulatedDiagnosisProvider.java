package com.recoversense.diagnosis;

import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for the real Claude-backed provider. Always
 * registered (unlike Claude, which only wires up when claude.api-key is
 * configured - see com.recoversense.claude.ClaudeAutoConfiguration) so
 * DiagnosisService always has a working DiagnosisProvider bean: this is what
 * every existing test runs against, and what local/offline development uses.
 * <p>
 * Wraps the existing keyword-based {@link DiagnosisEngine} unchanged, and
 * tags every result DiagnosisSource.SIMULATED - the M1.15 rule that a
 * deterministic fallback must never be presented as Claude output.
 */
@Component
public class SimulatedDiagnosisProvider implements DiagnosisProvider {

    private final DiagnosisEngine diagnosisEngine = new DiagnosisEngine();

    @Override
    public RecoveryDiagnosis diagnose(DiagnosisContext context) {
        DiagnosisResult result = diagnosisEngine.diagnose(context);
        return new RecoveryDiagnosis(result.diagnosisCategory(), result.confidence(), result.reasoning(),
                DiagnosisSource.SIMULATED);
    }
}
