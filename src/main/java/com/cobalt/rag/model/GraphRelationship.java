package com.cobalt.rag.model;

/**
 * A single directed edge from the program-relationship graph (Neo4j), kept
 * structured — rather than flattened into a display string — so the UI can
 * render it as a proper node/edge diagram (cards, chain, or hub layout).
 *
 * @param direction How this edge relates to the program(s) the question was
 *                   actually about: "OUTGOING" if found by tracing what that
 *                   program calls (transitively, possibly several hops
 *                   away), "INCOMING" if found by tracing what calls into it
 *                   (transitively), or null for edges from the keyword
 *                   fallback / synthetic business-flow edges, which have no
 *                   single seed program to be directional relative to.
 */
public record GraphRelationship(
        String fromId,
        String fromLabel,
        String fromType,
        String relType,
        String toId,
        String toLabel,
        String toType,
        String direction
) {
}
