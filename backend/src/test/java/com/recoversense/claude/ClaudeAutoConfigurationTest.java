package com.recoversense.claude;

import com.recoversense.diagnosis.DiagnosisProvider;
import com.recoversense.diagnosis.SimulatedDiagnosisProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * M1.16: proves the discovered ClaudeApiKeyConfiguredCondition fix - a blank
 * (present-but-empty) claude.api-key must behave exactly like an absent one
 * (SimulatedDiagnosisProvider only), not silently activate a real Claude
 * client that will fail with a genuine 401 against an empty key. See
 * ClaudeAutoConfiguration/ClaudeApiKeyConfiguredCondition javadoc for how
 * this was found: sourcing a .env with CLAUDE_API_KEY= (declared but empty)
 * into the environment used to wire the real, broken provider instead of the
 * mock.
 */
class ClaudeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClaudeAutoConfiguration.class))
            .withBean(SimulatedDiagnosisProvider.class);

    @Test
    void blankApiKey_doesNotActivateClaude_simulatedProviderRemainsTheOnlyOne() {
        contextRunner.withPropertyValues("claude.api-key=").run(context -> {
            DiagnosisProvider provider = context.getBean(DiagnosisProvider.class);
            assertInstanceOf(SimulatedDiagnosisProvider.class, provider);
            assertFalse(context.containsBean("claudeDiagnosisProvider"));
        });
    }

    @Test
    void missingApiKey_doesNotActivateClaude() {
        contextRunner.run(context -> {
            DiagnosisProvider provider = context.getBean(DiagnosisProvider.class);
            assertInstanceOf(SimulatedDiagnosisProvider.class, provider);
        });
    }

    @Test
    void nonBlankApiKey_activatesClaudeAsThePrimaryProvider() {
        contextRunner.withPropertyValues("claude.api-key=sk-ant-test-key").run(context -> {
            DiagnosisProvider provider = context.getBean(DiagnosisProvider.class);
            assertInstanceOf(ClaudeDiagnosisProvider.class, provider);
        });
    }
}
