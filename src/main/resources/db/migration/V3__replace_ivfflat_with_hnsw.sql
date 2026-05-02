-- Replace IVFFlat index (requires ~300+ rows) with HNSW (works at any dataset size)
DROP INDEX IF EXISTS idx_vector_store_embedding_ivfflat;

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
