package com.cobalt.rag.model;

import java.util.List;

/**
 * One step in the change-order sequence: all the nodes in this tier depend
 * only on nodes from earlier tiers (or nothing), so they can all be changed
 * together, only after every earlier tier has been changed.
 */
public record ImpactTier(int order, List<ImpactedNode> nodes, boolean hasCycle) {
}
