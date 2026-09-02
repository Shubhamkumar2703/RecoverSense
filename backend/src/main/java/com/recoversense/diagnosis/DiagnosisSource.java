package com.recoversense.diagnosis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which kind of provider actually produced a {@link RecoveryDiagnosis}.
 * Required so a demo/dashboard can never present a deterministic fallback as
 * if it were real Claude output (see AI_DIAGNOSIS.md's fallback rule).
 */
public enum DiagnosisSource {
    CLAUDE,
    SIMULATED;

    private static final Pattern PREFIX = Pattern.compile("^\\[(CLAUDE|SIMULATED)]");

    /**
     * M1.27: recovers the source from {@code RecoveryDecision.diagnosisRaw}
     * (written by DiagnosisService as {@code "[" + source + "] " + reasoning}),
     * so callers (RecoveryResponse/DashboardMetricsService) can label a
     * diagnosis truthfully - "Provider: Deterministic Demo" vs "Provider:
     * Claude" - without RecoverSense ever having stored the source as its
     * own column. Returns null for anything that doesn't match (a
     * hand-built diagnosisRaw in a test fixture, or none at all) rather than
     * guessing.
     */
    public static DiagnosisSource parsePrefix(String diagnosisRaw) {
        if (diagnosisRaw == null) {
            return null;
        }
        Matcher matcher = PREFIX.matcher(diagnosisRaw);
        return matcher.find() ? DiagnosisSource.valueOf(matcher.group(1)) : null;
    }
}
