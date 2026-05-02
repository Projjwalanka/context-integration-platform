-- =============================================================================
-- V1: Initial Schema — AI Assistant POC
-- =============================================================================

-- Enable pgvector extension (must run as superuser)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- fuzzy text search

-- =============================================================================
-- USERS
-- =============================================================================
CREATE TABLE users (
    id          VARCHAR(36)  PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    locked      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

CREATE TABLE user_roles (
    user_id  VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- =============================================================================
-- CONVERSATIONS & MESSAGES
-- =============================================================================
CREATE TABLE conversations (
    id          VARCHAR(36)  PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    title       VARCHAR(255),
    user_id     VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    archived    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);
CREATE INDEX idx_conversations_user_id ON conversations(user_id);
CREATE INDEX idx_conversations_updated_at ON conversations(updated_at DESC);

CREATE TABLE conversation_connectors (
    conversation_id  VARCHAR(36) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    connector_id     VARCHAR(36) NOT NULL,
    PRIMARY KEY (conversation_id, connector_id)
);

CREATE TABLE messages (
    id               VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    conversation_id  VARCHAR(36) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role             VARCHAR(20) NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM','TOOL')),
    content          TEXT        NOT NULL,
    metadata         JSONB,
    token_count      INT,
    model            VARCHAR(100),
    latency_ms       BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX idx_messages_created_at ON messages(created_at);

-- =============================================================================
-- CONNECTOR CONFIGS
-- =============================================================================
CREATE TABLE connector_configs (
    id                    VARCHAR(36)  PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    connector_type        VARCHAR(50)  NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    encrypted_credentials TEXT,
    config                JSONB,
    enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    verified              BOOLEAN NOT NULL DEFAULT FALSE,
    user_id               VARCHAR(36) REFERENCES users(id) ON DELETE CASCADE,
    last_sync_at          TIMESTAMPTZ,
    last_error            VARCHAR(500),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ,
    UNIQUE (user_id, connector_type, name)
);
CREATE INDEX idx_connector_configs_user_id ON connector_configs(user_id);
CREATE INDEX idx_connector_configs_type ON connector_configs(connector_type);

-- =============================================================================
-- INGESTION JOBS
-- =============================================================================
CREATE TABLE ingestion_jobs (
    id                VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    connector_type    VARCHAR(50) NOT NULL,
    source_ref        VARCHAR(100),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')),
    chunks_processed  INT,
    chunks_total      INT,
    error_message     TEXT,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ
);
CREATE INDEX idx_ingestion_jobs_status ON ingestion_jobs(status);

-- =============================================================================
-- FEEDBACK
-- =============================================================================
CREATE TABLE feedback (
    id               VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    message_id       VARCHAR(36) NOT NULL,
    conversation_id  VARCHAR(36) NOT NULL,
    user_id          VARCHAR(36) NOT NULL,
    type             VARCHAR(10) NOT NULL CHECK (type IN ('THUMBS_UP','THUMBS_DOWN','RATING')),
    rating           INT CHECK (rating >= 1 AND rating <= 5),
    comment          TEXT,
    category         VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_feedback_conversation_id ON feedback(conversation_id);
CREATE INDEX idx_feedback_message_id ON feedback(message_id);

-- =============================================================================
-- VECTOR STORE (Spring AI auto-creates vector_store table, we add extra indexes)
-- Spring AI pgvector creates: vector_store(id UUID, content TEXT, metadata JSONB,
--   embedding vector(1536))
-- We add a composite GIN index for metadata filtering and a trigram index for
-- full-text fallback (sparse retrieval leg of the hybrid RAG pipeline).
-- =============================================================================
-- Note: Spring AI creates vector_store automatically on startup.
-- The indexes below are applied after the table exists via a separate migration
-- (V2) triggered after the app first run, OR you can comment these out and run
-- them manually once the vector_store table is created.

-- ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS source_type VARCHAR(50);
-- CREATE INDEX IF NOT EXISTS idx_vector_store_metadata ON vector_store USING GIN(metadata);
-- CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm ON vector_store USING GIN(content gin_trgm_ops);

-- =============================================================================
-- SEED: Default admin user
-- Password: Admin@123 (BCrypt hash — change in production!)
-- =============================================================================
INSERT INTO users (id, email, password, first_name, last_name, enabled)
VALUES (
    uuid_generate_v4()::text,
    'admin@bank.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCXzMXCxIiGxTivqhPEOXOm',
    'Admin',
    'User',
    TRUE
);
INSERT INTO user_roles (user_id, role)
SELECT id, 'ADMIN' FROM users WHERE email = 'admin@bank.com';
INSERT INTO user_roles (user_id, role)
SELECT id, 'USER' FROM users WHERE email = 'admin@bank.com';
