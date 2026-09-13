package com.cobalt.rag.model;

/** One step of the business-activity counterpart to the technical program call-graph. */
public record BusinessFlowEdge(String fromActivity, String relation, String toActivity) {
}
