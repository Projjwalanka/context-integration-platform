-- PostgreSQL initialisation script — runs inside the Docker container
-- Enables the pgvector extension before the application starts.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;
