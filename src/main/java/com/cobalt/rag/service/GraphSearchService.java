package com.cobalt.rag.service;

import com.cobalt.rag.model.GraphRelationship;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GraphSearchService {

    private final Driver driver;

    @Value("${cobalt.rag.graph-context-limit:25}")
    private int limit;

    // Fetch direct relationships for programs found via vector search
    private static final String PROGRAM_RELS_QUERY = """
            MATCH (n)-[r]->(m)
            WHERE n.id IN $ids
            RETURN n.id AS fromId, n.label AS fromLabel, labels(n)[0] AS fromType,
                   type(r) AS relType,
                   m.id AS toId, m.label AS toLabel, labels(m)[0] AS toType
            LIMIT $limit
            """;

    // Fallback: keyword-based fuzzy lookup on node labels
    private static final String KEYWORD_QUERY = """
            MATCH (n)-[r]->(m)
            WHERE any(kw IN $keywords WHERE
                      toLower(n.label) CONTAINS toLower(kw)
                   OR toLower(n.id)    CONTAINS toLower(kw))
            RETURN n.id AS fromId, n.label AS fromLabel, labels(n)[0] AS fromType,
                   type(r) AS relType,
                   m.id AS toId, m.label AS toLabel, labels(m)[0] AS toType
            LIMIT $limit
            """;

    public GraphSearchService(Driver driver) {
        this.driver = driver;
    }

    public List<GraphRelationship> findRelationships(List<String> programIds, List<String> keywords) {
        List<GraphRelationship> results = new ArrayList<>();
        try (Session session = driver.session()) {
            if (!programIds.isEmpty()) {
                session.run(PROGRAM_RELS_QUERY, Map.of("ids", programIds, "limit", limit))
                       .list()
                       .forEach(rec -> addRelationship(results, rec));
            }
            if (!keywords.isEmpty() && results.size() < limit) {
                session.run(KEYWORD_QUERY, Map.of("keywords", keywords, "limit", limit - results.size()))
                       .list()
                       .forEach(rec -> addRelationship(results, rec));
            }
        } catch (Exception ex) {
            // Graph context is best-effort; do not fail the whole request
            return List.of();
        }
        return results.stream().distinct().toList();
    }

    private void addRelationship(List<GraphRelationship> results, org.neo4j.driver.Record record) {
        String fromId = record.get("fromId").asString("");
        String fromLabel = record.get("fromLabel").asString("");
        String fromType = record.get("fromType").asString("");
        String relType = record.get("relType").asString("");
        String toId = record.get("toId").asString("");
        String toLabel = record.get("toLabel").asString("");
        String toType = record.get("toType").asString("");
        if (!fromLabel.isBlank() && !toLabel.isBlank()) {
            results.add(new GraphRelationship(fromId, fromLabel, fromType, relType, toId, toLabel, toType));
        }
    }
}
