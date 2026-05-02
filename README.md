# AI Assistant POC — Enterprise Banking Intelligence Platform

> **Status:** Proof of Concept · Java 21 · Spring Boot 3.3 · Spring AI 1.0 · GPT-4o · pgvector · React 18

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Quick Start — Local Development](#quick-start--local-development)
4. [Quick Start — Docker Demo](#quick-start--docker-demo)
5. [Configuration Reference](#configuration-reference)
6. [Module Guide](#module-guide)
7. [API Reference](#api-reference)
8. [Default Credentials](#default-credentials)
9. [Extending the System](#extending-the-system)
10. [Production Considerations](#production-considerations)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        React UI (embedded SPA)                      │
│  Chat (SSE streaming) · Voice (Web Speech) · Connector Panel        │
│  Feedback · File Upload · Artifact Download                         │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ REST / SSE
┌───────────────────────────▼─────────────────────────────────────────┐
│                  Spring Boot 3.3 Application                        │
│                                                                     │
│  ┌─────────────┐   ┌──────────────┐   ┌────────────────────────┐   │
│  │  JWT Auth   │   │  GuardrailChain│  │   ChatService (SSE)   │   │
│  │  (HS512)    │   │  PII · Inject. │  │   + ConversationMem.  │   │
│  └─────────────┘   │  Toxicity · RL │  └──────────┬────────────┘   │
│                    └──────────────┘               │               │
│  ┌────────────────────────────────────────────────▼────────────┐  │
│  │                   AgentOrchestrator (ReAct)                  │  │
│  │  generate_pdf · generate_excel · generate_image             │  │
│  │  send_email · export_json · search_knowledge_base           │  │
│  └────────────────────────────────────────────────┬────────────┘  │
│                                                    │               │
│  ┌─────────────────────────────────────────────────▼────────────┐  │
│  │  OpenAI GPT-4o (Spring AI)  ←→  VectorStoreService          │  │
│  │  Embeddings: text-embedding-3-small                          │  │
│  │  Hybrid RAG: Dense (cosine) + Sparse (trigram) + RRF         │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ConnectorRegistry (SPI — auto-discovery)                          │
│  ┌──────────┐ ┌───────────┐ ┌────────┐ ┌───────────┐ ┌───────┐   │
│  │  Jira    │ │Confluence │ │GitHub  │ │SharePoint │ │ Email │   │
│  │ REST v3  │ │ CQL/v2    │ │Search  │ │ Graph API │ │  IMAP │   │
│  └──────────┘ └───────────┘ └────────┘ └───────────┘ └───────┘   │
│  Credentials: AES-256-GCM encrypted at rest                        │
└─────────────────────────────────────────────────────────────────────┘
                            │
              ┌─────────────▼──────────────┐
              │  PostgreSQL 16 + pgvector  │
              │  Flyway migrations         │
              │  IVFFlat ANN index         │
              └────────────────────────────┘
```

### Key Design Decisions

| Concern | Choice | Rationale |
|---|---|---|
| LLM | OpenAI GPT-4o via Spring AI | Proven, multimodal, tool-use support |
| Embeddings | text-embedding-3-small (1536d) | Cost-effective, strong performance |
| Vector DB | pgvector on PostgreSQL | No extra infra; ACID transactions |
| Retrieval | Hybrid RAG (dense + sparse + RRF) | Higher recall than pure vector search |
| Agentic pattern | ReAct (Reason + Act) | Transparent chain-of-thought |
| Connector extension | SPI / Spring auto-discovery | Zero-change to registry when adding connectors |
| Credentials at rest | AES-256-GCM | Upgrade path to KMS/Vault is one class swap |
| Security | Stateless JWT (HS512) | Scales horizontally without session store |
| Guardrails | Regex + pattern chain | Baseline; upgrade to ML classifier in prod |
| Streaming | Spring WebFlux SSE | Low-latency real-time token streaming |

---

## Prerequisites

### For Local Development

| Tool | Version | Notes |
|---|---|---|
| Java JDK | 21 | Eclipse Temurin recommended |
| Maven | 3.9+ | Bundled `mvnw` wrapper included |
| Node.js | 20 LTS | For `npm run dev` frontend dev server |
| PostgreSQL | 15+ | Must have **pgvector** extension |
| OpenAI API key | Any tier | GPT-4o access required |

#### Install pgvector locally (macOS / Linux)

```bash
# macOS (Homebrew)
brew install pgvector

# Ubuntu/Debian
sudo apt install postgresql-16-pgvector

# Verify
psql -U postgres -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### For Docker Demo

| Tool | Version |
|---|---|
| Docker | 24+ |
| Docker Compose | 2.20+ |

---

## Quick Start — Local Development

### 1. Clone and configure

```bash
git clone <your-repo-url> ai-assistant-poc
cd ai-assistant-poc

# Create your environment file
cp .env.example .env
```

Edit `.env`:

```env
# REQUIRED — your OpenAI API key
OPENAI_API_KEY=sk-...

# JWT secret — generate a random 32-byte base64 string:
# openssl rand -base64 32
JWT_SECRET=your-base64-secret-here

# AES-256 encryption key for connector credentials:
# python3 -c "import os,base64; print(base64.b64encode(os.urandom(32)).decode())"
ENCRYPTION_KEY=your-base64-aes-key-here
```

### 2. Set up PostgreSQL

```bash
# Create database
psql -U postgres -c "CREATE DATABASE ai_assistant;"

# Enable extensions
psql -U postgres -d ai_assistant -c "
  CREATE EXTENSION IF NOT EXISTS vector;
  CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
"
```

### 3. Build and run the backend

```bash
# Using Maven wrapper (builds React + Spring Boot)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-DOPENAI_API_KEY=$OPENAI_API_KEY -DJWT_SECRET=$JWT_SECRET -DENCRYPTION_KEY=$ENCRYPTION_KEY"
```

The application starts on **http://localhost:8080**.
Flyway runs migrations automatically on startup.

### 4. (Optional) Run the React dev server with hot reload

```bash
cd frontend
npm install
npm run dev
# Opens http://localhost:3000 — proxies /api to http://localhost:8080
```

### 5. Run the vector store indexes migration

After the first application start (Spring AI creates the `vector_store` table), run:

```bash
psql -U postgres -d ai_assistant -f src/main/resources/db/migration/V2__vector_store_indexes.sql
```

---

## Quick Start — Docker Demo

### 1. Configure

```bash
cp .env.example .env
# Edit .env: set OPENAI_API_KEY, JWT_SECRET, ENCRYPTION_KEY
```

### 2. Build and start all services

```bash
docker-compose up --build
```

This starts:
- `postgres` — PostgreSQL 16 + pgvector on port **5432**
- `app`      — AI Assistant on port **8080**

### 3. Open the UI

Navigate to **http://localhost:8080**

Login with: `admin@bank.com` / `Admin@123`

### 4. Stop

```bash
docker-compose down          # stop (keep data)
docker-compose down -v       # stop + delete volumes
```

---

## Configuration Reference

All settings live in `src/main/resources/application.yml` and are overridable via environment variables.

| Environment Variable | Default | Description |
|---|---|---|
| `OPENAI_API_KEY` | _(required)_ | OpenAI API key |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `ai_assistant` | Database name |
| `DB_USER` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | _(required)_ | Base64-encoded 256-bit secret |
| `ENCRYPTION_KEY` | _(required)_ | Base64-encoded 32-byte AES key |
| `SMTP_HOST` | `smtp.gmail.com` | SMTP server for email sending |
| `SMTP_USER` | — | SMTP username |
| `SMTP_PASSWORD` | — | SMTP password / app password |
| `ARTIFACTS_DIR` | `/tmp/ai-artifacts` | Generated files directory |
| `PORT` | `8080` | HTTP server port |

---

## Module Guide

### Package Structure

```
com.bank.aiassistant/
├── config/                 # Spring configuration beans
├── security/jwt/           # JWT token lifecycle, filter
├── model/entity/           # JPA entities
├── model/dto/              # Request/Response DTOs (Java Records)
├── repository/             # Spring Data JPA repositories
├── service/
│   ├── llm/                # LlmService interface + OpenAI impl
│   ├── chat/               # ChatService (orchestrates full turn)
│   ├── vector/             # VectorStoreService (hybrid RAG)
│   ├── ingestion/          # IngestionPipeline, DocumentChunker
│   ├── connector/          # SPI + 6 connector implementations
│   │   ├── spi/            # DataSourceConnector interface
│   │   ├── jira/           # Jira REST API v3
│   │   ├── confluence/     # Confluence CQL API
│   │   ├── github/         # GitHub Search API
│   │   ├── sharepoint/     # Microsoft Graph API
│   │   ├── email/          # IMAP connector
│   │   └── documents/      # pgvector passthrough
│   ├── agent/              # AgentOrchestrator (ReAct)
│   │   └── tools/          # 7 agent tools
│   └── guardrails/         # 4-layer guardrail chain
├── controller/             # REST controllers
└── exception/              # Global exception handler
```

### Ingestion Pipeline

**Static documents** (PDF, DOCX, XLSX, TXT, HTML):
1. Upload via `POST /api/ingestion/upload`
2. `IngestionPipeline` reads with PDFBox / Apache Tika
3. `DocumentChunker` splits: 512-token window, 64-token overlap
4. Spring AI embeds with `text-embedding-3-small`
5. Stored in pgvector with metadata (`user_id`, `source_type`, `ingested_at`)

**Live data** (Jira, Confluence, GitHub, SharePoint, Email):
- Fetched in real-time during each chat turn
- Query is passed to each active connector
- Results appended to the LLM context window
- No embedding overhead — always fresh

### Guardrail Chain

```
Input  → RateLimiter (Bucket4j) → PromptInjectionGuardrail → ToxicityGuardrail → PiiDetector → LLM
Output → PiiDetector → DataLeakageGuardrail → ToxicityGuardrail → User
```

---

## API Reference

### Authentication

```
POST /api/auth/login      { email, password } → { accessToken, refreshToken, ... }
POST /api/auth/register   { email, password, firstName, lastName }
POST /api/auth/logout     (Bearer token)
POST /api/auth/refresh    ?refreshToken=...
```

### Chat

```
POST /api/chat                      Blocking chat
POST /api/chat/stream               SSE streaming chat (text/event-stream)
GET  /api/chat/conversations        List conversations (paginated)
GET  /api/chat/conversations/{id}   Get conversation + messages
DELETE /api/chat/conversations/{id} Archive conversation
```

**Chat request body:**
```json
{
  "message": "Summarise open Jira tickets for sprint 12",
  "conversationId": "optional-existing-id",
  "connectorIds": ["connector-uuid-1", "connector-uuid-2"],
  "stream": true
}
```

### Connectors

```
GET    /api/connectors         List connectors
POST   /api/connectors         Create connector
PUT    /api/connectors/{id}    Update connector
DELETE /api/connectors/{id}    Delete connector
POST   /api/connectors/{id}/health  Test connectivity
```

### Ingestion

```
POST /api/ingestion/upload     Upload file (multipart/form-data, field: file)
GET  /api/ingestion/jobs       List recent ingestion jobs
GET  /api/ingestion/jobs/{id}  Get job status
```

### Feedback

```
POST /api/feedback        Submit feedback (THUMBS_UP / THUMBS_DOWN / RATING)
GET  /api/feedback/stats  Aggregated stats (average rating, total)
```

### Artifacts (generated files)

```
GET /api/artifacts/{filename}   Download a generated PDF/Excel/JSON
```

---

## Default Credentials

| Field | Value |
|---|---|
| Email | `admin@bank.com` |
| Password | `Admin@123` |
| Roles | `ADMIN`, `USER` |

> Change this immediately in production by updating the seed in `V1__initial_schema.sql`
> or via the `/api/auth/register` endpoint.

---

## Extending the System

### Add a new data source connector

1. Create a new class in `service/connector/<your-source>/`:

```java
@Service
public class MySalesforceConnector implements DataSourceConnector {

    @Override
    public ConnectorType getType() { return ConnectorType.SALESFORCE; } // Add to enum

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) { ... }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(
            ConnectorConfig config, String query, int maxResults) { ... }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) { ... }
}
```

2. Add `SALESFORCE` to `ConnectorType` enum.
3. Add field definitions to the React `CONNECTOR_FIELDS` map in `ConnectorConfigModal.jsx`.
4. Done — `ConnectorRegistry` auto-discovers it via Spring's `@Service` scanning.

### Add a new agent tool

1. Implement `AgentTool` in `service/agent/tools/`:

```java
@Component
public class CalendarTool implements AgentTool {
    @Override public String getName() { return "create_calendar_event"; }
    @Override public String getDescription() { return "Creates a calendar event..."; }
    @Override public String getParameterSchema() { return "{...}"; }
    @Override public ToolResult execute(JsonNode params) { ... }
}
```

2. Done — `AgentOrchestrator` auto-injects all `AgentTool` beans.

### Swap LLM provider

Replace `OpenAiLlmService` with any implementation of `LlmService`:

```java
@Service @Primary
public class AzureOpenAiLlmService implements LlmService { ... }
```

---

## Production Considerations

| Area | POC (current) | Production upgrade |
|---|---|---|
| Credential storage | AES-256-GCM in DB | AWS KMS / HashiCorp Vault |
| Token blacklist | In-memory ConcurrentHashSet | Redis SET with TTL |
| Rate limiting | Bucket4j in-memory | Bucket4j + Redis (distributed) |
| Guardrails | Regex patterns | AWS Comprehend / Azure Content Safety / Presidio |
| LLM provider | OpenAI GPT-4o | AWS Bedrock / Azure OpenAI (failover) |
| Vector index | IVFFlat (100 lists) | HNSW (higher recall, slower build) |
| Observability | Actuator + Micrometer | Prometheus + Grafana + Zipkin |
| Authentication | JWT in-memory blacklist | Keycloak + OAuth2 / OIDC |
| File storage | Local filesystem | S3 / Azure Blob |
| Async processing | Spring `@Async` | Kafka or RabbitMQ |

---

## Troubleshooting

**"vector extension not found"**
```sql
-- Run as superuser in your database:
CREATE EXTENSION IF NOT EXISTS vector;
```

**"OPENAI_API_KEY not set"**
```bash
export OPENAI_API_KEY=sk-...
# Or add to .env file and run: source .env
```

**Build fails on frontend**
```bash
cd frontend && npm install && npm run build
# Then re-run: ./mvnw package -DskipTests
```

**Port 5432 already in use**
```bash
# Stop existing PostgreSQL
sudo systemctl stop postgresql
# Or change docker-compose.yml to use a different host port (e.g. 5433:5432)
```

---

*Built as an enterprise-grade POC. All architectural decisions are designed for straightforward extension into a production system.*
