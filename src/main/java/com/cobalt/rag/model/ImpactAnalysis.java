package com.cobalt.rag.model;

import java.util.List;

/**
 * Change-impact analysis for a recommended code change: every file/program
 * impacted (transitively, via calls or a shared copybook), grouped into
 * ordered tiers — tier 0 must change before tier 1, tier 1 before tier 2, etc.
 */
public record ImpactAnalysis(List<ImpactTier> tiers, boolean truncated) {
}
