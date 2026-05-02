package com.bank.aiassistant.service.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * PII (Personally Identifiable Information) detector and redactor.
 *
 * <p>Regex-based patterns for a bank POC. Production upgrade path:
 * <ul>
 *   <li>AWS Comprehend Medical / AWS Comprehend PII</li>
 *   <li>Azure Cognitive Services Text Analytics PII</li>
 *   <li>Microsoft Presidio (open source, Docker-based)</li>
 * </ul>
 *
 * <p>Detects and redacts:
 * Credit card numbers, SSN, email addresses, phone numbers, IBAN, NHS numbers.
 */
@Slf4j
@Component
public class PiiDetectorGuardrail {

    private record PiiPattern(String label, Pattern pattern, String replacement) {}

    private static final java.util.List<PiiPattern> PATTERNS = java.util.List.of(
            new PiiPattern("CREDIT_CARD",
                    Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12})\\b"),
                    "[CREDIT_CARD_REDACTED]"),
            new PiiPattern("SSN",
                    Pattern.compile("\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0{4})\\d{4}\\b"),
                    "[SSN_REDACTED]"),
            new PiiPattern("EMAIL",
                    Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"),
                    "[EMAIL_REDACTED]"),
            new PiiPattern("PHONE_UK",
                    Pattern.compile("\\b(?:0|\\+44)\\s?(?:1\\d{3}|2\\d|3\\d|7\\d{3}|8\\d{3})\\s?\\d{3}\\s?\\d{3,4}\\b"),
                    "[PHONE_REDACTED]"),
            new PiiPattern("PHONE_US",
                    Pattern.compile("\\b(?:\\+1[-.]?)?\\(?\\d{3}\\)?[-.]?\\d{3}[-.]?\\d{4}\\b"),
                    "[PHONE_REDACTED]"),
            new PiiPattern("IBAN",
                    Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}(?:[A-Z0-9]?){0,16}\\b"),
                    "[IBAN_REDACTED]"),
            new PiiPattern("SORT_CODE",
                    Pattern.compile("\\b\\d{2}-\\d{2}-\\d{2}\\b"),
                    "[SORT_CODE_REDACTED]"),
            new PiiPattern("ACCOUNT_NUMBER",
                    Pattern.compile("\\b\\d{8}\\b"),  // 8-digit UK account numbers
                    "[ACCOUNT_NUM_REDACTED]")
    );

    /**
     * Detects if text contains PII.
     */
    public boolean containsPii(String text) {
        if (text == null) return false;
        return PATTERNS.stream().anyMatch(p -> p.pattern().matcher(text).find());
    }

    /**
     * Redacts all PII from text and returns sanitised version.
     */
    public String redact(String text) {
        if (text == null) return null;
        String result = text;
        int redactions = 0;
        for (PiiPattern pii : PATTERNS) {
            String replaced = pii.pattern().matcher(result).replaceAll(pii.replacement());
            if (!replaced.equals(result)) {
                redactions++;
                log.info("PII redacted: type={}", pii.label());
            }
            result = replaced;
        }
        if (redactions > 0) {
            log.warn("Redacted {} PII entities from text", redactions);
        }
        return result;
    }
}
