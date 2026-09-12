package com.cobalt.rag.model;

/**
 * A single directed edge from the program-relationship graph (Neo4j), kept
 * structured — rather than flattened into a display string — so the UI can
 * render it as a proper node/edge diagram (cards, chain, or hub layout).
 */
public record GraphRelationship(
        String fromId,
        String fromLabel,
        String fromType,
        String relType,
        String toId,
        String toLabel,
        String toType
) {
}
