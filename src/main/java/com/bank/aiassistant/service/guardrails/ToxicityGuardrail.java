package com.bank.aiassistant.service.guardrails;

import com.bank.aiassistant.exception.GuardrailException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Toxicity and harmful content filter for both input and output.
 *
 * <p>Lightweight keyword/pattern approach for POC.
 * Production: integrate OpenAI Moderation API or Azure Content Safety.
 */
@Slf4j
@Component
public class ToxicityGuardrail {

    private record ToxicPattern(String category, Pattern pattern) {}

    private static final List<ToxicPattern> TOXIC_PATTERNS = List.of(
            new ToxicPattern("SELF_HARM",
                    Pattern.compile("\\b(how\\s+to\\s+(commit|do)\\s+suicide|self.harm|kill\\s+myself)\\b", Pattern.CASE_INSENSITIVE)),
            new ToxicPattern("ILLEGAL_ACTIVITY",
                    Pattern.compile("\\b(how\\s+to\\s+(hack|make\\s+(a\\s+)?bomb|launder\\s+money|synthesize\\s+(drugs|cocaine|meth)))\\b", Pattern.CASE_INSENSITIVE)),
            new ToxicPattern("FRAUD",
                    Pattern.compile("\\b(phishing|identity\\s+theft|credit\\s+card\\s+fraud|money\\s+mule)\\b", Pattern.CASE_INSENSITIVE))
    );

    public String processInput(String input, String userId) {
        for (ToxicPattern tp : TOXIC_PATTERNS) {
            if (tp.pattern().matcher(input).find()) {
                log.warn("SECURITY: Toxicity detected [{}] for user={}", tp.category(), userId);
                throw new GuardrailException(
                        "I'm not able to assist with that request. [Code: TX-" + tp.category() + "]");
            }
        }
        return input;
    }

    public String processOutput(String output, String userId) {
        for (ToxicPattern tp : TOXIC_PATTERNS) {
            if (tp.pattern().matcher(output).find()) {
                log.error("SECURITY: Toxic output blocked [{}] for user={}", tp.category(), userId);
                return "I'm unable to provide that information. Please contact your system administrator.";
            }
        }
        return output;
    }
}
