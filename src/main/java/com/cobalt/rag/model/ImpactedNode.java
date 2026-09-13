package com.cobalt.rag.model;

/** One file/program impacted by a proposed code change (see {@link ImpactAnalysis}). */
public record ImpactedNode(String id, String label, String type) {
}
