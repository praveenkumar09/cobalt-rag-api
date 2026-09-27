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
import java.util.stream.Collectors;

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

    // Of the candidate nodes found by REACHES_ROOTS_QUERY, which ones actually
    // define or reference at least one of the specific field(s) the change
    // discusses — as opposed to merely being structurally reachable (e.g.
    // sharing a copybook without ever touching that field). Used to narrow a
    // pure-reachability result down to real, field-level impact.
    private static final String FIELD_RELEVANT_QUERY = """
            MATCH (n)-[:DEFINES|REFERENCES]->(f:FIELD)
            WHERE n.id IN $candidateIds AND f.id IN $fieldIds
            RETURN DISTINCT n.id AS id
            """;

    // Whether the discussed field(s) exist as FIELD nodes AT ALL — distinct from
    // FIELD_RELEVANT_QUERY finding no REFERENCING programs. A brand-new field
    // ("add a new field to X") legitimately has zero DEFINES/REFERENCES edges
    // anywhere yet, so narrowing by reference is meaningless for it: see the
    // "new field" branch in analyze() below, which falls back to structural
    // reachability (which copybook + which programs COPY it) instead of
    // narrowing to nothing, specifically for this case.
    private static final String FIELD_EXISTS_QUERY = """
            MATCH (f:FIELD)
            WHERE f.id IN $fieldIds
            RETURN DISTINCT f.id AS id
            """;

    private final Driver driver;

    public ImpactAnalysisService(Driver driver) {
        this.driver = driver;
    }

    public ImpactAnalysis analyze(List<String> seedProgramIds) {
        return analyze(seedProgramIds, Set.of());
    }

    /**
     * Same as {@link #analyze(List)}, but when {@code fieldNames} is non-empty,
     * narrows the result to candidate nodes that actually DEFINE or REFERENCE at
     * least one of those fields (per the FIELD graph edges written by the
     * ingestor) — pure structural reachability (sharing a copybook, being a
     * transitive caller) is no longer sufficient on its own. The seed program(s)
     * and their own copybooks (tier 0) are always kept regardless of the field
     * filter, since the program you asked about is trivially "impacted."
     *
     * This is deliberately precise, not broad: a program that merely shares a
     * copybook with the seed but never touches the specific field(s) the change
     * discusses is NOT included, even though it's structurally reachable. When
     * the field filter narrows everything away, that's the real answer ("nothing
     * else genuinely needs this change") — the result is the seed tier alone,
     * not a silent fallback to the wider structural list.
     *
     * Returns null only when {@code fieldNames} is empty AND there's no
     * structural reachability either — genuinely nothing to show, field-level or
     * otherwise.
     */
    public ImpactAnalysis analyze(List<String> seedProgramIds, Set<String> fieldNames) {
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

            var reached = session.run(REACHES_ROOTS_QUERY,
                    Map.of("rootIds", List.copyOf(rootIds), "limit", MAX_IMPACTED_NODES)).list();
            boolean truncated = reached.size() >= MAX_IMPACTED_NODES;

            Map<String, ImpactedNode> candidates = new HashMap<>();
            reached.forEach(rec -> putNode(candidates, rec));

            Set<String> fieldIds = fieldNames == null ? Set.of() : fieldNames.stream()
                    .filter(f -> f != null && !f.isBlank())
                    .map(f -> f.trim().toUpperCase())
                    .collect(Collectors.toSet());

            if (!fieldIds.isEmpty()) {
                var existingFields = session.run(FIELD_EXISTS_QUERY, Map.of("fieldIds", List.copyOf(fieldIds))).list();
                if (existingFields.isEmpty()) {
                    // None of the discussed field(s) exist as FIELD nodes anywhere yet —
                    // a new-field scenario. Reference-based narrowing can't say anything
                    // useful about a field nothing has ever DEFINED or REFERENCED, so
                    // treat this the same as "no field filter": show the full structural
                    // reachability (the copybook the seed copies, and every program that
                    // in turn copies THAT copybook) — those are exactly the files that
                    // would need reviewing for a genuinely new field, even though none of
                    // them reference it (yet, by definition).
                    fieldIds = Set.of();
                }
            }

            if (!fieldIds.isEmpty() && !candidates.isEmpty()) {
                var relevant = session.run(FIELD_RELEVANT_QUERY,
                        Map.of("candidateIds", List.copyOf(candidates.keySet()), "fieldIds", List.copyOf(fieldIds))
                ).list();
                Set<String> relevantIds = new HashSet<>();
                relevant.forEach(rec -> relevantIds.add(rec.get("id").asString("")));
                candidates.keySet().retainAll(relevantIds);
            }

            candidates.forEach(nodesById::putIfAbsent);

            if (nodesById.size() <= rootIds.size()) {
                if (fieldIds.isEmpty()) {
                    // No field filter was even applied, and there's no structural
                    // reachability either — genuinely nothing to show.
                    return null;
                }
                // A field filter WAS applied and narrowed every structurally-reachable
                // candidate away. That IS the real, precise answer — "no other file in
                // the system actually references these specific fields" — not a reason
                // to fall back to the broader copybook-sharing/caller list (which is
                // what returning null used to trigger upstream, via switchIfEmpty).
                // Fall through and return just the seed tier, so the UI still confirms
                // what change-relevant scope was actually checked.
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
