package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import com.cobalt.rag.model.ProgramSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Backs the "current vs. proposed" code-compare view opened from an Impact
 * Analysis entry. The current side is always the real ingested source (never
 * fabricated — see {@link VectorSearchService#fetchFullSource}); the proposed
 * side is a real LLM generation grounded in that exact source plus the actual
 * chat answer that recommended the change, not a canned diff.
 *
 * <p>A file small enough to fit comfortably in one prompt is generated whole,
 * same as before. A LARGE file is handled agentically instead of being
 * rejected: (1) locate — a cheap LLM call scans the program's section names
 * and ingestor-written purpose descriptions (not their content — this scales
 * with section COUNT, never file size) and picks which section(s) actually
 * need to change; (2) generate — a small, scoped LLM call produces just the
 * modified version of each selected section; (3) splice — those sections are
 * substituted back into the real full source, so the returned proposal is
 * still the complete file, byte-identical everywhere except what genuinely
 * changed. No single LLM call in this path is ever close to whole-file size,
 * regardless of how large the file is. Every stage narrates itself via the
 * {@code onStep} callback (surfaced to the UI as "thinking" events over SSE —
 * see {@link #proposeChangeStream}), so the user watches the same
 * locate → generate → splice reasoning a human would do by hand.
 */
@Service
public class CodeChangeService {

    private static final String WHOLE_FILE_SYSTEM_PROMPT = """
            You are a COBOL code-modification assistant. You are given the ORIGINAL \
            source of one program exactly as it exists, and a description of a \
            recommended change taken from a prior question-and-answer exchange.

            Output the FULL modified source implementing that change:
            - Preserve every unrelated line exactly as-is — same columns, spacing, and formatting.
            - Change only what the described recommendation actually requires.
            - Output raw COBOL source only: no markdown code fences, no commentary, \
              no explanation before or after.
            """;

    private static final String LOCATE_SECTION_SYSTEM_PROMPT = """
            You are given a COBOL program's list of sections — each with a chunk id, \
            section name, a short natural-language description of what it does, and its \
            line range in the file — plus a user's question and the answer already given \
            recommending a change to this program.

            Identify which section(s) would actually need to change to implement that \
            recommendation. Select as few as genuinely need to change — usually just one, \
            rarely more than two or three. Only select a section you are confident about \
            based on its description; never guess or select one "just in case".

            Respond with ONLY a JSON object with exactly one key, "chunkIds": an ordered \
            array (most relevant first) of the exact chunk id strings from the list above \
            that need to change. Return an empty array if you cannot confidently identify \
            which section(s) apply. Example:
            {"chunkIds":["PERFTEST_3100-LOOKUP-COI-RATE_030"]}
            """;

    private static final String GENERATE_SECTION_SYSTEM_PROMPT = """
            You are a COBOL code-modification assistant. You are given ONE SECTION of a \
            larger COBOL program exactly as it exists (not the whole file), identified by \
            its section name and line range, plus a description of a recommended change \
            taken from a prior question-and-answer exchange about that program.

            Output the FULL modified version of JUST this section:
            - Preserve every unrelated line exactly as-is — same columns, spacing, and formatting.
            - Change only what the described recommendation actually requires within this section.
            - Do not add, remove, or reference anything outside this section's own boundaries.
            - Output raw COBOL source only: no markdown code fences, no commentary, \
              no explanation before or after.
            """;

    private static final ResponseFormat JSON_OBJECT_FORMAT =
            ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();

    // Whole-file generation is only attempted below this size — see the class
    // Javadoc for what happens above it. A real paragraph/section is never
    // anywhere near this size even in a huge file, so it doubles as the
    // per-section ceiling too: a section that's STILL this big (almost always
    // an ingestion coverage-gap raw-content fallback, not a real paragraph —
    // see VectorSearchService's CHUNKS_FOR_PROGRAM_SQL comment) is skipped
    // rather than risking the same failure mode one level down.
    @Value("${cobalt.rag.propose-change.max-source-lines:3000}")
    private int maxSectionLines;

    // How many of the located section(s) to actually attempt generating a
    // change for, at most — bounds both cost and how much a single "propose
    // change" click can touch, even if the locate step is over-confident.
    private static final int MAX_SECTIONS_PER_CHANGE = 3;

    private final VectorSearchService vectorSearch;
    private final ChatModel chatModel;
    private final RagMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeChangeService(VectorSearchService vectorSearch, ChatModel chatModel, RagMetrics metrics) {
        this.vectorSearch = vectorSearch;
        this.chatModel = chatModel;
        this.metrics = metrics;
    }

    public Optional<ProgramSource> getSource(String programId) {
        return vectorSearch.fetchFullSource(programId);
    }

    /**
     * {@code steps} is the "thinking" narration, in the order it happened —
     * shown in the UI regardless of response mode (streamed live over SSE for
     * "Live" mode, returned all at once here for "Full" mode). Exactly one of
     * proposedSource/errorMessage is non-null on success/failure; both null
     * (with notFound=true) means no ingested source exists for programId at all.
     */
    public record ProposeChangeOutcome(List<String> steps, String proposedSource, String errorMessage, boolean notFound) {
        static ProposeChangeOutcome sourceNotFound() {
            return new ProposeChangeOutcome(List.of(), null, null, true);
        }
        static ProposeChangeOutcome error(List<String> steps, String message) {
            return new ProposeChangeOutcome(steps, null, message, false);
        }
        static ProposeChangeOutcome success(List<String> steps, String proposedSource) {
            return new ProposeChangeOutcome(steps, proposedSource, null, false);
        }
    }

    public ProposeChangeOutcome proposeChange(String programId, String question, String answer) {
        List<String> steps = new ArrayList<>();
        return runAgent(programId, question, answer, steps::add);
    }

    /**
     * Streaming variant used when the user's response-mode setting is "Live".
     * Event shapes over SSE:
     *   {"type":"thinking","message":"..."}   — zero or more, as each stage happens
     *   {"type":"result","proposedSource":"..."}   — on success
     *   {"type":"error","message":"..."}    — on failure (source not found, couldn't
     *                                          locate a section, etc.) instead of result
     *   [DONE]   — always last
     * No "token" events: unlike the main chat answer, a proposed-change generation
     * isn't prose meant to be read as it streams — the "thinking" log IS the live
     * progress signal now, and the result only makes sense as one complete,
     * already-spliced file, not a growing fragment.
     */
    public Flux<String> proposeChangeStream(String programId, String question, String answer) {
        return Flux.<String>create(sink -> {
                    ProposeChangeOutcome outcome;
                    try {
                        outcome = runAgent(programId, question, answer,
                                message -> sink.next(toJson(Map.of("type", "thinking", "message", message))));
                    } catch (Exception e) {
                        sink.next(toJson(Map.of("type", "error", "message", "Unexpected error generating the proposed change.")));
                        sink.next("[DONE]");
                        sink.complete();
                        return;
                    }
                    if (outcome.notFound()) {
                        sink.next(toJson(Map.of("type", "error", "message", "Source not available for " + programId)));
                    } else if (outcome.errorMessage() != null) {
                        sink.next(toJson(Map.of("type", "error", "message", outcome.errorMessage())));
                    } else {
                        sink.next(toJson(Map.of("type", "result", "proposedSource", outcome.proposedSource())));
                    }
                    sink.next("[DONE]");
                    sink.complete();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ─── The agent itself ───────────────────────────────────────────────────

    private ProposeChangeOutcome runAgent(String programId, String question, String answer, Consumer<String> onStep) {
        List<String> steps = new ArrayList<>();
        Consumer<String> step = message -> {
            steps.add(message);
            onStep.accept(message);
        };

        Optional<ProgramSource> sourceOpt = vectorSearch.fetchFullSource(programId);
        if (sourceOpt.isEmpty()) {
            return ProposeChangeOutcome.sourceNotFound();
        }
        ProgramSource source = sourceOpt.get();
        int totalLines = (int) source.content().lines().count();

        if (totalLines <= maxSectionLines) {
            step.accept("Reading " + programId + " (" + totalLines + " lines) — small enough to generate directly.");
            String modified = generateWhole(question, answer, programId, source.content());
            if (modified == null || modified.isBlank()) {
                return ProposeChangeOutcome.error(steps, "The model returned an empty response. Try again.");
            }
            modified = preserveLeadingWhitespace(source.content(), modified);
            // Same false-success risk as the section-scoped path below: a question
            // that's really about impact ("which files need to change?") rather than a
            // concrete edit to THIS file's own code can make the model correctly hand
            // back the file unchanged. Splicing that in as a "successful" empty diff
            // reads as a bug, not an honest answer — see the identical check in the
            // large-file loop for the full rationale.
            if (modified.equals(source.content())) {
                return ProposeChangeOutcome.error(steps, programId + " doesn't actually need its code changed for "
                        + "this recommendation — it looks like it concerns a runtime value, configuration, or a "
                        + "different file rather than this program's own logic.");
            }
            step.accept("Change generated.");
            return ProposeChangeOutcome.success(steps, modified);
        }

        step.accept("Reading " + programId + " (" + totalLines + " lines) — too large for one pass, "
                + "locating the specific section(s) that need to change instead.");

        List<ChunkResult> chunks = vectorSearch.fetchChunksForProgram(programId);
        if (chunks.isEmpty()) {
            return ProposeChangeOutcome.error(steps, "This file has no individually-tracked sections to target a change at.");
        }

        Map<String, ChunkResult> byChunkId = new LinkedHashMap<>();
        for (ChunkResult c : chunks) {
            if (c.chunkId() != null) byChunkId.put(c.chunkId(), c);
        }

        step.accept("Scanning " + chunks.size() + " sections of " + programId + "…");
        List<String> locatedIds = locateRelevantSections(question, answer, programId, chunks);
        List<ChunkResult> selected = new ArrayList<>();
        for (String id : locatedIds) {
            ChunkResult c = byChunkId.get(id);
            if (c != null && selected.size() < MAX_SECTIONS_PER_CHANGE) {
                selected.add(c);
            }
        }

        if (selected.isEmpty()) {
            step.accept("Couldn't confidently identify which section applies.");
            return ProposeChangeOutcome.error(steps, "Couldn't confidently identify which section of this "
                    + totalLines + "-line file needs to change. Try mentioning a specific paragraph or "
                    + "section name in your question.");
        }

        step.accept("Found " + selected.size() + " relevant section" + (selected.size() == 1 ? "" : "s") + ": "
                + String.join(", ", selected.stream().map(c -> sectionLabel(c)).toList()) + ".");

        String result = source.content();
        List<String> applied = new ArrayList<>();
        boolean anyNoChangeNeeded = false;
        for (ChunkResult sel : selected) {
            int sectionLines = (int) sel.content().lines().count();
            if (sectionLines > maxSectionLines) {
                step.accept("Skipping " + sectionLabel(sel) + " — " + sectionLines + " lines is unexpectedly "
                        + "large for a single section, likely a raw/unanalyzed region rather than a real "
                        + "paragraph; not safe to regenerate in one pass.");
                continue;
            }
            step.accept("Generating the change for " + sectionLabel(sel) + "…");
            String modifiedSection = generateSection(question, answer, programId, sel);
            if (modifiedSection == null || modifiedSection.isBlank()) {
                step.accept("Skipping " + sectionLabel(sel) + " — the model returned an empty response.");
                continue;
            }
            modifiedSection = preserveLeadingWhitespace(sel.content(), modifiedSection);
            // The model sometimes (correctly) concludes a located section's own code
            // doesn't need to change — e.g. the recommendation is about a runtime/input
            // value flowing through unchanged logic, not the logic itself. Splicing in an
            // identical copy would produce a "successful" result with an empty diff, which
            // reads as a bug ("nothing was highlighted") rather than the honest answer.
            if (modifiedSection.equals(sel.content())) {
                anyNoChangeNeeded = true;
                step.accept(sectionLabel(sel) + " doesn't actually need its code changed for this recommendation.");
                continue;
            }
            int idx = result.indexOf(sel.content());
            if (idx < 0) {
                step.accept("Skipping " + sectionLabel(sel) + " — couldn't safely locate its exact position "
                        + "in the full file to apply the change.");
                continue;
            }
            result = result.substring(0, idx) + modifiedSection + result.substring(idx + sel.content().length());
            applied.add(sectionLabel(sel));
            step.accept(sectionLabel(sel) + " updated.");
        }

        if (applied.isEmpty()) {
            String message = anyNoChangeNeeded
                    ? "The located section(s) don't actually need their code changed for this recommendation — "
                            + "it looks like it concerns a runtime value or configuration rather than the "
                            + "program's logic. Try asking about a specific paragraph if you expected a code-level change."
                    : "Found the relevant section(s) but couldn't safely apply the generated change(s) back into "
                            + "the full file. Try regenerating, or ask about a more specific paragraph.";
            return ProposeChangeOutcome.error(steps, message);
        }

        step.accept("Applied " + applied.size() + " change" + (applied.size() == 1 ? "" : "s")
                + " to the full " + totalLines + "-line file.");
        return ProposeChangeOutcome.success(steps, result);
    }

    private String sectionLabel(ChunkResult c) {
        String name = (c.sectionName() != null && !c.sectionName().isBlank()) ? c.sectionName() : c.chunkId();
        if (c.lineStart() != null && c.lineEnd() != null) {
            return name + " (lines " + c.lineStart() + "-" + c.lineEnd() + ")";
        }
        return name;
    }

    // ─── LLM calls ──────────────────────────────────────────────────────────

    private String generateWhole(String question, String answer, String programId, String currentSource) {
        var sample = metrics.startLlmCall();
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(WHOLE_FILE_SYSTEM_PROMPT),
                    new UserMessage(buildWholeFileUserMessage(programId, question, answer, currentSource))
            )));
            return stripCodeFences(response.getResult().getOutput().getText());
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_whole");
            return null;
        } finally {
            metrics.stopLlmCall(sample, "propose_change_whole");
        }
    }

    /** Returns the located chunk ids, validated only in the sense of being non-blank
     * strings — the caller cross-checks them against real chunk ids before using them,
     * so a hallucinated id is simply dropped rather than trusted. Never throws: any
     * failure here (bad JSON, LLM error) degrades to "found nothing", same as the
     * extraction methods elsewhere in this app. */
    private List<String> locateRelevantSections(String question, String answer, String programId,
                                                  List<ChunkResult> chunks) {
        var sample = metrics.startLlmCall();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Question: ").append(question).append("\n\n");
            sb.append("Recommended change (from the assistant's answer):\n").append(answer).append("\n\n");
            sb.append("Sections of ").append(programId).append(":\n");
            int i = 1;
            for (ChunkResult c : chunks) {
                sb.append(i++).append(". id: ").append(c.chunkId())
                        .append(" | name: ").append(c.sectionName())
                        .append(" | lines ").append(c.lineStart()).append('-').append(c.lineEnd())
                        .append(" | purpose: ").append(nullToEmpty(c.sectionPurpose()))
                        .append('\n');
            }

            var response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(LOCATE_SECTION_SYSTEM_PROMPT), new UserMessage(sb.toString())),
                    OpenAiChatOptions.builder().responseFormat(JSON_OBJECT_FORMAT).build()));

            var root = objectMapper.readTree(response.getResult().getOutput().getText());
            List<String> ids = new ArrayList<>();
            for (var idNode : root.path("chunkIds")) {
                String id = idNode.asText(null);
                if (id != null && !id.isBlank()) ids.add(id);
            }
            return ids;
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_locate");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "propose_change_locate");
        }
    }

    private String generateSection(String question, String answer, String programId, ChunkResult section) {
        var sample = metrics.startLlmCall();
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(GENERATE_SECTION_SYSTEM_PROMPT),
                    new UserMessage(buildSectionUserMessage(programId, question, answer, section))
            )));
            return stripCodeFences(response.getResult().getOutput().getText());
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_section");
            return null;
        } finally {
            metrics.stopLlmCall(sample, "propose_change_section");
        }
    }

    // ─── Prompt building / small helpers ───────────────────────────────────

    private String buildWholeFileUserMessage(String programId, String question, String answer, String currentSource) {
        return """
                Original question: %s

                Recommended change (from the assistant's answer):
                %s

                ORIGINAL SOURCE of %s:
                %s
                """.formatted(question, answer, programId, currentSource);
    }

    private String buildSectionUserMessage(String programId, String question, String answer, ChunkResult section) {
        return """
                Original question: %s

                Recommended change (from the assistant's answer):
                %s

                This is section "%s" (lines %s-%s) of %s — NOT the whole program, just this one section:
                %s
                """.formatted(question, answer, section.sectionName(), section.lineStart(), section.lineEnd(),
                programId, section.content());
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** LLMs reliably strip the leading whitespace of just the FIRST line of a
     * returned block, even when told to preserve columns exactly — observed in
     * practice for a COBOL section header that must start in Area A (column 8).
     * Since the model demonstrably preserves indentation on every other line,
     * restoring only the first line's original leading spaces (when the model's
     * version lost them) is a safe, targeted fix rather than a general
     * indentation-guessing heuristic. */
    private String preserveLeadingWhitespace(String original, String generated) {
        if (original == null || generated == null || generated.isEmpty()) return generated;
        String[] origLines = original.split("\n", -1);
        String[] genLines = generated.split("\n", -1);
        if (origLines.length == 0 || genLines.length == 0) return generated;
        String origFirst = origLines[0];
        String genFirst = genLines[0];
        int indent = 0;
        while (indent < origFirst.length() && origFirst.charAt(indent) == ' ') indent++;
        String origIndent = origFirst.substring(0, indent);
        if (!origIndent.isEmpty() && !genFirst.startsWith(origIndent)) {
            genLines[0] = origIndent + genFirst.stripLeading();
            return String.join("\n", genLines);
        }
        return generated;
    }

    private String stripCodeFences(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
