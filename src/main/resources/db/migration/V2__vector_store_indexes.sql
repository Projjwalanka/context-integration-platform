-- =============================================================================
-- V2: Create vector_store table and add performance indexes
-- NOTE: spring.ai.vectorstore.pgvector.initialize-schema is set to FALSE so
--       we own the schema completely through Flyway.
-- =============================================================================

-- Ensure required extensions are present (idempotent)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Create the vector_store table exactly as Spring AI expects it
-- (Spring AI 1.1.0-M2 PgVectorStore schema: id uuid, content text,
--  metadata json, embedding vector(<dimensions>))
CREATE TABLE IF NOT EXISTS vector_store (
    id       UUID    DEFAULT uuid_generate_v4() PRIMARY KEY,
    content  TEXT,
    metadata JSON,
    embedding vector(1536)
);

-- IVFFlat index for ANN (approximate nearest neighbour) search
-- lists = sqrt(num_rows); 100 is a safe starting point for a POC
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_ivfflat
    ON vector_store USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- GIN index for metadata filtering — cast to jsonb because Spring AI stores metadata as json type
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_gin
    ON vector_store USING GIN ((metadata::jsonb) jsonb_path_ops);

-- Trigram index on content for sparse / BM25-style keyword fallback
-- Requires pg_trgm extension (enabled above)
CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm
    ON vector_store USING GIN (content gin_trgm_ops);
