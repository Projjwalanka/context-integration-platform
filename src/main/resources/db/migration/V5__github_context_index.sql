-- GitHub context index optimized for lexical + recency retrieval.
-- Uses PostgreSQL full text search and trigram similarity (no vector embedding required).

CREATE TABLE IF NOT EXISTS github_content_index (
    id                VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::text,
    connector_id      VARCHAR(36) NOT NULL REFERENCES connector_configs(id) ON DELETE CASCADE,
    user_id           VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type       VARCHAR(40) NOT NULL,
    repo              VARCHAR(255),
    url               TEXT NOT NULL,
    title             TEXT,
    body              TEXT NOT NULL,
    metadata          JSONB,
    source_updated_at TIMESTAMPTZ,
    ingested_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    search_vector     tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(body, '')), 'B')
    ) STORED,
    UNIQUE (connector_id, url)
);

CREATE INDEX IF NOT EXISTS idx_github_content_index_connector
    ON github_content_index (connector_id);

CREATE INDEX IF NOT EXISTS idx_github_content_index_user
    ON github_content_index (user_id);

CREATE INDEX IF NOT EXISTS idx_github_content_index_repo
    ON github_content_index (repo);

CREATE INDEX IF NOT EXISTS idx_github_content_index_updated
    ON github_content_index (source_updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_github_content_index_tsv
    ON github_content_index USING GIN (search_vector);

CREATE INDEX IF NOT EXISTS idx_github_content_index_body_trgm
    ON github_content_index USING GIN (body gin_trgm_ops);
