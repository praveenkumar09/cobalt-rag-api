package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import com.cobalt.rag.model.ProgramSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backs the "current vs. proposed" code-compare view opened from an Impact
 * Analysis entry. The current side is always the real ingested source (never
 * fabricated — see {@link VectorSearchService#fetchFullSource}); the proposed
 * side is a real LLM generation grounded in that exact source plus the actual
 * chat answer that recommended the change, not a canned diff.
 *
 * <p>Every file — regardless of size — goes through the same agentic
 * locate → generate → splice pipeline: (1) locate — a cheap LLM call scans
 * the program's section names and ingestor-written purpose descriptions (not
 * their content — this scales with section COUNT, never file size) and picks
 * which section(s) actually need to change; (2) generate — a small, scoped
 * LLM call produces just the modified version of each selected section;
 * (3) splice — those sections are substituted back into the real full
 * source, so the returned proposal is still the complete file, byte-identical
 * everywhere except what genuinely changed. No single LLM call in this path
 * is ever close to whole-file size, and — critically — nothing outside the
 * selected section(s) ever passes through the model at all, so it can't drop
 * or reword an unrelated line by mistake. A file with NO section-level chunk
 * data falls back to a whole-file rewrite (higher risk — the model has to
 * faithfully reproduce every unrelated line itself — used only as a last
 * resort, and only when the file is small enough to bound that risk).
 *
 * <p>Either path's result then goes through a real compile check (see
 * {@link CobolCompileService}) before being returned as "success" — an LLM
 * claiming its own COBOL is valid isn't good enough. A genuinely new compile
 * error (not one the original file already had) triggers up to a couple of
 * scoped auto-fix attempts, each recompiled in turn; only a fix the compiler
 * itself accepts is ever returned.
 *
 * <p>Every stage narrates itself via the {@code onStep} callback (surfaced to
 * the UI as "thinking" events over SSE — see {@link #proposeChangeStream}),
 * so the user watches the same locate → generate → splice → compile
 * reasoning a human would do by hand.
 */
@Service
public class CodeChangeService {

    // Every LLM-call helper below catches Exception broadly (an LLM/network
    // failure shouldn't crash the whole propose-change run) and previously
    // just returned null on failure with NO log line — a caller three steps
    // up would see a fix strategy silently skipped with zero trail of why.
    // Observed live: attemptDeclareFix kept losing to attemptUsageWindowFix on
    // legitimate "not defined" errors and there was no way to tell whether
    // that was a structural bailout (no WORKING-STORAGE found, etc.) or a
    // swallowed exception (bad JSON, timeout) without adding this logger.
    private static final Logger log = LoggerFactory.getLogger(CodeChangeService.class);

    // Prepended to every prompt that outputs real COBOL source. Discovered from an
    // actual failure: the model declared WS-DISCOUNT-RATE and WS-FINAL-PREMIUM on
    // lines long enough to run past column 72; GnuCOBOL doesn't wrap or error on
    // that, it just silently drops everything past column 72, so what actually
    // reached the compiler was the truncated "WS-D" / "WS-FINAL-PR" — corrupting
    // the rest of WORKING-STORAGE's parse and making even unrelated, pre-existing
    // fields read back as "not defined". No prompt here mentioned the column limit
    // at all before that. None of them may omit this again.
    private static final String COLUMN_RULE = """
            CRITICAL — fixed-format COBOL column rule: content only exists in columns \
            8-72 (Area A/B). A real compiler does NOT wrap or error when a line runs \
            past column 72 — it silently DROPS everything beyond that column, which can \
            cut an identifier off mid-name and corrupt the parse of everything after it. \
            Before finalizing any line, mentally count its length. If a field name + \
            PICTURE clause (+ VALUE clause) or a long MOVE/COMPUTE/IF would run past \
            column 72, either split it with a continuation (end the line early, put a \
            hyphen in column 7 of the next line, continue the literal there) or keep it \
            short — e.g. a more compact field name, or drop a non-essential VALUE clause \
            — rather than crowding everything onto one line. Never let a line exceed 72 \
            characters.
            """;

    private static final String WHOLE_FILE_SYSTEM_PROMPT = COLUMN_RULE + """
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

    private static final String GENERATE_SECTION_SYSTEM_PROMPT = COLUMN_RULE + """
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

    private static final String FIX_COMPILE_ERROR_SYSTEM_PROMPT = COLUMN_RULE + """
            You are a COBOL code-repair agent. You are given a short EXCERPT of a larger \
            COBOL program's real source (not the whole file) — the lines around where a \
            real compiler reported error(s) — those exact compiler error message(s), and, \
            if this isn't the first attempt, the PREVIOUS excerpt(s) you already tried and \
            the error each one still produced.

            Diagnose the ROOT CAUSE before fixing — don't just reword the same lines. Common \
            causes in fixed-format COBOL: a statement running past column 72 without a \
            continuation character in column 7 of the next line (the compiler then reads the \
            rest as a new, truncated/garbage token — e.g. an identifier getting cut off \
            mid-name); a missing period ending a statement/paragraph; an unmatched scope \
            terminator (END-IF/END-PERFORM/etc.). If a previous attempt is shown and it \
            produced the SAME or a near-identical error, that attempt's actual root-cause \
            diagnosis was wrong — try a genuinely different fix, not a cosmetic variation of \
            the same one.

            This excerpt is from PROCEDURE DIVISION logic, not WORKING-STORAGE — if a field \
            is genuinely undeclared, that is handled by a SEPARATE step elsewhere in the \
            file; do NOT add a data-item declaration (an 01/05-level PIC clause item) here, \
            it is not valid inside procedure logic and will itself cause a syntax error. \
            Only fix the CONTROL FLOW / STATEMENT SYNTAX in this excerpt (periods, scope \
            terminators, valid targets for MOVE/COMPUTE, PERFORM structure, etc.).

            Respond with ONLY a JSON object with exactly two keys:
            "explanation": one short sentence naming the root cause and what you changed.
            "fixedExcerpt": the FULL corrected excerpt as a single string (use \\n for newlines) — \
            preserve every unrelated line exactly as-is (same columns/spacing/formatting), \
            fix only what the diagnosis calls for, and keep it raw COBOL only: no markdown \
            fences, no commentary inside this field.
            """;

    private static final String DECLARE_FIELD_SYSTEM_PROMPT = COLUMN_RULE + """
            You are a COBOL code-repair agent. A compiler reported that certain \
            identifier(s) are not defined anywhere. That means they're missing their \
            WORKING-STORAGE declaration — no amount of editing the line that USES them \
            can fix this; a new field declaration has to be ADDED elsewhere in the file.

            You are given: the identifier name(s), an excerpt showing how they're USED \
            (read-only, for inferring what kind of data each one holds — a rate, a count, \
            a money amount, a flag), and an excerpt from the END of the WORKING-STORAGE \
            SECTION (immediately before PROCEDURE DIVISION) — this is what you edit. If a \
            previous attempt is shown and it produced a DIFFERENT error (e.g. "not numeric" \
            or "is ambiguous") rather than the same "not defined" error, that means the \
            declaration WAS added but with the wrong TYPE or a name that collides with \
            something — fix that specific problem this time, don't just repeat the same PIC \
            clause. Get the type right the first time: any identifier used with \
            COMPUTE/MULTIPLY/SUBTRACT/ADD/DIVIDE, or as a PERFORM VARYING control variable, \
            MUST get a NUMERIC PIC clause (9s, optionally with V for a decimal point — e.g. \
            PIC 9(7)V99), never an alphanumeric PIC X. If a previous attempt is shown and it \
            produced the SAME "not defined" error again, that means the declaration didn't \
            actually get added to WORKING-STORAGE at all (e.g. it was added inline near the \
            usage instead, which isn't valid there); make sure this time the declaration is \
            a genuine 01-level (or 05-level under an existing group) item appended to the \
            WORKING-STORAGE excerpt itself.

            The WORKING-STORAGE excerpt is shown for context and style only — how existing \
            fields there are named and typed — never reproduce it back. Only the brand-new \
            declaration line(s) you write get inserted (by code, not by you) right before \
            PROCEDURE DIVISION, so any existing line you happened to echo back slightly \
            reformatted would corrupt real, unrelated code; asking for new lines only makes \
            that class of mistake structurally impossible.

            Respond with ONLY a JSON object with exactly two keys:
            "explanation": one short sentence naming which field(s) you declared and the \
            PICTURE clause you chose.
            "newDeclarations": ONLY the brand-new declaration line(s) — nothing from the \
            excerpt, no surrounding context. One well-formed, INDEPENDENT 01-level item per \
            missing identifier (never a 05-level item nested under an existing group — it will \
            be inserted standalone, not inside any group), each ending in a period, with a PIC \
            clause (and VALUE clause if appropriate) consistent with how it's used. Raw COBOL \
            only, no leading indentation (applied automatically), no markdown fences, no \
            commentary inside this field.
            """;

    // Deliberately NOT prefixed with COLUMN_RULE — this prompt never outputs
    // COBOL, it translates a change already grounded in real (compile-verified)
    // before/after source into plain business language, backing the "Business
    // Impact Summary" shown in Business view in place of a raw code diff — see
    // CodeCompareModal.tsx.
    private static final String BUSINESS_IMPACT_SUMMARY_SYSTEM_PROMPT = """
            You are explaining a code change to a NON-TECHNICAL business stakeholder — an \
            insurance business analyst reviewing a proposed change, not a programmer. You are \
            given the ORIGINAL QUESTION that prompted this change, the ANSWER already given to \
            it, and the exact BEFORE/AFTER of the real source code section(s) that were changed \
            to implement it.

            Write a short plain-English summary (2-4 sentences) covering:
            - What actually changes, described in terms of the policy/customer/claim, not the code.
            - Any eligibility or condition that applies — who or what triggers this change.
            - Whether anything else in the existing behavior is left alone (reassure the reader \
              this isn't a bigger change than it looks).

            Do not use COBOL syntax, field names (like WS-XXX or PMR-XXX), line numbers, or code \
            excerpts anywhere in your answer — write for someone who will never see the source \
            code. Output plain prose only: no markdown headers, no code fences, and bullet points \
            only if there are genuinely 2 or more distinct effects worth separating.
            """;

    // Deliberately NOT prefixed with COLUMN_RULE — never outputs COBOL. Backs
    // the opening "Thought" of a propose-change run. That used to be a static
    // Java string reciting this agent's own locate/generate/splice/compile
    // process (see git history) — which reads exactly like what it is, a
    // paraphrase of internal design/prompt text, not reasoning about THIS
    // request. The mechanical process is already visible later as real
    // Action/Observation steps, so this prompt is told NOT to repeat it and
    // instead reason about what the specific request actually needs.
    private static final String PLAN_THOUGHT_SYSTEM_PROMPT = """
            You are a COBOL maintenance engineer about to make a real, specific code change. \
            You're given the original question that was asked and the answer already given \
            recommending a change. Before starting, think out loud for 1-2 sentences about what \
            THIS particular request actually requires — e.g. what kind of logic is likely \
            involved (a validation check, an error/status code, a calculation, a new condition), \
            and what could make it tricky.

            Do NOT describe your own working process (locating a section, generating an edit, \
            compiling, fixing errors) — that's generic scaffolding the user already sees later as \
            separate steps, not what's being asked for here. Focus only on the substance of THIS \
            change.

            Respond with ONLY that 1-2 sentences of reasoning, in first person, as plain prose — \
            no preamble, no quotes, no markdown, no restating the question verbatim.
            """;

    private static final ResponseFormat JSON_OBJECT_FORMAT =
            ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();

    private static final Pattern DIAGNOSTIC_LINE_NUMBER = Pattern.compile("^[^:]+:(\\d+):");
    private static final Pattern UNDEFINED_IDENTIFIER = Pattern.compile("'([A-Z0-9-]+)' is not defined");
    private static final Pattern WORKING_STORAGE_HEADER =
            Pattern.compile("(?im)^\\s*WORKING-STORAGE\\s+SECTION\\s*\\.\\s*$");
    private static final Pattern PROCEDURE_DIVISION_HEADER =
            Pattern.compile("(?im)^\\s*PROCEDURE\\s+DIVISION\\b.*\\.\\s*$");
    // A WORKING-STORAGE-style data item — two-digit level number, a name, a PIC
    // clause — matched only when it explicitly contains "PIC", so this never
    // false-positives on a PROCEDURE DIVISION paragraph/section name (which are
    // a single hyphenated token ending in a period, never followed by PIC).
    private static final Pattern MISPLACED_DATA_ITEM =
            Pattern.compile("(?i)^\\s*\\d{2}\\s+[A-Z0-9-]+\\s+PIC\\b");
    // A COBOL paragraph or section header: a hyphenated name, optionally
    // followed by "SECTION", and NOTHING else on the line except the
    // terminating period — deliberately excludes any line with real statement
    // content after the period (e.g. "MOVE X TO Y.") so this only matches true
    // paragraph/section boundaries, used to split a too-coarse chunk (see
    // COARSE_SECTION_LINE_THRESHOLD) into real paragraphs on the fly.
    private static final Pattern PARAGRAPH_HEADER =
            Pattern.compile("(?im)^\\s{0,11}([A-Z0-9][A-Z0-9-]*)(?:\\s+SECTION)?\\.\\s*$");

    // How many times to let the agent try fixing a compiler error before giving
    // up honestly — bounds cost/latency on a change that just isn't fixable
    // with a small, scoped patch.
    private static final int MAX_FIX_ATTEMPTS = 5;
    // Lines of real source kept on each side of an error line when building the
    // fix excerpt — enough for the model to see the surrounding COBOL structure
    // (the enclosing paragraph, a PERFORM/IF block) without sending anywhere
    // near the whole file, regardless of file size.
    private static final int FIX_CONTEXT_LINES = 8;
    // Caps the excerpt size when new errors are scattered far apart in the
    // file — fixes around the FIRST error rather than spanning the whole gap.
    private static final int MAX_FIX_WINDOW_LINES = 120;
    // Lines kept from the end of WORKING-STORAGE SECTION when appending a
    // missing field declaration there — enough to see the last group's
    // structure (so a new item can match its style) without sending the whole
    // (potentially large) WORKING-STORAGE SECTION.
    private static final int DECLARE_CONTEXT_LINES = 15;

    // Two uses, both bounding a single LLM call's input to something well
    // short of whole-file size: (1) a section this big is almost always an
    // ingestion coverage-gap raw-content fallback, not a real paragraph — see
    // VectorSearchService's CHUNKS_FOR_PROGRAM_SQL comment — so it's skipped
    // rather than risked; (2) the whole-file-rewrite fallback (only used when
    // a program has no section-level chunk data at all) is only attempted
    // below this size, same reasoning as the old whole-file fast path.
    @Value("${cobalt.rag.propose-change.max-source-lines:3000}")
    private int maxSectionLines;

    // How many of the located section(s) to actually attempt generating a
    // change for, at most — bounds both cost and how much a single "propose
    // change" click can touch, even if the locate step is over-confident.
    private static final int MAX_SECTIONS_PER_CHANGE = 3;

    // A located "section" longer than this is treated as coarse ingestion (a
    // whole DIVISION as one chunk, not a real single paragraph — see the
    // on-the-fly paragraph splitting this gates) rather than a genuinely large
    // paragraph. Picked well above any real paragraph observed in practice
    // (PREMINQ's largest real section was ~20 lines) but well below a whole
    // PROCEDURE DIVISION (100+ lines even in a small program).
    private static final int COARSE_SECTION_LINE_THRESHOLD = 50;

    private final VectorSearchService vectorSearch;
    private final ChatModel chatModel;
    private final RagMetrics metrics;
    private final CobolCompileService cobolCompileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeChangeService(VectorSearchService vectorSearch, ChatModel chatModel, RagMetrics metrics,
                              CobolCompileService cobolCompileService) {
        this.vectorSearch = vectorSearch;
        this.chatModel = chatModel;
        this.metrics = metrics;
        this.cobolCompileService = cobolCompileService;
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
     * {@code businessSummary} is non-null only on success, and only when one
     * could be generated — a plain-English translation of what the diff
     * actually does, grounded in the exact before/after of the section(s) that
     * changed (see generateBusinessSummary) so a non-technical stakeholder can
     * review a proposed change without reading COBOL.
     */
    public record ProposeChangeOutcome(List<String> steps, String proposedSource, String errorMessage,
                                         boolean notFound, String businessSummary) {
        static ProposeChangeOutcome sourceNotFound() {
            return new ProposeChangeOutcome(List.of(), null, null, true, null);
        }
        static ProposeChangeOutcome error(List<String> steps, String message) {
            return new ProposeChangeOutcome(steps, null, message, false, null);
        }
        static ProposeChangeOutcome success(List<String> steps, String proposedSource) {
            return new ProposeChangeOutcome(steps, proposedSource, null, false, null);
        }
        static ProposeChangeOutcome successWithSummary(ProposeChangeOutcome base, String businessSummary) {
            return new ProposeChangeOutcome(base.steps(), base.proposedSource(), null, false, businessSummary);
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
                        Map<String, Object> resultEvent = new LinkedHashMap<>();
                        resultEvent.put("type", "result");
                        resultEvent.put("proposedSource", outcome.proposedSource());
                        if (outcome.businessSummary() != null) {
                            resultEvent.put("businessSummary", outcome.businessSummary());
                        }
                        sink.next(toJson(resultEvent));
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

        // The ReAct-style plan, stated up front before any action — see thought()/
        // action()/observation() below for the pattern followed for the rest of
        // this run: reason about what to do next, do it, observe the real result,
        // repeat. This opening thought is a real LLM call grounded in the actual
        // question/answer (see PLAN_THOUGHT_SYSTEM_PROMPT) rather than a fixed
        // description of this agent's own locate/generate/splice/compile process
        // — that process is already visible below as its own Action/Observation
        // steps, so restating it here read as prompt text rather than reasoning.
        String planThought = generatePlanThought(question, answer, programId);
        thought(step, planThought != null && !planThought.isBlank()
                ? planThought
                : "Looking at " + programId + " (" + totalLines + " lines) to work out what this change needs.");

        List<ChunkResult> chunks = vectorSearch.fetchChunksForProgram(programId);
        if (chunks.isEmpty()) {
            // No section-level metadata to scope a change to — the only remaining
            // option is a whole-file rewrite, which is exactly the higher-risk path
            // (the model has to faithfully reproduce every unrelated line itself,
            // and has been observed dropping one on occasion) that locate+generate+
            // splice exists to avoid. Used ONLY as a last resort here, and only when
            // the file is still small enough that regenerating it whole is at least
            // bounded — never for a large file with no chunks, which would silently
            // reintroduce the large-file cost problem this agent exists to solve.
            if (totalLines > maxSectionLines) {
                return ProposeChangeOutcome.error(steps, "This file has no individually-tracked sections and is "
                        + totalLines + " lines — too large to safely regenerate as a whole. Try asking about a "
                        + "specific paragraph, or re-ingest this program so it gets section-level data.");
            }
            observation(step, programId + " has no individually-tracked sections available.");
            action(step, "Falling back to a whole-file rewrite (higher risk of an incidental change elsewhere — "
                    + "used only because there's no section data available to scope a smaller edit to).");
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
            observation(step, "Change generated.");
            ProposeChangeOutcome wholeFileOutcome =
                    verifyCompilation(programId, source.content(), modified, question, answer, step, steps);
            SectionChange change = extractChangedSpan(programId, source.content(), modified);
            return attachBusinessSummaryIfSuccess(wholeFileOutcome, question, answer,
                    change != null ? List.of(change) : List.of(), step);
        }

        action(step, "Scanning " + chunks.size() + " sections of " + programId + " for the one(s) that match.");
        Map<String, ChunkResult> byChunkId = new LinkedHashMap<>();
        for (ChunkResult c : chunks) {
            if (c.chunkId() != null) byChunkId.put(c.chunkId(), c);
        }

        List<String> locatedIds = locateRelevantSections(question, answer, programId, chunks);
        List<ChunkResult> selected = new ArrayList<>();
        for (String id : locatedIds) {
            ChunkResult c = byChunkId.get(id);
            if (c != null && selected.size() < MAX_SECTIONS_PER_CHANGE) {
                selected.add(c);
            }
        }

        if (selected.isEmpty()) {
            observation(step, "Couldn't confidently identify which section applies.");
            return ProposeChangeOutcome.error(steps, "Couldn't confidently identify which section of this "
                    + totalLines + "-line file needs to change. Try mentioning a specific paragraph or "
                    + "section name in your question.");
        }

        observation(step, "Found " + selected.size() + " relevant section" + (selected.size() == 1 ? "" : "s") + ": "
                + String.join(", ", selected.stream().map(c -> sectionLabel(c)).toList()) + ".");

        String result = source.content();
        List<String> applied = new ArrayList<>();
        List<SectionChange> sectionChanges = new ArrayList<>();
        boolean anyNoChangeNeeded = false;
        for (ChunkResult sel : selected) {
            int sectionLines = (int) sel.content().lines().count();
            if (sectionLines > maxSectionLines) {
                observation(step, "Skipping " + sectionLabel(sel) + " — " + sectionLines + " lines is unexpectedly "
                        + "large for a single section, likely a raw/unanalyzed region rather than a real "
                        + "paragraph; not safe to regenerate in one pass.");
                continue;
            }

            // A "section" this big is almost always coarse ingestion (a whole
            // DIVISION treated as one chunk because the program's paragraphs never
            // got split out individually — observed on CLMPRC, whose PROCEDURE
            // DIVISION is ONE 178-line chunk) rather than a genuinely large real
            // paragraph. Handing the whole thing to generateSection asks the model
            // to confidently edit something the size of a small file — it usually
            // (correctly!) can't localize where within it to make a small change,
            // and honestly reports "doesn't need to change", which is a false
            // negative caused by chunk coarseness, not by the recommendation. Fix
            // it by re-locating within it at paragraph granularity, computed on the
            // fly — no re-ingestion needed, and this protects any program with
            // similarly coarse chunks, not just this one.
            ChunkResult target = sel;
            if (sectionLines > COARSE_SECTION_LINE_THRESHOLD) {
                List<SubSection> paragraphs = splitIntoParagraphs(sel.content(), sel.lineStart() != null ? sel.lineStart() : 1);
                if (paragraphs.size() >= 2) {
                    observation(step, sectionLabel(sel) + " is " + sectionLines + " lines — too coarse to treat as "
                            + "one unit (likely never split into paragraphs during ingestion); looking for the "
                            + "specific paragraph within it instead.");
                    List<ChunkResult> synthetic = new ArrayList<>();
                    for (int i = 0; i < paragraphs.size(); i++) {
                        SubSection p = paragraphs.get(i);
                        synthetic.add(new ChunkResult(sel.chunkId() + "_SUB_" + i, null, programId, null, null,
                                p.name(), null, p.content(), null, p.lineStart(), p.lineEnd(), 0.0, null));
                    }
                    List<String> subLocated = locateRelevantSections(question, answer, programId, synthetic);
                    ChunkResult refined = subLocated.isEmpty() ? null : synthetic.stream()
                            .filter(c -> c.chunkId().equals(subLocated.get(0)))
                            .findFirst().orElse(null);
                    if (refined != null) {
                        observation(step, "Narrowed to " + sectionLabel(refined) + ".");
                        target = refined;
                    } else {
                        observation(step, "Couldn't confidently narrow within " + sectionLabel(sel)
                                + " — proceeding with the whole thing.");
                    }
                }
            }

            action(step, "Generating a scoped edit for " + sectionLabel(target) + ".");
            String modifiedSection = generateSection(question, answer, programId, target);
            if (modifiedSection == null || modifiedSection.isBlank()) {
                observation(step, "Skipping " + sectionLabel(target) + " — the model returned an empty response.");
                continue;
            }
            modifiedSection = preserveLeadingWhitespace(target.content(), modifiedSection);
            // The model sometimes (correctly) concludes a located section's own code
            // doesn't need to change — e.g. the recommendation is about a runtime/input
            // value flowing through unchanged logic, not the logic itself. Splicing in an
            // identical copy would produce a "successful" result with an empty diff, which
            // reads as a bug ("nothing was highlighted") rather than the honest answer.
            if (modifiedSection.equals(target.content())) {
                anyNoChangeNeeded = true;
                observation(step, sectionLabel(target) + " doesn't actually need its code changed for this recommendation.");
                continue;
            }
            int idx = result.indexOf(target.content());
            if (idx < 0) {
                observation(step, "Skipping " + sectionLabel(target) + " — couldn't safely locate its exact position "
                        + "in the full file to apply the change.");
                continue;
            }
            sectionChanges.add(new SectionChange(sectionLabel(target), target.content(), modifiedSection));
            result = result.substring(0, idx) + modifiedSection + result.substring(idx + target.content().length());
            applied.add(sectionLabel(target));
            observation(step, sectionLabel(target) + " updated.");
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

        action(step, "Splicing " + applied.size() + " change" + (applied.size() == 1 ? "" : "s")
                + " into the full " + totalLines + "-line file (everything else stays byte-identical).");
        ProposeChangeOutcome outcome = verifyCompilation(programId, source.content(), result, question, answer, step, steps);
        return attachBusinessSummaryIfSuccess(outcome, question, answer, sectionChanges, step);
    }

    /** One section's before/after text, kept from the point it was actually
     * generated/spliced — captured BEFORE any compile-fix attempts touch the
     * file further, since those are technical corrections (a missing
     * declaration, a relocated data item) that have no business meaning and
     * would only muddy a plain-English summary. */
    private record SectionChange(String label, String before, String after) {
    }

    /** Only attaches a summary on a genuine success (a real proposedSource) —
     * never for an error/not-found outcome, and never when nothing actually
     * changed (the whole-file fallback's before/after came out identical, or
     * — defensively — an empty change list). A failed summary generation still
     * returns the outcome as-is: the technical diff remains available even
     * without a business-language translation of it. */
    private ProposeChangeOutcome attachBusinessSummaryIfSuccess(ProposeChangeOutcome outcome, String question,
                                                                   String answer, List<SectionChange> changes,
                                                                   Consumer<String> step) {
        if (outcome.proposedSource() == null || changes.isEmpty()) return outcome;
        action(step, "Summarizing what this change means in plain business terms.");
        String summary = generateBusinessSummary(question, answer, changes);
        if (summary == null || summary.isBlank()) {
            observation(step, "Couldn't generate a business summary — the technical diff is still available.");
            return outcome;
        }
        observation(step, "Business summary ready.");
        return ProposeChangeOutcome.successWithSummary(outcome, summary);
    }

    private String generateBusinessSummary(String question, String answer, List<SectionChange> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original question: ").append(question).append("\n\n");
        sb.append("Recommended change (from the assistant's answer):\n").append(answer).append("\n\n");
        for (SectionChange c : changes) {
            sb.append("--- ").append(c.label()).append(" — BEFORE ---\n").append(c.before()).append("\n\n");
            sb.append("--- ").append(c.label()).append(" — AFTER ---\n").append(c.after()).append("\n\n");
        }

        var sample = metrics.startLlmCall();
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(BUSINESS_IMPACT_SUMMARY_SYSTEM_PROMPT), new UserMessage(sb.toString()))));
            String text = response.getResult().getOutput().getText();
            return text != null ? text.strip() : null;
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_business_summary");
            log.warn("propose_change_business_summary failed: {}", e.toString());
            return null;
        } finally {
            metrics.stopLlmCall(sample, "propose_change_business_summary");
        }
    }

    /** Genuine, request-specific reasoning for the opening "Thought" of a
     * propose-change run — see {@link #PLAN_THOUGHT_SYSTEM_PROMPT} for why this
     * replaced a static description of this agent's own process. Returns null
     * on any failure so the caller can fall back to a short factual line
     * instead of losing the step entirely. */
    private String generatePlanThought(String question, String answer, String programId) {
        var sample = metrics.startLlmCall();
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(PLAN_THOUGHT_SYSTEM_PROMPT),
                    new UserMessage("Program: " + programId + "\n\nOriginal question: " + question
                            + "\n\nRecommended change (from the assistant's answer):\n" + answer))));
            String text = response.getResult().getOutput().getText();
            return text != null ? text.strip() : null;
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_plan_thought");
            log.warn("propose_change_plan_thought failed: {}", e.toString());
            return null;
        } finally {
            metrics.stopLlmCall(sample, "propose_change_plan_thought");
        }
    }

    /** Lightweight fallback diff for the whole-file-rewrite path only (no
     * section-scoped before/after available there): finds the span where the
     * two versions first/last differ by line, expands a few lines of context
     * on each side. Not a general-purpose diff algorithm — doesn't need to be,
     * since the only caller already knows the two texts differ somewhere. */
    private SectionChange extractChangedSpan(String programId, String before, String after) {
        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);
        int minLen = Math.min(beforeLines.length, afterLines.length);
        int start = 0;
        while (start < minLen && beforeLines[start].equals(afterLines[start])) start++;
        int endB = beforeLines.length - 1;
        int endA = afterLines.length - 1;
        while (endB >= start && endA >= start && beforeLines[endB].equals(afterLines[endA])) {
            endB--;
            endA--;
        }
        if (endB < start && endA < start) return null;
        int ctxStart = Math.max(0, start - 3);
        int ctxEndB = Math.min(beforeLines.length - 1, endB + 3);
        int ctxEndA = Math.min(afterLines.length - 1, endA + 3);
        String beforeSpan = String.join("\n", java.util.Arrays.asList(beforeLines).subList(ctxStart, ctxEndB + 1));
        String afterSpan = String.join("\n", java.util.Arrays.asList(afterLines).subList(ctxStart, ctxEndA + 1));
        return new SectionChange(programId, beforeSpan, afterSpan);
    }

    // ReAct framing used throughout this agent: every step is reasoning about
    // what to do next (Thought), the LLM/tool call that does it (Action), or the
    // real, observed result of that call (Observation) — never decorative status
    // text. Kept as one-line helpers so the pattern is visibly consistent across
    // runAgent, verifyCompilation, and the fix loop rather than drifting.
    private void thought(Consumer<String> step, String text) {
        step.accept("Thought: " + text);
    }

    private void action(Consumer<String> step, String text) {
        step.accept("Action: " + text);
    }

    private void observation(Consumer<String> step, String text) {
        step.accept("Observation: " + text);
    }

    // ─── Compile verification + auto-fix ───────────────────────────────────

    /**
     * Runs the candidate through a real COBOL compiler (see
     * {@link CobolCompileService}'s Javadoc for why this compares against the
     * ORIGINAL file's own compiler output rather than expecting a clean
     * compile outright) and, if it introduced a genuinely new error, tries up
     * to {@link #MAX_FIX_ATTEMPTS} scoped repairs, recompiling after each. Only
     * returns success once the compiler agrees, or an honest error naming the
     * last compiler failure once attempts are exhausted.
     */
    private ProposeChangeOutcome verifyCompilation(String programId, String originalSource, String candidate,
                                                     String question, String answer, Consumer<String> step,
                                                     List<String> steps) {
        thought(step, "Before returning this as a real proposed change, I need to verify it actually compiles — "
                + "an LLM's own claim that its COBOL is valid isn't good enough on its own.");
        action(step, "Compiling the result with a real COBOL compiler, diffed against " + programId + "'s own "
                + "baseline compile so any pre-existing issue in this file isn't blamed on this edit.");
        try (CobolCompileService.Session session = cobolCompileService.startSession(programId, originalSource)) {
            CobolCompileService.CompileResult result = session.verify(candidate);

            if (result.infraError() != null) {
                observation(step, "Couldn't run the COBOL compiler (" + result.infraError() + ") — skipping verification.");
                return ProposeChangeOutcome.success(steps, candidate);
            }
            if (result.success()) {
                observation(step, "Compilation succeeded — code verified.");
                return ProposeChangeOutcome.success(steps, candidate);
            }

            observation(step, "Compilation failed with " + result.newErrors().size() + " new error"
                    + (result.newErrors().size() == 1 ? "" : "s") + ": " + result.newErrors().get(0));

            String current = candidate;
            CobolCompileService.CompileResult latest = result;
            // The agent's own memory of what it already tried and why that still
            // failed — passed into each new attempt so it reasons about its prior
            // (unsuccessful) diagnosis instead of blindly resending the same
            // context and risking the same answer again.
            List<FixAttemptRecord> history = new ArrayList<>();
            for (int attempt = 1; attempt <= MAX_FIX_ATTEMPTS; attempt++) {
                thought(step, "Attempt " + attempt + " of " + MAX_FIX_ATTEMPTS + ": " + (history.isEmpty()
                        ? "diagnosing the root cause of \"" + latest.newErrors().get(0) + "\"."
                        : "the previous attempt's fix didn't resolve it — still \"" + latest.newErrors().get(0)
                                + "\". Reasoning about a genuinely different angle rather than repeating it."));
                FixResult fix = attemptCompileFix(programId, originalSource, current, latest.newErrors(), question, answer, history);
                if (fix == null) {
                    observation(step, "Couldn't produce a usable fix for this error.");
                    break;
                }
                action(step, "[" + fix.strategy() + "] " + fix.explanation());
                CobolCompileService.CompileResult retry = session.verify(fix.fixedSource());
                if (retry.infraError() != null) {
                    observation(step, "Couldn't run the COBOL compiler (" + retry.infraError() + ") — skipping further verification.");
                    return ProposeChangeOutcome.success(steps, fix.fixedSource());
                }
                if (retry.success()) {
                    observation(step, "Recompiled — this fix works. Code verified.");
                    return ProposeChangeOutcome.success(steps, fix.fixedSource());
                }
                observation(step, "Recompiled — still failing: " + retry.newErrors().get(0) + annotateWithLine(fix.fixedSource(), retry.newErrors().get(0)));
                history.add(new FixAttemptRecord(fix.strategy(), fix.excerpt(), fix.explanation(), retry.newErrors()));
                current = fix.fixedSource();
                latest = retry;
            }

            return ProposeChangeOutcome.error(steps, "The generated change didn't pass COBOL compilation after "
                    + MAX_FIX_ATTEMPTS + " fix attempt(s). Last compiler error: " + latest.newErrors().get(0));
        }
    }

    /** One previous, unsuccessful fix attempt — kept so the next attempt can see
     * what was already tried against this same error and reason about why it
     * didn't work, instead of repeating it. */
    private record FixAttemptRecord(String strategy, String excerpt, String explanation, List<String> errorsAfter) {
    }

    private record FixResult(String strategy, String explanation, String excerpt, String fixedSource) {
    }

    private record Window(int start, int end, String text) {
    }

    /**
     * Picks a fix strategy based on WHAT the compiler is actually complaining
     * about, instead of always patching the same window around the error line —
     * a "not defined" error means the field's DECLARATION is missing, which
     * lives in WORKING-STORAGE, nowhere near where it's USED (the error line);
     * no amount of retrying a fix scoped to the usage site can add a
     * declaration somewhere else in the file. That mismatch — not the model
     * failing to reason — is why an earlier version of this loop could re-run
     * the same "I'll add the declaration" fix five times and see the identical
     * error every time: the excerpt it was allowed to touch never included
     * WORKING-STORAGE at all.
     */
    private FixResult attemptCompileFix(String programId, String originalSource, String source, List<String> errors,
                                          String question, String answer, List<FixAttemptRecord> history) {
        Set<String> undefinedNames = new LinkedHashSet<>();
        for (String err : errors) {
            Matcher um = UNDEFINED_IDENTIFIER.matcher(err);
            if (um.find()) undefinedNames.add(um.group(1));
        }
        if (!undefinedNames.isEmpty()) {
            FixResult declareFix = attemptDeclareFix(programId, originalSource, source, undefinedNames, errors, question, answer, history);
            if (declareFix != null) return declareFix;
            // Couldn't locate a clean WORKING-STORAGE/PROCEDURE DIVISION boundary to
            // insert into (e.g. an unusual file layout) — fall back to the usage-site
            // fix below; it at least gets a shot at a smaller, inline correction.
        }
        // Checked BEFORE the general usage-site fix: a data-item declaration
        // (level number + PIC clause) sitting inside PROCEDURE DIVISION logic is
        // never valid COBOL regardless of what else is wrong with the statement
        // around it — observed live: the generate step embedded "01 WS-SENIOR-
        // DISCOUNT PIC 9(5)V99 VALUE 0." inline where it's used instead of in
        // WORKING-STORAGE, and the usage-site fix misdiagnosed it as a missing
        // period FIVE TIMES IN A ROW with byte-identical wording each time — the
        // "don't repeat the same failed diagnosis" instruction in its own prompt
        // didn't save it, because the fix that was needed (relocate the line, not
        // punctuate it) was never one an LLM guess was likely to land on by
        // chance. This is exact-text pattern matching, not a guess: no LLM call,
        // just move the exact line to where it belongs.
        FixResult relocateFix = attemptRelocateMisplacedDeclaration(programId, source, errors);
        if (relocateFix != null) return relocateFix;
        return attemptUsageWindowFix(programId, source, errors, question, answer, history);
    }

    /** Detects a WORKING-STORAGE-style data item (a two-digit level number, a
     * name, and a PIC clause) sitting inside PROCEDURE DIVISION logic — never
     * valid COBOL — and relocates that EXACT line, verbatim, to just before
     * PROCEDURE DIVISION where declarations belong. Deterministic: the line's
     * exact text is already known, so there's nothing for a model to guess.
     * Returns null if the excerpt isn't in PROCEDURE DIVISION territory or
     * doesn't contain a line matching this shape. */
    private FixResult attemptRelocateMisplacedDeclaration(String programId, String source, List<String> errors) {
        String[] allLines = source.split("\n", -1);
        int pdLineIndex = -1;
        for (int i = 0; i < allLines.length; i++) {
            if (PROCEDURE_DIVISION_HEADER.matcher(allLines[i]).matches()) {
                pdLineIndex = i;
                break;
            }
        }
        if (pdLineIndex < 0) return null;

        Window window = computeErrorWindow(source, errors);
        if (window == null || window.start() - 1 < pdLineIndex) return null;

        String[] excerptLines = window.text().split("\n", -1);
        int misplacedIdx = -1;
        for (int i = 0; i < excerptLines.length; i++) {
            if (MISPLACED_DATA_ITEM.matcher(excerptLines[i]).find()) {
                misplacedIdx = i;
                break;
            }
        }
        if (misplacedIdx < 0) return null;

        String misplacedLine = excerptLines[misplacedIdx];
        List<String> withoutMisplaced = new ArrayList<>(java.util.Arrays.asList(excerptLines));
        withoutMisplaced.remove(misplacedIdx);
        String newExcerpt = String.join("\n", withoutMisplaced);

        int idx = source.indexOf(window.text());
        if (idx < 0) return null;
        String sourceWithoutMisplaced = source.substring(0, idx) + newExcerpt + source.substring(idx + window.text().length());

        String[] linesAfterRemoval = sourceWithoutMisplaced.split("\n", -1);
        int pdIndex2 = -1;
        for (int i = 0; i < linesAfterRemoval.length; i++) {
            if (PROCEDURE_DIVISION_HEADER.matcher(linesAfterRemoval[i]).matches()) {
                pdIndex2 = i;
                break;
            }
        }
        if (pdIndex2 < 0) return null;

        List<String> rebuilt = new ArrayList<>(linesAfterRemoval.length + 1);
        rebuilt.addAll(java.util.Arrays.asList(linesAfterRemoval).subList(0, pdIndex2));
        rebuilt.add(misplacedLine);
        rebuilt.addAll(java.util.Arrays.asList(linesAfterRemoval).subList(pdIndex2, linesAfterRemoval.length));
        String fixedSource = String.join("\n", rebuilt);

        String explanation = "Found \"" + misplacedLine.strip() + "\" declared inline inside procedure logic "
                + "(a data item isn't valid there, regardless of punctuation) — moved it to WORKING-STORAGE "
                + "where declarations belong.";
        return new FixResult("relocate misplaced declaration", explanation, window.text(), fixedSource);
    }

    /** Finds the tail of WORKING-STORAGE SECTION (just before PROCEDURE DIVISION)
     * and asks the model to APPEND declarations for the given undefined
     * identifiers there. If an identifier already exists verbatim in the
     * ORIGINAL (pre-edit) source, that almost always means the whole-file/section
     * generation step accidentally dropped it while rewriting — in that case the
     * model is shown exactly how it looked before and told to restore it, rather
     * than invent a fresh guess that can drift out of sync with how the field is
     * used elsewhere in the file (observed in practice: a re-invented WS-INPUT-FIELDS
     * group came out shaped differently from the original, and every other
     * reference to its child field then became "ambiguous" instead of undefined).
     * Returns null if the file doesn't have a clean WORKING-STORAGE/PROCEDURE
     * DIVISION boundary to work with. */
    private FixResult attemptDeclareFix(String programId, String originalSource, String source,
                                          Set<String> undefinedNames, List<String> errors, String question,
                                          String answer, List<FixAttemptRecord> history) {
        // Rebuilt via a line array + a single controlled String.join, not
        // substring/indexOf splicing — an earlier version relied on the model's
        // own returned text preserving the exact trailing blank line ahead of
        // PROCEDURE DIVISION, which LLMs routinely trim, silently gluing the
        // last declaration onto the SAME line as "PROCEDURE DIVISION." and
        // corrupting the parse. Explicit line boundaries can't go missing.
        String[] allLines = source.split("\n", -1);
        int pdLineIndex = -1;
        for (int i = 0; i < allLines.length; i++) {
            if (PROCEDURE_DIVISION_HEADER.matcher(allLines[i]).matches()) {
                pdLineIndex = i;
                break;
            }
        }
        if (pdLineIndex < 0) return null;
        boolean hasWorkingStorage = false;
        for (int i = 0; i < pdLineIndex; i++) {
            if (WORKING_STORAGE_HEADER.matcher(allLines[i]).matches()) {
                hasWorkingStorage = true;
                break;
            }
        }
        if (!hasWorkingStorage) return null;

        int windowLines = Math.min(pdLineIndex, DECLARE_CONTEXT_LINES);
        int startIdx = Math.max(0, pdLineIndex - windowLines);
        String declareExcerpt = String.join("\n", java.util.Arrays.asList(allLines).subList(startIdx, pdLineIndex));
        if (declareExcerpt.isBlank()) return null;

        Window usageWindow = computeErrorWindow(source, errors);
        String usageExcerpt = usageWindow != null ? usageWindow.text() : "(not available)";

        StringBuilder originalContext = new StringBuilder();
        for (String name : undefinedNames) {
            String found = findOriginalDeclarationContext(originalSource, name);
            if (found != null) {
                originalContext.append("--- ").append(name).append(" as it looked BEFORE this change ---\n")
                        .append(found).append("\n\n");
            }
        }

        var sample = metrics.startLlmCall();
        String explanation;
        String newDeclarations;
        try {
            var response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(DECLARE_FIELD_SYSTEM_PROMPT),
                            new UserMessage(buildDeclareUserMessage(programId, undefinedNames, errors, usageExcerpt,
                                    declareExcerpt, question, answer, history, originalContext.toString()))),
                    OpenAiChatOptions.builder().responseFormat(JSON_OBJECT_FORMAT).build()));
            var root = objectMapper.readTree(response.getResult().getOutput().getText());
            explanation = root.path("explanation").asText("Declared the missing field(s).");
            newDeclarations = root.path("newDeclarations").asText(null);
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_fix_declare");
            log.warn("propose_change_fix_declare failed: {}", e.toString());
            return null;
        } finally {
            metrics.stopLlmCall(sample, "propose_change_fix_declare");
        }
        if (newDeclarations == null || newDeclarations.isBlank()) {
            log.warn("propose_change_fix_declare: model returned no newDeclarations for {}", undefinedNames);
            return null;
        }
        newDeclarations = stripCodeFences(newDeclarations);

        // Indent applied ourselves, from an existing 01-level item already in
        // this WORKING-STORAGE SECTION — never trust the model's own
        // indentation for a line that gets inserted next to fixed-format COBOL
        // it didn't write, and never touch declareExcerpt's own lines (see the
        // system prompt: only brand-new lines come back, so there is nothing
        // of the model's to re-indent that could corrupt an existing line).
        String indent = detectFieldIndent(declareExcerpt);
        List<String> newLines = new ArrayList<>();
        for (String line : newDeclarations.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) newLines.add(indent + trimmed);
        }
        if (newLines.isEmpty()) return null;

        List<String> rebuilt = new ArrayList<>(allLines.length + newLines.size());
        rebuilt.addAll(java.util.Arrays.asList(allLines).subList(0, pdLineIndex));
        rebuilt.addAll(newLines);
        rebuilt.addAll(java.util.Arrays.asList(allLines).subList(pdLineIndex, allLines.length));
        String fixedSource = String.join("\n", rebuilt);
        return new FixResult("declare missing field", explanation, declareExcerpt, fixedSource);
    }

    /** The leading whitespace an existing 01-level WORKING-STORAGE item uses in
     * {@code excerpt}, so a newly-inserted 01-level item matches this file's
     * own indentation instead of whatever the model happened to write. Falls
     * back to the last non-blank line's indent, then a conventional 8 spaces
     * (COBOL Area A starts at column 8) if the excerpt has nothing usable. */
    private String detectFieldIndent(String excerpt) {
        Pattern topLevelItem = Pattern.compile("^(\\s*)01\\s+[A-Z0-9-]+");
        String fallback = null;
        for (String line : excerpt.split("\n", -1)) {
            Matcher m = topLevelItem.matcher(line);
            if (m.find()) return m.group(1);
            if (!line.isBlank()) fallback = line.substring(0, line.length() - line.stripLeading().length());
        }
        return fallback != null ? fallback : "       ";
    }

    /** Extracts a small excerpt around the failing line(s) from {@code source},
     * asks the model to diagnose and fix just that excerpt — given the compiler's
     * own error text AND its own prior failed attempt(s), if any — and splices
     * the fix back in. Never sends the whole file to the model regardless of how
     * large it is, same principle as the section-scoped generate path. Returns
     * null if no error line number could be parsed, the model's fix can't be
     * located back in the source, or the call fails. */
    private FixResult attemptUsageWindowFix(String programId, String source, List<String> errors, String question,
                                              String answer, List<FixAttemptRecord> history) {
        Window window = computeErrorWindow(source, errors);
        if (window == null) return null;
        String excerpt = window.text();

        var sample = metrics.startLlmCall();
        String explanation;
        String fixedExcerpt;
        try {
            var response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(FIX_COMPILE_ERROR_SYSTEM_PROMPT),
                            new UserMessage(buildFixUserMessage(programId, window.start(), window.end(), errors, excerpt,
                                    question, answer, history))),
                    OpenAiChatOptions.builder().responseFormat(JSON_OBJECT_FORMAT).build()));
            var root = objectMapper.readTree(response.getResult().getOutput().getText());
            explanation = root.path("explanation").asText("Applied a fix.");
            fixedExcerpt = root.path("fixedExcerpt").asText(null);
        } catch (Exception e) {
            metrics.recordLlmCallError("propose_change_fix");
            log.warn("propose_change_fix failed: {}", e.toString());
            return null;
        } finally {
            metrics.stopLlmCall(sample, "propose_change_fix");
        }
        if (fixedExcerpt == null || fixedExcerpt.isBlank()) return null;
        fixedExcerpt = stripCodeFences(fixedExcerpt);
        fixedExcerpt = preserveLeadingWhitespace(excerpt, fixedExcerpt);

        // The prompt above already tells the model never to add a data-item
        // declaration or section header here — observed live not being enough:
        // on a harder case the model added "WORKING-STORAGE SECTION." plus new
        // 01-level items directly into a PROCEDURE DIVISION excerpt anyway,
        // producing a second, illegal WORKING-STORAGE SECTION buried inside
        // procedure logic. Nothing downstream ever detects or removes a stray
        // SECTION header (only attemptRelocateMisplacedDeclaration's narrower
        // data-item case), so it survived attempt after attempt, each one just
        // shuffling data items around it while the real corruption stayed put.
        // A prompt instruction is not enforcement — checking the model's own
        // output against the same patterns used elsewhere in this file is.
        if (introducesIllegalProcedureContent(excerpt, fixedExcerpt)) {
            return null;
        }

        int idx = source.indexOf(excerpt);
        if (idx < 0) return null;
        String fixedSource = source.substring(0, idx) + fixedExcerpt + source.substring(idx + excerpt.length());
        return new FixResult("usage-site fix", explanation, excerpt, fixedSource);
    }

    /** True only when {@code fixedExcerpt} contains a WORKING-STORAGE header or
     * a data-item declaration that {@code originalExcerpt} did NOT already
     * have — i.e. the model introduced one where none existed, which is always
     * illegal for an excerpt taken from PROCEDURE DIVISION logic. Comparing
     * against the original (rather than flagging any match unconditionally)
     * avoids a false positive on the rare excerpt that legitimately spans the
     * WORKING-STORAGE/PROCEDURE DIVISION boundary already. */
    private boolean introducesIllegalProcedureContent(String originalExcerpt, String fixedExcerpt) {
        return containsHeaderOrDeclaration(fixedExcerpt) && !containsHeaderOrDeclaration(originalExcerpt);
    }

    private boolean containsHeaderOrDeclaration(String text) {
        for (String line : text.split("\n", -1)) {
            if (WORKING_STORAGE_HEADER.matcher(line).matches() || MISPLACED_DATA_ITEM.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** Debug-visible context appended to a "still failing" step: shows the actual
     * current line the latest error points at, so a repeated failure is visibly
     * a repeated failure — not just a repeated message — as it happens. */
    private String annotateWithLine(String source, String error) {
        Matcher m = DIAGNOSTIC_LINE_NUMBER.matcher(error);
        if (!m.find()) return "";
        int lineNumber = Integer.parseInt(m.group(1));
        String[] lines = source.split("\n", -1);
        if (lineNumber < 1 || lineNumber > lines.length) return "";
        return " (line " + lineNumber + " now reads: \"" + lines[lineNumber - 1].strip() + "\")";
    }

    private Window computeErrorWindow(String source, List<String> errors) {
        String[] lines = source.split("\n", -1);
        int totalLines = lines.length;
        int minLine = Integer.MAX_VALUE;
        int maxLine = -1;
        for (String err : errors) {
            Matcher m = DIAGNOSTIC_LINE_NUMBER.matcher(err);
            if (m.find()) {
                int line = Integer.parseInt(m.group(1));
                minLine = Math.min(minLine, line);
                maxLine = Math.max(maxLine, line);
            }
        }
        if (maxLine < 0) return null;

        int windowStart = Math.max(1, minLine - FIX_CONTEXT_LINES);
        int windowEnd = Math.min(totalLines, maxLine + FIX_CONTEXT_LINES);
        if (windowEnd - windowStart + 1 > MAX_FIX_WINDOW_LINES) {
            windowEnd = Math.min(totalLines, windowStart + MAX_FIX_WINDOW_LINES);
        }

        StringBuilder excerptBuilder = new StringBuilder();
        for (int i = windowStart; i <= windowEnd; i++) {
            if (excerptBuilder.length() > 0) excerptBuilder.append('\n');
            excerptBuilder.append(lines[i - 1]);
        }
        return new Window(windowStart, windowEnd, excerptBuilder.toString());
    }

    /** Best-effort search for how {@code name} was declared in the file BEFORE
     * this change — a few lines of context around its first mention in the
     * original source, or null if it doesn't appear there at all (i.e. it's a
     * genuinely new field the LLM needs to design from scratch). */
    private String findOriginalDeclarationContext(String originalSource, String name) {
        if (originalSource == null) return null;
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(originalSource);
        if (!m.find()) return null;
        String[] lines = originalSource.split("\n", -1);
        int cursor = 0;
        int lineNo = lines.length - 1;
        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1;
            if (cursor + lineLen > m.start()) {
                lineNo = i;
                break;
            }
            cursor += lineLen;
        }
        int start = Math.max(0, lineNo - 6);
        int end = Math.min(lines.length, lineNo + 3);
        return String.join("\n", java.util.Arrays.asList(lines).subList(start, end));
    }

    private String buildDeclareUserMessage(String programId, Set<String> undefinedNames, List<String> errors,
                                             String usageExcerpt, String declareExcerpt, String question, String answer,
                                             List<FixAttemptRecord> history, String originalContext) {
        String originalContextBlock = originalContext.isBlank() ? ""
                : "\nThese identifier(s) already existed in the file BEFORE this change and were most likely " +
                  "dropped by mistake while generating the edit — RESTORE them exactly as shown below rather " +
                  "than inventing a new shape for them:\n" + originalContext + "\n";

        return """
                Original question: %s

                Recommended change (from the assistant's answer):
                %s

                In %s, the compiler says these identifier(s) are not defined: %s

                Compiler error(s):
                %s

                Excerpt showing how the missing identifier(s) are USED (for inferring the \
                right PICTURE clause — do not edit this, it's for context only):
                %s
                %s%s
                END OF WORKING-STORAGE SECTION (append the missing declaration(s) here, \
                just before PROCEDURE DIVISION):
                %s
                """.formatted(question, answer, programId, String.join(", ", undefinedNames),
                String.join("\n", errors), usageExcerpt, originalContextBlock, historyText(history), declareExcerpt);
    }

    private String buildFixUserMessage(String programId, int windowStart, int windowEnd, List<String> errors,
                                         String excerpt, String question, String answer, List<FixAttemptRecord> history) {
        return """
                Original question: %s

                Recommended change (from the assistant's answer):
                %s

                This excerpt is lines %d-%d of %s (NOT the whole file) — it failed to compile.

                Compiler error(s):
                %s
                %s
                CURRENT EXCERPT (fix this):
                %s
                """.formatted(question, answer, windowStart, windowEnd, programId, String.join("\n", errors),
                historyText(history), excerpt);
    }

    private String historyText(List<FixAttemptRecord> history) {
        if (history.isEmpty()) return "";
        StringBuilder historyText = new StringBuilder();
        historyText.append("PREVIOUS ATTEMPT(S) — each already failed, do not repeat these:\n");
        for (int i = 0; i < history.size(); i++) {
            FixAttemptRecord h = history.get(i);
            historyText.append("Attempt ").append(i + 1).append(" (").append(h.strategy()).append(") — you said: \"")
                    .append(h.explanation()).append("\"\nThat version:\n").append(h.excerpt())
                    .append("\n...still produced:\n").append(String.join("\n", h.errorsAfter())).append("\n\n");
        }
        return historyText.toString();
    }

    private String sectionLabel(ChunkResult c) {
        String name = (c.sectionName() != null && !c.sectionName().isBlank()) ? c.sectionName() : c.chunkId();
        if (c.lineStart() != null && c.lineEnd() != null) {
            return name + " (lines " + c.lineStart() + "-" + c.lineEnd() + ")";
        }
        return name;
    }

    private record SubSection(String name, String content, int lineStart, int lineEnd) {
    }

    /** Splits a too-coarse chunk (see COARSE_SECTION_LINE_THRESHOLD) into real
     * COBOL paragraphs/sections by detecting header lines within it — computed
     * on the fly from the chunk's own text, independent of whatever granularity
     * ingestion happened to produce. {@code lineOffset} is the chunk's own
     * starting line number in the full file, so returned sub-sections carry
     * correct absolute line numbers. Returns an empty list if fewer than 2
     * paragraph boundaries are found (nothing meaningful to split). */
    private List<SubSection> splitIntoParagraphs(String text, int lineOffset) {
        String[] lines = text.split("\n", -1);
        List<Integer> boundaryIdx = new ArrayList<>();
        List<String> boundaryNames = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PARAGRAPH_HEADER.matcher(lines[i]);
            if (m.matches()) {
                boundaryIdx.add(i);
                boundaryNames.add(m.group(1));
            }
        }
        if (boundaryIdx.size() < 2) return List.of();

        // A "SECTION." header is commonly followed immediately by its own
        // "-PARA." header on the very next line (e.g. "4100-REJECT-CLAIM
        // SECTION." then "4100-REJECT-CLAIM-PARA." then the real statements)
        // — two boundaries with nothing but each other between them. Treating
        // them as separate units would split off a bogus paragraph containing
        // only a header line and push the real body onto the next entry
        // instead, which is exactly what a caller asking to edit "the
        // REJECT-CLAIM paragraph" doesn't want. Collapse any run of boundaries
        // that are on consecutive lines into one group, keyed by the first
        // (outermost, more descriptive) name in the run.
        List<Integer> groupStart = new ArrayList<>();
        List<String> groupName = new ArrayList<>();
        for (int b = 0; b < boundaryIdx.size(); b++) {
            if (b > 0 && boundaryIdx.get(b) == boundaryIdx.get(b - 1) + 1) {
                continue;
            }
            groupStart.add(boundaryIdx.get(b));
            groupName.add(boundaryNames.get(b));
        }
        if (groupStart.size() < 2) return List.of();

        List<SubSection> result = new ArrayList<>();
        for (int g = 0; g < groupStart.size(); g++) {
            int start = groupStart.get(g);
            int end = (g + 1 < groupStart.size()) ? groupStart.get(g + 1) - 1 : lines.length - 1;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            result.add(new SubSection(groupName.get(g), sb.toString(), lineOffset + start, lineOffset + end));
        }
        return result;
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
            log.warn("propose_change_whole failed: {}", e.toString());
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
            log.warn("propose_change_locate failed: {}", e.toString());
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
            log.warn("propose_change_section failed: {}", e.toString());
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
