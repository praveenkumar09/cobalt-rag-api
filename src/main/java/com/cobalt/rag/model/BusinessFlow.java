package com.cobalt.rag.model;

import java.util.List;

/** Business-activity counterpart of the technical "Key Relationships" graph. */
public record BusinessFlow(List<BusinessFlowEdge> edges) {
}
