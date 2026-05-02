package com.bank.aiassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Assistant POC — Enterprise Banking AI Platform
 *
 * <p>Architecture overview:
 * <ul>
 *   <li>Spring AI 1.0 + GPT-4o for LLM orchestration</li>
 *   <li>Pinecone for vector retrieval and MongoDB for application persistence</li>
 *   <li>MCP-based secure connector framework for Jira, Confluence, GitHub, SharePoint, Email</li>
 *   <li>ReAct agentic loop with tool execution (PDF, Excel, Email, Image, JSON)</li>
 *   <li>Multi-layer guardrails (PII, toxicity, prompt-injection, data-leakage)</li>
 *   <li>JWT-secured REST APIs with SSE streaming for chat</li>
 *   <li>Embedded React UI with voice support</li>
 * </ul>
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class AiAssistantPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantPocApplication.class, args);
    }
}
