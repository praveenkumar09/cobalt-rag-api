package com.cobalt.rag.service;

import com.cobalt.rag.model.ImpactAnalysis;
import com.cobalt.rag.model.ImpactTier;
import com.cobalt.rag.model.ImpactedNode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Given the program(s) a recommended code change touches, finds every other
 * file/program that would be impacted — transitively, via CALLS/EXECUTES, or
 * because it shares a copybook with the changed program(s) — and orders them
 * into change-order tiers so the caller knows what needs to change first.
 *
 * CALLS, EXECUTES, and COPIES are all treated uniformly as "depends-on" edges
 * (dependent -> dependency): a program CALLS/uses another program, a JCL job
 * EXECUTES a program, a program COPIES a copybook. Tier order is derived by
 * peeling off, one layer at a time, whichever nodes have no remaining
 * unresolved dependency (Kahn's topological sort) — the changed program(s)
 * and any copybook(s) they copy naturally fall out as tier 0, their direct
 * callers/copybook-siblings as tier 1, and so on.
 */
@Service
public class ImpactAnalysisService {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_IMPACTED_NODES = 30;

    // Copybook(s) the seed program(s) themselves depend on — effectively part
    // of "the change" when a data-layout field is what's being modified.
    private static final String SEED_COPYBOOKS_QUERY = """
            MATCH (p)-[:COPIES]->(c)
            WHERE p.id IN $seedIds
            RETURN DISTINCT c.id AS id, c.label AS label, labels(c)[0] AS type
            """;

    // Everything that reverse-reaches the combined root set via a CALLS,
    // EXECUTES, or COPIES chain — transitive callers AND copybook-sharing
    // siblings (and callers of those siblings), in one bounded traversal.
    private static final String REACHES_ROOTS_QUERY = """
            MATCH (n)-[:CALLS|EXECUTES|COPIES*1..%d]->(root)
            WHERE root.id IN $rootIds AND NOT n.id IN $rootIds
            RETURN DISTINCT n.id AS id, n.label AS label, labels(n)[0] AS type
            LIMIT $limit
            """.formatted(MAX_DEPTH);

    // The "depends-on" edges among the final impacted set, used to derive
    // change order via topological sort.
    private static final String INDUCED_EDGES_QUERY = """
            MATCH (a)-[:CALLS|EXECUTES|COPIES]->(b)
            WHERE a.id IN $ids AND b.id IN $ids
            RETURN a.id AS fromId, b.id AS toId
            """;

    private final Driver driver;

    public ImpactAnalysisService(Driver driver) {
        this.driver = driver;
    }

    public ImpactAnalysis analyze(List<String> seedProgramIds) {
        if (seedProgramIds == null || seedProgramIds.isEmpty()) {
            return null;
        }

        try (Session session = driver.session()) {
            Map<String, ImpactedNode> nodesById = new HashMap<>();
            seedProgramIds.forEach(id -> nodesById.put(id, new ImpactedNode(id, id, "PROGRAM")));

            // Seed copybooks — part of tier 0 alongside the seed program(s).
            session.run(SEED_COPYBOOKS_QUERY, Map.of("seedIds", seedProgramIds))
                    .list()
                    .forEach(rec -> putNode(nodesById, rec));

            Set<String> rootIds = new LinkedHashSet<>(nodesById.keySet());

            boolean truncated;
            var reached = session.run(REACHES_ROOTS_QUERY,
                    Map.of("rootIds", List.copyOf(rootIds), "limit", MAX_IMPACTED_NODES)).list();
            reached.forEach(rec -> putNode(nodesById, rec));
            truncated = reached.size() >= MAX_IMPACTED_NODES;

            if (nodesById.size() <= rootIds.size()) {
                // Nothing beyond the change target itself — no impact to show.
                return null;
            }

            Set<String> allIds = nodesById.keySet();
            var edgeRecords = session.run(INDUCED_EDGES_QUERY, Map.of("ids", List.copyOf(allIds))).list();

            List<ImpactTier> tiers = topologicalTiers(allIds, edgeRecords, nodesById);
            return new ImpactAnalysis(tiers, truncated);
        } catch (Exception ex) {
            // Impact analysis is best-effort, same as key-relationships graph context.
            return null;
        }
    }

    private List<ImpactTier> topologicalTiers(Set<String> allIds, List<Record> edgeRecords,
                                               Map<String, ImpactedNode> nodesById) {
        // dependsOn[a] = the things a depends on (within this set); dependents[b] = the things that depend on b.
        Map<String, Set<String>> dependsOn = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Record rec : edgeRecords) {
            String from = rec.get("fromId").asString("");
            String to = rec.get("toId").asString("");
            if (from.isBlank() || to.isBlank() || from.equals(to)) continue;
            dependsOn.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            dependents.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
        }

        Map<String, Integer> remainingDeps = new HashMap<>();
        for (String id : allIds) {
            remainingDeps.put(id, dependsOn.getOrDefault(id, Set.of()).size());
        }

        List<ImpactTier> tiers = new ArrayList<>();
        Set<String> pending = new HashSet<>(allIds);

        while (!pending.isEmpty()) {
            List<String> ready = pending.stream()
                    .filter(id -> remainingDeps.get(id) == 0)
                    .sorted()
                    .toList();

            if (ready.isEmpty()) {
                // A cycle among the remaining nodes — surface them together rather
                // than looping forever or dropping them.
                List<ImpactedNode> cycleNodes = pending.stream()
                        .sorted()
                        .map(nodesById::get)
                        .toList();
                tiers.add(new ImpactTier(tiers.size(), cycleNodes, true));
                break;
            }

            List<ImpactedNode> tierNodes = ready.stream().map(nodesById::get).toList();
            tiers.add(new ImpactTier(tiers.size(), tierNodes, false));

            for (String id : ready) {
                pending.remove(id);
                for (String dependent : dependents.getOrDefault(id, List.of())) {
                    if (pending.contains(dependent)) {
                        remainingDeps.merge(dependent, -1, Integer::sum);
                    }
                }
            }
        }

        return tiers;
    }

    private void putNode(Map<String, ImpactedNode> nodesById, Record record) {
        String id = record.get("id").asString("");
        String label = record.get("label").asString(id);
        String type = record.get("type").asString("");
        if (!id.isBlank()) {
            nodesById.putIfAbsent(id, new ImpactedNode(id, label, type));
        }
    }
}
