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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    @Value("${cobalt.rag.top-k:5}")
    private int topK;

    // Chunks scoring below this cosine similarity are treated as irrelevant and
    // dropped before they reach the LLM context or the response's citation list —
    // this is what keeps off-topic questions from showing any source citations.
    @Value("${cobalt.rag.similarity-threshold:0.35}")
    private double similarityThreshold;

    private static final String SIMILARITY_SQL = """
            SELECT chunk_id, source_file, program_id, domain, sub_domain,
                   section_name, section_purpose, content, file_type,
                   line_start, line_end, key_data_fields,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM chunks
            WHERE embedding IS NOT NULL AND should_embed = true
            ORDER BY embedding <=> ?::vector
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

    public List<ChunkResult> search(String question) {
        float[] vec = embeddingModel.embed(question);
        String vectorStr = toVectorString(vec);

        List<ChunkResult> results = jdbc.query(
                SIMILARITY_SQL,
                (rs, rowNum) -> new ChunkResult(
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
                ),
                vectorStr, vectorStr, topK
        );

        return results.stream()
                .filter(c -> c.similarity() >= similarityThreshold)
                .toList();
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
