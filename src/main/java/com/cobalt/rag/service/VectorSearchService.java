package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import com.cobalt.rag.model.CorpusSample;
import com.cobalt.rag.model.DomainTag;
import com.cobalt.rag.model.ProgramSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    // How many candidates EACH retrieval method (vector on the question, vector
    // on a HyDE expansion, keyword) contributes before RRF-merging and handing
    // off to reranking — wider than topK on purpose: a fixed top-5 by raw
    // similarity has no recovery path if the right chunk isn't in the top 5;
    // widening the net here and letting reranking (see RerankService) pick the
    // best topK from a bigger, hybrid-retrieved pool fixes that.
    @Value("${cobalt.rag.retrieval.candidate-pool-size:20}")
    private int candidatePoolSize;

    // Chunks scoring below this cosine similarity are treated as irrelevant —
    // applied only to the VECTOR component of a candidate's score (a pure
    // keyword-only match has no cosine score to threshold against).
    @Value("${cobalt.rag.similarity-threshold:0.35}")
    private double similarityThreshold;

    // Standard Reciprocal Rank Fusion constant (k=60, from the original RRF
    // paper) — large enough that a #1-ranked result in one list isn't wildly
    // more dominant than a #1 in another, small enough that rank position
    // still matters more than which list a candidate came from.
    private static final int RRF_K = 60;

    private static final String VECTOR_SQL = """
            SELECT chunk_id, source_file, program_id, domain, sub_domain,
                   section_name, section_purpose, content, file_type,
                   line_start, line_end, key_data_fields,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM chunks
            WHERE embedding IS NOT NULL AND should_embed = true
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

    // 'simple' text-search config deliberately, not 'english' — no
    // stemming/stopword removal, which would mangle hyphenated COBOL
    // identifiers (WS-POLICY-YEARS) rather than help match them. similarity
    // is always 0 here (no cosine score for a keyword-only match); ts_rank
    // drives this list's own ranking instead, fed into the RRF merge below.
    private static final String KEYWORD_SQL = """
            SELECT chunk_id, source_file, program_id, domain, sub_domain,
                   section_name, section_purpose, content, file_type,
                   line_start, line_end, key_data_fields,
                   0.0 AS similarity
            FROM chunks
            WHERE should_embed = true AND content_tsv @@ plainto_tsquery('simple', ?)
            ORDER BY ts_rank(content_tsv, plainto_tsquery('simple', ?)) DESC
            LIMIT ?
            """;

    // Chunk rows are contiguous and gapless per program (the ingestor's chunker
    // closes each chunk exactly where the next one starts), so every chunk for a
    // program — not just the embedded/should_embed ones — ordered by line_start
    // reconstructs the complete original file, never a fabricated approximation.
    private static final String FULL_SOURCE_SQL = """
            SELECT source_file, content
            FROM chunks
            WHERE program_id = ?
            ORDER BY line_start NULLS LAST
            """;

    // Random sample of real, already-ingested programs/sections — used to ground
    // the home-screen starter-question suggestions in whatever codebase is
    // actually loaded, instead of a hardcoded example that can drift out of sync.
    private static final String SAMPLE_SQL = """
            SELECT program_id, domain, sub_domain, section_name, section_purpose
            FROM chunks
            WHERE should_embed = true AND section_purpose IS NOT NULL AND section_purpose <> ''
            ORDER BY random()
            LIMIT ?
            """;

    public VectorSearchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    /** Convenience overload — no HyDE query expansion. */
    public List<ChunkResult> search(String question) {
        return search(question, null);
    }

    /**
     * Hybrid retrieval: vector search on the question (+ optionally a HyDE
     * expansion, see RagService) and keyword search, each contributing up to
     * candidatePoolSize candidates, merged via Reciprocal Rank Fusion into one
     * ranked candidate pool. Returns the pool as-is (up to candidatePoolSize
     * chunks) — narrowing to the final topK actually used in the answer prompt
     * is RerankService's job, not this method's; callers that don't rerank
     * should truncate to topK themselves.
     *
     * @param hydeQuestion a short hypothetical-answer expansion of {@code question}
     *                     (see RagService's query-expansion step), or null to skip it.
     */
    public List<ChunkResult> search(String question, String hydeQuestion) {
        boolean hasHyde = hydeQuestion != null && !hydeQuestion.isBlank();

        // The 2-3 sub-queries are independent (each its own embedding call +
        // DB round-trip) — run them concurrently rather than paying their
        // latency one after another, same virtual-thread pattern RagService
        // already uses for its own independent sub-tasks.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<ChunkResult>> vectorFuture = executor.submit(() -> vectorSearch(question));
            Future<List<ChunkResult>> hydeFuture = hasHyde
                    ? executor.submit(() -> vectorSearch(hydeQuestion)) : null;
            Future<List<ChunkResult>> keywordFuture = executor.submit(() -> keywordSearch(question));

            List<ChunkResult> vectorHits = getOrEmpty(vectorFuture);
            List<ChunkResult> hydeHits = getOrEmpty(hydeFuture);
            List<ChunkResult> keywordHits = getOrEmpty(keywordFuture);

            return reciprocalRankFusion(vectorHits, hydeHits, keywordHits);
        }
    }

    private List<ChunkResult> getOrEmpty(Future<List<ChunkResult>> future) {
        if (future == null) return List.of();
        try {
            return future.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ChunkResult> vectorSearch(String queryText) {
        float[] vec = embeddingModel.embed(queryText);
        String vectorStr = toVectorString(vec);
        List<ChunkResult> results = jdbc.query(
                VECTOR_SQL,
                (rs, rowNum) -> mapRow(rs),
                vectorStr, vectorStr, candidatePoolSize
        );
        // Similarity threshold applies to the vector component specifically —
        // a chunk that only clears the bar via keyword/HyDE ranking is still
        // eligible below, just not counted as a vector "hit" for this list.
        return results.stream().filter(c -> c.similarity() >= similarityThreshold).toList();
    }

    private List<ChunkResult> keywordSearch(String queryText) {
        try {
            return jdbc.query(KEYWORD_SQL, (rs, rowNum) -> mapRow(rs),
                    queryText, queryText, candidatePoolSize);
        } catch (Exception e) {
            // plainto_tsquery on a pathological input (e.g. only stopwords/punctuation)
            // can return zero terms and therefore no matches — never let the keyword
            // side of hybrid search fail the whole retrieval; vector search alone
            // is exactly today's pre-hybrid-search behavior.
            return List.of();
        }
    }

    /**
     * Merges ranked lists by Reciprocal Rank Fusion: each list contributes
     * 1/(RRF_K + rank) per chunk it contains (rank is 1-based position within
     * THAT list), summed across lists, then sorted descending. A chunk found
     * by multiple retrieval methods — e.g. both semantically similar AND
     * containing the literal search term — naturally outranks one found by
     * only one method, without needing to hand-tune how vector vs. keyword
     * scores compare on two totally different scales (cosine similarity vs.
     * ts_rank) — RRF sidesteps that by using rank position, not raw score.
     */
    private List<ChunkResult> reciprocalRankFusion(List<ChunkResult>... rankedLists) {
        Map<String, Double> scoreByChunkId = new LinkedHashMap<>();
        Map<String, ChunkResult> chunkById = new LinkedHashMap<>();
        for (List<ChunkResult> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                ChunkResult c = list.get(i);
                int rank = i + 1;
                scoreByChunkId.merge(c.chunkId(), 1.0 / (RRF_K + rank), Double::sum);
                // Prefer the version of this chunk carrying a non-zero similarity
                // (i.e. the one from a vector list) over a keyword-only row whose
                // similarity is a placeholder 0.0, so downstream similarity display
                // (citations) shows the real cosine score when one exists.
                chunkById.merge(c.chunkId(), c, (existing, replacement) ->
                        existing.similarity() >= replacement.similarity() ? existing : replacement);
            }
        }
        return scoreByChunkId.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> chunkById.get(e.getKey()))
                .limit(candidatePoolSize)
                .toList();
    }

    private ChunkResult mapRow(ResultSet rs) throws SQLException {
        return new ChunkResult(
                rs.getString("chunk_id"),
                rs.getString("source_file"),
                rs.getString("program_id"),
                rs.getString("domain"),
                rs.getString("sub_domain"),
                rs.getString("section_name"),
                rs.getString("section_purpose"),
                rs.getString("content"),
                rs.getString("file_type"),
                nullableInt(rs, "line_start"),
                nullableInt(rs, "line_end"),
                rs.getDouble("similarity"),
                nullableStringList(rs, "key_data_fields")
        );
    }

    public Optional<ProgramSource> fetchFullSource(String programId) {
        List<Object[]> rows = jdbc.query(
                FULL_SOURCE_SQL,
                (rs, rowNum) -> new Object[]{rs.getString("source_file"), rs.getString("content")},
                programId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String sourceFile = (String) rows.get(0)[0];
        String content = rows.stream()
                .map(row -> (String) row[1])
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return Optional.of(new ProgramSource(programId, sourceFile, content));
    }

    /**
     * Each program's real, most-frequent domain/sub-domain tag from ingestion —
     * used to relabel the technical call-graph as a business-activity flow
     * (see BusinessInsightService) without inventing any label.
     */
    public Map<String, DomainTag> fetchDomainTags(List<String> programIds) {
        if (programIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = programIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT program_id, domain, sub_domain, count(*) AS cnt FROM chunks " +
                "WHERE program_id IN (" + placeholders + ") " +
                "GROUP BY program_id, domain, sub_domain ORDER BY program_id, cnt DESC";

        List<Object[]> rows = jdbc.query(
                sql,
                (rs, rowNum) -> new Object[]{rs.getString("program_id"), rs.getString("domain"), rs.getString("sub_domain")},
                programIds.toArray()
        );

        Map<String, DomainTag> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.putIfAbsent((String) row[0], new DomainTag((String) row[1], (String) row[2]));
        }
        return result;
    }

    public List<CorpusSample> sampleForSuggestions(int limit) {
        return jdbc.query(
                SAMPLE_SQL,
                (rs, rowNum) -> new CorpusSample(
                        rs.getString("program_id"),
                        rs.getString("domain"),
                        rs.getString("sub_domain"),
                        rs.getString("section_name"),
                        rs.getString("section_purpose")
                ),
                limit
        );
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> nullableStringList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return null;
        Object[] elements = (Object[]) array.getArray();
        List<String> result = new java.util.ArrayList<>(elements.length);
        for (Object e : elements) {
            if (e != null) result.add(e.toString());
        }
        return result.isEmpty() ? null : result;
    }

    private static String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
    }
}
