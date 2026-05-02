package com.bank.aiassistant.service.guardrails;

import com.bank.aiassistant.exception.GuardrailException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt injection and jailbreak detection guardrail.
 *
 * <p>Detects:
 * <ul>
 *   <li>Classic "ignore previous instructions" patterns</li>
 *   <li>Role-play jailbreaks ("act as DAN", "pretend you are...")</li>
 *   <li>System prompt extraction attempts</li>
 *   <li>Delimiter injection</li>
 * </ul>
 *
 * <p>Production upgrade: Use a dedicated classifier (e.g. Rebuff, LakeraGuard, or
 * a fine-tuned BERT model) for higher detection rates.
 */
@Slf4j
@Component
public class PromptInjectionGuardrail {

    private record InjectionPattern(String name, Pattern pattern) {}

    private static final List<InjectionPattern> INJECTION_PATTERNS = List.of(
            new InjectionPattern("IGNORE_INSTRUCTIONS",
                    Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above|your)\\s+instructions?", Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("NEW_INSTRUCTIONS",
                    Pattern.compile("(new|updated|revised)\\s+instructions?\\s*:", Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("DAN_JAILBREAK",
                    Pattern.compile("\\b(DAN|do\\s+anything\\s+now|jailbreak|jail\\s+break)\\b", Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("ACT_AS",
                    Pattern.compile("(act|pretend|behave|respond)\\s+(as|like)\\s+(if\\s+you\\s+(are|were)|a\\s+)?\\b(different|unrestricted|uncensored|evil|malicious)\\b", Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("SYSTEM_PROMPT_LEAK",
                    Pattern.compile("(print|show|reveal|display|output|repeat)\\s+(your\\s+)?(system\\s+prompt|instructions|training|rules|guidelines)", Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("DELIMITER_INJECTION",
                    Pattern.compile("(</?(system|assistant|human|user|instruction)>|\\[/?INST\\]|<<SYS>>)", Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("OVERRIDE_SAFETY",
                    Pattern.compile("(disable|bypass|override|remove|ignore)\\s+(safety|guardrails?|filters?|restrictions?|limitations?)", Pattern.CASE_INSENSITIVE))
    );

    /**
     * Checks for prompt injection. Returns sanitised input.
     * Throws {@link GuardrailException} for clear injection attempts.
     */
    public String process(String input, String userId) {
        for (InjectionPattern pattern : INJECTION_PATTERNS) {
            if (pattern.pattern().matcher(input).find()) {
                log.warn("SECURITY: Prompt injection detected [{}}] for user={}: {}",
                        pattern.name(), userId, input.substring(0, Math.min(100, input.length())));
                throw new GuardrailException(
                        "Your message contains content that cannot be processed. " +
                        "Please rephrase your question. [Code: PI-" + pattern.name() + "]");
            }
        }
        return input;
    }
}
