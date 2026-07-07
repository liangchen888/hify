-- Initialize pgvector extension for PostgreSQL
-- This script sets up vector support in PostgreSQL for RAG embeddings
-- Run this ONCE on the PostgreSQL database as a superuser or with appropriate privileges

-- Enable pgvector extension (required for vector data type)
CREATE EXTENSION IF NOT EXISTS vector;

-- Create the chunk table with pgvector support for BGE embeddings
-- BGE-large-zh-v1.5 produces 768-dimensional vectors
CREATE TABLE IF NOT EXISTS chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER,
    embedding vector(768) NOT NULL,              -- BGE 768-dimensional vector
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,                  -- Soft delete flag (0=active, 1=deleted)
    
    -- Constraints
    CONSTRAINT chunk_document_index_unique UNIQUE(document_id, chunk_index)
);

-- Create indexes for query optimization

-- 1. Index on document_id for filtering by document
CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON chunk(document_id) WHERE deleted = 0;

-- 2. IVFFLAT index on embedding for vector similarity search (fast approximate search)
--    - operator_class=vector_cosine_ops for cosine distance
--    - lists parameter: recommended is sqrt(rows), adjusted based on dataset size
--    - probe parameter: number of lists to search (lower=faster, higher=more accurate)
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_ivf ON chunk 
    USING IVFFLAT (embedding vector_cosine_ops) 
    WITH (lists = 100);

-- Alternative: HNSW index for better quality (but slower build time)
-- CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw ON chunk 
--     USING HNSW (embedding vector_cosine_ops) 
--     WITH (m = 16, ef_construction = 64);

-- 3. Composite index for filtering by document + deleted status before vector search
CREATE INDEX IF NOT EXISTS idx_chunk_document_deleted ON chunk(document_id, deleted) WHERE deleted = 0;

-- Table comments for documentation
COMMENT ON TABLE chunk IS 'Vector-stored text chunks from documents, used for RAG retrieval with BGE embeddings';
COMMENT ON COLUMN chunk.id IS 'Primary key identifier';
COMMENT ON COLUMN chunk.document_id IS 'Foreign key reference to parent document';
COMMENT ON COLUMN chunk.chunk_index IS 'Sequential index of chunk within document (0-based)';
COMMENT ON COLUMN chunk.content IS 'Original text content of the chunk';
COMMENT ON COLUMN chunk.token_count IS 'Token count for the chunk content (used for billing/limits)';
COMMENT ON COLUMN chunk.embedding IS '768-dimensional vector from BGE-large-zh-v1.5 model';
COMMENT ON COLUMN chunk.deleted IS 'Soft delete flag (0=active, 1=deleted)';
