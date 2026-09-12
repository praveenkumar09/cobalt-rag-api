package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

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
                   line_start, line_end,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM chunks
            WHERE embedding IS NOT NULL AND should_embed = true
            ORDER BY embedding <=> ?::vector
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
                        rs.getDouble("similarity")
                ),
                vectorStr, vectorStr, topK
        );

        return results.stream()
                .filter(c -> c.similarity() >= similarityThreshold)
                .toList();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
