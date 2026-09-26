-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ── Chunks table ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chunks (
    chunk_id              TEXT PRIMARY KEY,
    chunk_index           INTEGER,
    total_chunks          INTEGER,

    -- Source
    source_file           TEXT,
    file_type             TEXT,

    -- Program identity
    program_id            TEXT,
    author                TEXT,
    date_written          TEXT,

    -- Domain classification
    domain                TEXT,
    sub_domain            TEXT,
    processing_type       TEXT,

    -- Structural location
    division              TEXT,
    section_name          TEXT,
    line_start            INTEGER,
    line_end              INTEGER,

    -- Semantic metadata
    section_purpose       TEXT,
    has_file_io           BOOLEAN  DEFAULT FALSE,
    has_error_handling    BOOLEAN  DEFAULT FALSE,

    -- Embedding control
    should_embed          BOOLEAN  DEFAULT TRUE,
    embedding_text        TEXT,

    -- Array fields for filtering
    tags                  TEXT[],
    files_read            TEXT[],
    files_written         TEXT[],
    external_programs     TEXT[],
    copybooks_referenced  TEXT[],
    key_data_fields       TEXT[],

    -- Raw content + full JSON payload
    content               TEXT,
    payload               JSONB,

    -- Vector embedding (text-embedding-3-large, native = 3072 dims)
    embedding             vector(3072),

    created_at            TIMESTAMPTZ DEFAULT NOW()
);

-- ── Metadata indexes (for filtering before vector search) ───────────────────
CREATE INDEX IF NOT EXISTS idx_chunks_domain        ON chunks (domain);
CREATE INDEX IF NOT EXISTS idx_chunks_program_id    ON chunks (program_id);
CREATE INDEX IF NOT EXISTS idx_chunks_file_type     ON chunks (file_type);
CREATE INDEX IF NOT EXISTS idx_chunks_sub_domain    ON chunks (sub_domain);
CREATE INDEX IF NOT EXISTS idx_chunks_processing    ON chunks (processing_type);
CREATE INDEX IF NOT EXISTS idx_chunks_should_embed  ON chunks (should_embed);
CREATE INDEX IF NOT EXISTS idx_chunks_payload       ON chunks USING gin (payload);

-- No HNSW/approximate vector index: at this corpus size (low thousands of
-- chunks), a plain sequential scan + exact cosine distance is already fast
-- (single-digit ms) and always correct. An HNSW index here actively hurts
-- correctness instead: pgvector's LIMIT-aware search-width sizing only
-- applies when LIMIT is a literal known at plan time — cobalt-rag-api's
-- similarity query binds LIMIT as a JDBC prepared-statement parameter, so
-- every real query got the index's un-widened default ef_search, silently
-- returning wrong (lower-similarity) top-K results instead of the true
-- nearest neighbors. Confirmed directly: `PREPARE ... EXECUTE` with the
-- index present missed the correct top match every time; the identical
-- query as a plain literal (or with the index dropped) found it. Revisit
-- only if the corpus grows into the hundreds of thousands of chunks, and
-- if so, tune hnsw.ef_search explicitly rather than relying on the
-- LIMIT-aware default.

-- ── source_file index (for incremental delete before re-processing a file) ──
CREATE INDEX IF NOT EXISTS idx_chunks_source_file ON chunks (source_file);

-- ── Ingestion audit log ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ingestion_runs (
    run_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_name             TEXT NOT NULL,
    commit_sha            TEXT,
    previous_sha          TEXT,
    files_processed       INTEGER DEFAULT 0,
    chunks_upserted       INTEGER DEFAULT 0,
    embeddings_generated  INTEGER DEFAULT 0,
    status                TEXT NOT NULL,   -- SUCCESS | NO_CHANGE | FAILED
    started_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_runs_repo ON ingestion_runs (repo_name, status, completed_at DESC);