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

    // Per-DIRECTION cap (not combined) — outgoing and incoming each get this
    // many relationship rows, so a program with many more callees than
    // callers (or vice versa) can never crowd the other direction out.
    @Value("${cobalt.rag.graph-context-limit:25}")
    private int limit;

    // How many hops to trace in each direction. Kept modest — call graphs
    // fan out fast, and this also bounds how many PATHS Neo4j has to explore.
    @Value("${cobalt.rag.graph-max-hops:4}")
    private int configuredMaxHops;

    // Caps the number of PATHS considered (before they're unwound into
    // individual edges), independent of how many edge ROWS are ultimately
    // returned — protects against combinatorial blow-up in a densely
    // connected graph at higher hop counts.
    @Value("${cobalt.rag.graph-path-limit:100}")
    private int pathLimit;

    // Neo4j requires the hop bound in a variable-length pattern to be a
    // literal in the query text, not a bindable parameter — so these two
    // query strings are built once a hop count is known, from our own config
    // (never user input), with the value clamped to a sane range first.
    private String outgoingQuery;
    private String incomingQuery;

    // Traces what the seed program(s) call, transitively — direct callees,
    // what THOSE call, and so on up to maxHops. UNWIND flattens each matched
    // path into its individual edges so the caller sees every hop, not just
    // the first; WITH DISTINCT r drops an edge that appears in more than one
    // path (common with overlapping call chains).
    private static final String OUTGOING_TEMPLATE = """
            MATCH path = (n)-[*1..%d]->(m)
            WHERE n.id IN $ids
            WITH path
            LIMIT $pathLimit
            UNWIND relationships(path) AS r
            WITH DISTINCT r
            RETURN startNode(r).id AS fromId, startNode(r).label AS fromLabel, labels(startNode(r))[0] AS fromType,
                   type(r) AS relType,
                   endNode(r).id AS toId, endNode(r).label AS toLabel, labels(endNode(r))[0] AS toType
            LIMIT $rowLimit
            """;

    // Traces what calls INTO the seed program(s), transitively — direct
    // callers, what calls THOSE, and so on up to maxHops.
    private static final String INCOMING_TEMPLATE = """
            MATCH path = (m)-[*1..%d]->(n)
            WHERE n.id IN $ids
            WITH path
            LIMIT $pathLimit
            UNWIND relationships(path) AS r
            WITH DISTINCT r
            RETURN startNode(r).id AS fromId, startNode(r).label AS fromLabel, labels(startNode(r))[0] AS fromType,
                   type(r) AS relType,
                   endNode(r).id AS toId, endNode(r).label AS toLabel, labels(endNode(r))[0] AS toType
            LIMIT $rowLimit
            """;

    // Fallback: keyword-based fuzzy lookup on node labels — only used when no
    // seed program is known. Deliberately stays a single undirected hop:
    // without a seed there's no program to trace multi-hop chains FROM.
    private static final String KEYWORD_QUERY = """
            MATCH (n)-[r]-(m)
            WHERE any(kw IN $keywords WHERE
                      toLower(n.label) CONTAINS toLower(kw)
                   OR toLower(n.id)    CONTAINS toLower(kw)
                   OR toLower(m.label) CONTAINS toLower(kw)
                   OR toLower(m.id)    CONTAINS toLower(kw))
            RETURN startNode(r).id AS fromId, startNode(r).label AS fromLabel, labels(startNode(r))[0] AS fromType,
                   type(r) AS relType,
                   endNode(r).id AS toId, endNode(r).label AS toLabel, labels(endNode(r))[0] AS toType
            LIMIT $limit
            """;

    public GraphSearchService(Driver driver) {
        this.driver = driver;
    }

    private String outgoingQuery() {
        if (outgoingQuery == null) {
            outgoingQuery = OUTGOING_TEMPLATE.formatted(clampedMaxHops());
        }
        return outgoingQuery;
    }

    private String incomingQuery() {
        if (incomingQuery == null) {
            incomingQuery = INCOMING_TEMPLATE.formatted(clampedMaxHops());
        }
        return incomingQuery;
    }

    private int clampedMaxHops() {
        return Math.max(1, Math.min(configuredMaxHops, 6));
    }

    public List<GraphRelationship> findRelationships(List<String> programIds, List<String> keywords) {
        List<GraphRelationship> results = new ArrayList<>();
        try (Session session = driver.session()) {
            if (!programIds.isEmpty()) {
                session.run(outgoingQuery(), Map.of("ids", programIds, "pathLimit", pathLimit, "rowLimit", limit))
                       .list()
                       .forEach(rec -> addRelationship(results, rec, "OUTGOING"));
                session.run(incomingQuery(), Map.of("ids", programIds, "pathLimit", pathLimit, "rowLimit", limit))
                       .list()
                       .forEach(rec -> addRelationship(results, rec, "INCOMING"));
            }
            int combinedLimit = limit * 2;
            if (!keywords.isEmpty() && results.size() < combinedLimit) {
                session.run(KEYWORD_QUERY, Map.of("keywords", keywords, "limit", combinedLimit - results.size()))
                       .list()
                       .forEach(rec -> addRelationship(results, rec, null));
            }
        } catch (Exception ex) {
            // Graph context is best-effort; do not fail the whole request
            return List.of();
        }
        return results.stream().distinct().toList();
    }

    private void addRelationship(List<GraphRelationship> results, org.neo4j.driver.Record record, String direction) {
        String fromId = record.get("fromId").asString("");
        String fromLabel = record.get("fromLabel").asString("");
        String fromType = record.get("fromType").asString("");
        String relType = record.get("relType").asString("");
        String toId = record.get("toId").asString("");
        String toLabel = record.get("toLabel").asString("");
        String toType = record.get("toType").asString("");
        if (!fromLabel.isBlank() && !toLabel.isBlank()) {
            results.add(new GraphRelationship(fromId, fromLabel, fromType, relType, toId, toLabel, toType, direction));
        }
    }
}
