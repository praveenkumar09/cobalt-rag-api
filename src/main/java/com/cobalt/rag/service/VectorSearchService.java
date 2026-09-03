package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    @Value("${cobalt.rag.top-k:5}")
    private int topK;

    private static final String SIMILARITY_SQL = """
            SELECT chunk_id, source_file, program_id, domain, sub_domain,
                   section_name, section_purpose, content, file_type,
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

        return jdbc.query(
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
                        rs.getDouble("similarity")
                ),
                vectorStr, vectorStr, topK
        );
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