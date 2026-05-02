package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Agent tool: sends an email via the configured SMTP server.
 *
 * <p>Input params: {@code to}, {@code subject}, {@code body}, {@code cc} (optional)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSendTool implements AgentTool {

    private final JavaMailSender mailSender;

    @Override public String getName() { return "send_email"; }

    @Override
    public String getDescription() {
        return "Sends an email to specified recipients. Use when the user explicitly asks to send an email. " +
               "Always confirm the recipient and content with the user before sending.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "to":      { "type": "string",  "description": "Recipient email address(es), comma-separated" },
                    "subject": { "type": "string",  "description": "Email subject line" },
                    "body":    { "type": "string",  "description": "Email body (plain text)" },
                    "cc":      { "type": "string",  "description": "CC address(es), optional" }
                  },
                  "required": ["to", "subject", "body"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String to      = params.path("to").asText();
        String subject = params.path("subject").asText();
        String body    = params.path("body").asText();
        String cc      = params.path("cc").asText(null);

        if (to.isBlank() || subject.isBlank()) return ToolResult.error("to and subject are required");

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to.split(","));
            message.setSubject(subject);
            message.setText(body);
            if (cc != null && !cc.isBlank()) message.setCc(cc.split(","));
            mailSender.send(message);
            log.info("Email sent to: {} subject: {}", to, subject);
            return ToolResult.ok("Email sent successfully to: " + to);
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", to, ex.getMessage());
            return ToolResult.error("Failed to send email: " + ex.getMessage());
        }
    }
}
