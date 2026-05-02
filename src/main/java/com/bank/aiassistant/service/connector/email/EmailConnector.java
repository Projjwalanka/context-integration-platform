package com.bank.aiassistant.service.connector.email;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.service.connector.ConnectorCredentialService;
import com.bank.aiassistant.service.connector.spi.ConnectorHealth;
import com.bank.aiassistant.service.connector.spi.ConnectorType;
import com.bank.aiassistant.service.connector.spi.DataSourceConnector;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.SubjectTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * IMAP email connector for searching and reading emails.
 *
 * <p>Required credentials: {@code imapHost}, {@code imapPort}, {@code username}, {@code password}
 * <p>Config: {@code folder} (default: INBOX), {@code maxMessages}
 *
 * <p>For sending emails, see {@link com.bank.aiassistant.service.agent.tools.EmailSendTool}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailConnector implements DataSourceConnector {

    private final ConnectorCredentialService credentialService;

    @Override
    public ConnectorType getType() { return ConnectorType.EMAIL; }

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) {
        long start = System.currentTimeMillis();
        Store store = null;
        try {
            store = openStore(config);
            return ConnectorHealth.ok(System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return ConnectorHealth.error(ex.getMessage());
        } finally {
            closeStore(store);
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                               String query, int maxResults) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            store = openStore(config);
            String folderName = getConfigValue(config, "folder", "INBOX");
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            // Search by subject keyword
            Message[] messages = folder.search(new SubjectTerm(query));
            int count = Math.min(messages.length, maxResults);
            for (int i = Math.max(0, messages.length - count); i < messages.length; i++) {
                Message msg = messages[i];
                try {
                    String subject = msg.getSubject() != null ? msg.getSubject() : "(No Subject)";
                    String from = msg.getFrom() != null ? msg.getFrom()[0].toString() : "Unknown";
                    String body = extractBody(msg);
                    String content = String.format("[Email] %s\nFrom: %s\n%s",
                            subject, from,
                            body.length() > 2000 ? body.substring(0, 2000) + "…" : body);
                    results.add(Map.entry(content, Map.of(
                            "source_type", "EMAIL",
                            "subject", subject,
                            "from", from
                    )));
                } catch (Exception msgEx) {
                    log.warn("Failed to process email: {}", msgEx.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Email query failed: {}", ex.getMessage());
        } finally {
            closeFolder(folder);
            closeStore(store);
        }
        return results;
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) {
        return query(config, "", 50);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Store openStore(ConnectorConfig config) throws Exception {
        Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
        Properties props = new Properties();
        props.put("mail.imap.host", creds.get("imapHost"));
        props.put("mail.imap.port", creds.getOrDefault("imapPort", "993"));
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.auth", "true");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect(creds.get("imapHost"),
                Integer.parseInt(creds.getOrDefault("imapPort", "993")),
                creds.get("username"),
                creds.get("password"));
        return store;
    }

    private String extractBody(Message message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String s) return s;
        if (content instanceof MimeMultipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                if (part.isMimeType("text/plain")) sb.append(part.getContent());
            }
            return sb.toString();
        }
        return "";
    }

    private String getConfigValue(ConnectorConfig config, String key, String defaultVal) {
        try {
            if (config.getConfig() != null) {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                String val = om.readTree(config.getConfig()).path(key).asText(null);
                return val != null ? val : defaultVal;
            }
        } catch (Exception ignored) {}
        return defaultVal;
    }

    private void closeFolder(Folder f) {
        try { if (f != null && f.isOpen()) f.close(false); } catch (Exception ignored) {}
    }
    private void closeStore(Store s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
