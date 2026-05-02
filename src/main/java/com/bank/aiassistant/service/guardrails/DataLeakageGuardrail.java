package com.bank.aiassistant.service.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Data leakage prevention — scans LLM output for secrets, credentials, and
 * internal system information that should never be exposed to users.
 */
@Slf4j
@Component
public class DataLeakageGuardrail {

    private record SecretPattern(String name, Pattern pattern, String replacement) {}

    private static final List<SecretPattern> SECRET_PATTERNS = List.of(
            new SecretPattern("AWS_KEY",
                    Pattern.compile("AKIA[0-9A-Z]{16}", Pattern.CASE_INSENSITIVE),
                    "[AWS_KEY_REDACTED]"),
            new SecretPattern("PRIVATE_KEY",
                    Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
                    "[PRIVATE_KEY_REDACTED]"),
            new SecretPattern("JWT_TOKEN",
                    Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
                    "[JWT_REDACTED]"),
            new SecretPattern("GENERIC_SECRET",
                    Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|token)\\s*[=:\"']\\s*[^\\s\"']{8,}"),
                    "[CREDENTIAL_REDACTED]"),
            new SecretPattern("CONNECTION_STRING",
                    Pattern.compile("(?i)(jdbc|mongodb|postgresql|mysql|redis)://[^\\s\"']+"),
                    "[CONNECTION_STRING_REDACTED]")
    );

    public String process(String output, String userId) {
        if (output == null) return null;
        String result = output;
        for (SecretPattern sp : SECRET_PATTERNS) {
            String replaced = sp.pattern().matcher(result).replaceAll(sp.replacement());
            if (!replaced.equals(result)) {
                log.warn("DATA_LEAKAGE: Potential secret [{}] redacted in output for user={}", sp.name(), userId);
                result = replaced;
            }
        }
        return result;
    }
}
