package com.bank.aiassistant.service.guardrails;

import com.bank.aiassistant.exception.GuardrailException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-layer guardrail pipeline — applied to both input and output.
 *
 * <p>Input chain: RateLimiter → PiiDetector → PromptInjectionDetector → ToxicityFilter
 * <p>Output chain: PiiDetector → DataLeakageGuardrail → ToxicityFilter
 *
 * <p>Design: Chain of Responsibility pattern — each guardrail can modify the content
 * (redact PII) or throw {@link GuardrailException} to reject it entirely.
 *
 * <p>Enterprise extensions:
 * <ul>
 *   <li>Connect to AWS Comprehend / Azure Content Safety for ML-based filtering</li>
 *   <li>Add audit logging to a SIEM (Splunk, ELK) for each guardrail decision</li>
 *   <li>Replace Bucket4j in-memory with Redis for distributed rate limiting</li>
 * </ul>
 */
@Slf4j
@Service
public class GuardrailChain {

    private final PiiDetectorGuardrail piiDetector;
    private final PromptInjectionGuardrail injectionDetector;
    private final ToxicityGuardrail toxicityGuardrail;
    private final DataLeakageGuardrail dataLeakageGuardrail;

    /** Per-user rate limiter buckets */
    private final Map<String, Bucket> rateLimiters = new ConcurrentHashMap<>();

    @Value("${app.guardrails.rate-limit.requests-per-minute:20}")
    private int requestsPerMinute;

    public GuardrailChain(PiiDetectorGuardrail piiDetector,
                          PromptInjectionGuardrail injectionDetector,
                          ToxicityGuardrail toxicityGuardrail,
                          DataLeakageGuardrail dataLeakageGuardrail) {
        this.piiDetector = piiDetector;
        this.injectionDetector = injectionDetector;
        this.toxicityGuardrail = toxicityGuardrail;
        this.dataLeakageGuardrail = dataLeakageGuardrail;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input guardrails
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies all input guardrails. Returns (possibly redacted) sanitized input.
     * Throws {@link GuardrailException} if input must be rejected.
     */
    public String checkInput(String input, String userId) {
        log.debug("Input guardrail check for user={}, len={}", userId, input.length());

        // 1. Rate limiting
        checkRateLimit(userId);

        // 2. Basic length and empty check
        if (input == null || input.isBlank()) throw new GuardrailException("Input cannot be empty");
        if (input.length() > 8000) throw new GuardrailException("Input exceeds maximum length of 8000 characters");

        // 3. Prompt injection detection
        String afterInjectionCheck = injectionDetector.process(input, userId);

        // 4. Toxicity filter
        String afterToxicity = toxicityGuardrail.processInput(afterInjectionCheck, userId);

        // 5. PII detection (redact, don't block)
        String sanitized = piiDetector.redact(afterToxicity);

        log.debug("Input guardrail PASSED for user={}", userId);
        return sanitized;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Output guardrails
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies all output guardrails. Returns (possibly redacted) sanitized output.
     */
    public String checkOutput(String output, String userId) {
        log.debug("Output guardrail check for user={}", userId);

        if (output == null || output.isBlank()) return output;

        // 1. PII redaction in output
        String afterPii = piiDetector.redact(output);

        // 2. Data leakage check (secrets, credentials, etc.)
        String afterLeakage = dataLeakageGuardrail.process(afterPii, userId);

        // 3. Toxicity check on output
        String safe = toxicityGuardrail.processOutput(afterLeakage, userId);

        log.debug("Output guardrail PASSED for user={}", userId);
        return safe;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rate limiting
    // ─────────────────────────────────────────────────────────────────────────

    private void checkRateLimit(String userId) {
        Bucket bucket = rateLimiters.computeIfAbsent(userId, k -> buildBucket());
        if (!bucket.tryConsume(1)) {
            log.warn("Rate limit exceeded for user={}", userId);
            throw new GuardrailException("Rate limit exceeded. Please wait before sending another message.");
        }
    }

    private Bucket buildBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillIntervally(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
