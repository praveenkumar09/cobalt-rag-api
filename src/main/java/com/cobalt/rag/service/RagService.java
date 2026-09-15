package com.cobalt.rag.service;

import com.cobalt.rag.model.AskResponse;
import com.cobalt.rag.model.BusinessFlow;
import com.cobalt.rag.model.BusinessRule;
import com.cobalt.rag.model.ChunkResult;
import com.cobalt.rag.model.CorpusSample;
import com.cobalt.rag.model.DataDictionaryEntry;
import com.cobalt.rag.model.DecisionTableRow;
import com.cobalt.rag.model.GraphRelationship;
import com.cobalt.rag.model.ImpactAnalysis;
import com.cobalt.rag.model.SourceCitation;
import com.cobalt.rag.model.TechnicalRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class RagService {

    private final VectorSearchService vectorSearch;
    private final GraphSearchService graphSearch;
    private final ImpactAnalysisService impactAnalysisService;
    private final BusinessInsightService businessInsightService;
    private final ChatModel chatModel;
    // Used ONLY for the streamed answer's token flux — see its own Javadoc for
    // why chatModel.stream() (Spring AI's OpenAiApi streaming path) can emit
    // tokens out of order and this bypasses it. chatModel.call() (every other
    // use in this class: business rules, follow-ups, starter suggestions,
    // the non-streaming ask()) is unaffected and stays exactly as-is.
    private final OrderedOpenAiStreamClient orderedStreamClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Canned fallback answer the LLM is instructed to return verbatim for off-topic
    // or unsupported-by-context questions. Used to detect that case after the LLM
    // responds, so citations can be suppressed for exactly the responses where the
    // model itself decided the retrieved context didn't actually answer the question.
    private static final String OUT_OF_SCOPE_MESSAGE =
            "I'm COBOL AI, specialized in analyzing life insurance COBOL/AS400 mainframe " +
            "codebases. I can answer questions about policy processing logic, surrender and " +
            "withdrawal flows, GIRO and premium collection, claims handling, fund management, " +
            "and the underlying COBOL programs that implement these processes. Your question " +
            "appears to be outside this domain — could you rephrase it in the context of " +
            "the life insurance codebase?";

    // ── System Prompt ──────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT = ("""
            You are COBOL AI, an expert AS400/COBOL mainframe code analyst specializing \
            in life insurance system analysis and modernization. You have deep knowledge of \
            both mainframe COBOL/JCL programming and life insurance business processes.

            ## Your Role
            You analyze COBOL programs, JCL jobs, and copybooks from a life insurance \
            mainframe codebase running on AS400/IBM i. You help business analysts, developers, \
            architects, and modernization teams understand:

            ### Technical Areas
            - Business logic encoded in COBOL programs and their divisions \
              (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
            - Batch processing flows and JCL job step structures
            - Program call hierarchies and dependencies (CALL, PERFORM, LINK)
            - File I/O patterns (VSAM KSDS/ESDS keyed files, QSAM sequential files, DB2 tables)
            - Copybook data structures, field layouts, 88-level condition names, and REDEFINES clauses
            - Error handling patterns, abend codes, and return code conventions

            ### Life Insurance Business Domains
            - **Policy Management**: policy issuance, endorsements, renewals, lapsation, reinstatement
            - **Surrender**: full surrender processing, surrender value calculation (guaranteed vs \
              non-guaranteed), surrender charges, surrender benefit payout workflows
            - **Partial Withdrawal**: partial withdrawal eligibility checks, minimum balance rules, \
              withdrawal fee calculation, fund unit redemption logic
            - **Claims Processing**: death claims, maturity claims, critical illness claims, \
              claim intimation, claim assessment, claim approval workflows, claim payout
            - **GIRO Processing**: General Interbank Recurring Order setup and maintenance, \
              direct debit collection batch jobs, GIRO rejection handling, re-presentment logic, \
              premium collection reconciliation
            - **Premium Processing**: regular premium billing, grace period handling, \
              auto-debit premium collection, premium allocation to funds
            - **Fund Management**: unit-linked fund switching, NAV (Net Asset Value) processing, \
              fund allocation and redemption, bonus allocation
            - **Agent & Commission**: agent commission calculation, clawback processing, \
              distributor commission splits
            - **Regulatory & Reporting**: MAS regulatory reports (Singapore), actuarial data feeds, \
              reinsurance cession schedules

            ## Answer Rules
            1. **Ground every answer in the provided context.** Only use information present \
               in the retrieved code chunks or graph relationships. If context is insufficient, \
               say so explicitly — do not fabricate logic.
            2. **Speak both languages**: explain the technical COBOL implementation AND translate \
               it into what it means for the insurance business process.
            3. **Be specific**: reference program names, paragraph names, COBOL field names \
               (e.g. WS-POLICY-NUMBER, SURR-CHARGE-RATE), copybook names, or file names \
               found in the context.
            4. **Use graph relationships** when describing how programs in a processing chain \
               call each other (e.g. a GIRO batch job → premium allocation → fund redemption).
            5. **Structured answers**: use numbered steps for process flows, bullet points for \
               feature lists, and tables in markdown when comparing options.

            ## Output Format
            Provide your answer in this structure:
            ```
            [Direct answer in 1-3 sentences — what the program/process does in business terms]

            **Business Context:**
            [1-2 sentences explaining the insurance business purpose]

            **Technical Details:**
            - [Bullet: key COBOL section/paragraph and what it does]
            - [Bullet: key file, table, or copybook involved]
            - [Bullet: any notable logic — calculations, validations, error handling]

            **Process Flow** (if applicable):
            1. Step one
            2. Step two

            **Programs referenced:** PROG1, PROG2
            **Key relationships:** PROG1 -[CALLS]-> PROG2
            ```
            Use ```cobol code blocks when quoting source code.

            ## Example

            **Request:**
            { "question": "How does the surrender processing program calculate the surrender value?" }

            **Response:**
            The surrender processing program computes the net surrender value by deducting \
            applicable surrender charges and outstanding loan amounts from the policy's \
            accumulated fund value.

            **Business Context:**
            When a policyholder exits a life insurance policy before maturity, the insurer \
            pays the surrender value. This program enforces the product's surrender charge \
            schedule and ensures any outstanding policy loans are recovered before payout.

            **Technical Details:**
            - Reads the policy master record from POLMAST (VSAM KSDS keyed on policy number)
            - Looks up the surrender charge rate from SURRCHG table using policy year \
              (WS-POLICY-YEAR) and product code (WS-PROD-CODE)
            - Calculates: NET-SURR-VALUE = FUND-VALUE - (FUND-VALUE * SURR-CHARGE-RATE) \
              - OUTSTANDING-LOAN-AMT
            - Validates that NET-SURR-VALUE >= WS-MIN-SURRENDER-AMT (minimum surrender threshold)
            - If validation passes, writes a SURRENDER-REQUEST record to SURRREQ and calls \
              PAYOUTPGM for disbursement

            **Process Flow:**
            1. Read policy from POLMAST
            2. Validate policy status = 'IN-FORCE' (88-level: POL-INFORCE)
            3. Calculate gross fund value from unit holdings
            4. Apply surrender charge schedule
            5. Deduct outstanding loan
            6. Write surrender record and trigger payout

            **Programs referenced:** SURRPGM, PAYOUTPGM
            **Key relationships:** SURRPGM -[CALLS]-> PAYOUTPGM

            ## Out-of-Scope Response
            If the question is entirely unrelated to life insurance business processes, \
            COBOL/AS400 mainframe systems, JCL, or the codebase being analyzed, respond \
            with exactly this message and nothing else:

            "%s"
            """).formatted(OUT_OF_SCOPE_MESSAGE);

    // ── Follow-up suggestion prompt ───────────────────────────────────────────
    private static final String FOLLOWUP_SYSTEM_PROMPT = """
            You generate follow-up questions for a COBOL/AS400 mainframe code assistant chat.
            Given the user's question, the assistant's answer, and the retrieved code context, \
            suggest exactly 3 concise, specific follow-up questions the user would plausibly ask \
            next. Ground each suggestion in program names, paragraphs, files, or business terms \
            that actually appear in the answer or context — never invent a program/section name \
            that wasn't mentioned. Do not repeat or rephrase the original question. Keep each \
            under 12 words.

            Respond with ONLY a JSON array of exactly 3 strings, no markdown fences, no commentary. \
            Example:
            ["How does PREMCOL validate the policy number?", "What happens if GIRO collection fails twice?", "Which programs call SURRPGM?"]
            """;

    // ── Starter-suggestion prompt (home-screen chips) ─────────────────────────
    private static final String STARTER_SUGGESTIONS_SYSTEM_PROMPT = """
            You generate example starter questions shown on the home screen of a COBOL/AS400 \
            mainframe code assistant chat, before any conversation has started. Given a random \
            sample of programs and sections actually present in the ingested codebase, suggest \
            exactly 3 concise, inviting questions a first-time user might ask to explore what \
            this assistant can do. Ground every suggestion in a real program, section, or domain \
            name from the sample — never invent one that wasn't given. Keep each under 14 words.

            Respond with ONLY a JSON array of exactly 3 strings, no markdown fences, no commentary.
            """;

    // In-memory cache for the starter suggestions — the underlying codebase only
    // changes on re-ingestion, so there's no need to call the LLM on every home
    // screen load. Stale-while-revalidate: once the TTL lapses, callers still get
    // the (stale) cached list immediately, while a single background refresh
    // brings it current for next time — nobody blocks on the LLM call except the
    // very first request ever (before the startup warm-up has had a chance to run).
    private static final long SUGGESTIONS_TTL_MS = 30 * 60 * 1000;
    private volatile List<String> cachedSuggestions = List.of();
    private volatile long suggestionsCachedAt = 0;
    private final AtomicBoolean suggestionsRefreshing = new AtomicBoolean(false);

    // Stopwords filtered out before sending keywords to the graph search
    private static final Pattern STOPWORD = Pattern.compile(
            "\\b(what|does|do|the|a|an|is|are|how|which|where|when|who|why|and|or|in|" +
            "on|of|to|for|with|this|that|it|its|can|will|has|have|be|been|being|by|" +
            "from|at|as|was|were|about|if|then|so|but|each|their|they|some|into)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Impact analysis runs the extra Neo4j traversal only for questions that
    // actually sound like a change request — an ordinary "what does this do"
    // question has nothing to compute change order for.
    // Each stem allows its common inflections (modify/modifies/modified/modifying,
    // etc.) — matching only the bare infinitive missed almost every natural-language
    // phrasing of a change request ("modified", "modifying", "updated", "adding"...),
    // since English spelling changes (y -> ied) or the run of word characters in
    // "modifying" mean the inflected form doesn't share a \b-delimited match with
    // the bare word. The \b anchors still prevent false positives like "address"
    // or "prefix" matching "add"/"fix".
    private static final Pattern CHANGE_REQUEST_WORD = Pattern.compile(
            "\\b(chang(e|es|ed|ing)|modif(y|ies|ied|ying)|updat(e|es|ed|ing)|" +
            "add(s|ed|ing)?|remov(e|es|ed|ing)|delet(e|es|ed|ing)|" +
            "refactor(s|ed|ing)?|renam(e|es|ed|ing)|replac(e|es|ed|ing)|" +
            "alter(s|ed|ing)?|extend(s|ed|ing)?|impact(s|ed|ing)?|" +
            "migrat(e|es|ed|ing)|fix(es|ed|ing)?)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public RagService(VectorSearchService vectorSearch,
                      GraphSearchService graphSearch,
                      ImpactAnalysisService impactAnalysisService,
                      BusinessInsightService businessInsightService,
                      ChatModel chatModel,
                      OrderedOpenAiStreamClient orderedStreamClient) {
        this.vectorSearch = vectorSearch;
        this.graphSearch  = graphSearch;
        this.impactAnalysisService = impactAnalysisService;
        this.businessInsightService = businessInsightService;
        this.chatModel    = chatModel;
        this.orderedStreamClient = orderedStreamClient;
    }

    public AskResponse ask(String question) {
        // 1. Semantic search — retrieve top-K relevant code chunks from pgvector
        List<ChunkResult> chunks = vectorSearch.search(question);

        // 2. Extract program IDs and keywords for graph traversal
        List<String> programIds = chunks.stream()
                .map(ChunkResult::programId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        List<String> keywords = extractKeywords(question);

        // 3. Graph search — find program relationships in Neo4j
        List<GraphRelationship> graphContext = graphSearch.findRelationships(programIds, keywords);

        // 4. Build augmented context block (vector + graph)
        String contextBlock = buildContextBlock(chunks, graphContext);

        // 5. Compose user message with context + question
        String userMessage = """
                %s

                Question: %s
                """.formatted(contextBlock, question);

        String answer;
        ImpactAnalysis impactAnalysis;
        List<BusinessRule> businessRules = List.of();
        List<DecisionTableRow> decisionTable = List.of();
        BusinessFlow businessFlow = null;
        List<DataDictionaryEntry> dataDictionary = List.of();
        List<TechnicalRule> technicalRules = List.of();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 3b. Impact analysis doesn't depend on the answer text — start it now, on
            // a virtual thread, so its (potentially slow) Neo4j traversal runs
            // concurrently with the main LLM call below instead of blocking ahead of it.
            Future<ImpactAnalysis> impactFuture = executor.submit(() ->
                    looksLikeChangeRequest(question) ? impactAnalysisService.analyze(programIds) : null);

            // 6. Call LLM
            var response = chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage(SYSTEM_PROMPT),
                            new UserMessage(userMessage)
                    ))
            );
            answer = response.getResult().getOutput().getText();
            boolean outOfScope = isOutOfScope(answer);

            ImpactAnalysis resolvedImpact;
            try {
                resolvedImpact = impactFuture.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resolvedImpact = null;
            } catch (ExecutionException e) {
                resolvedImpact = null;
            }
            impactAnalysis = outOfScope ? null : resolvedImpact;

            // businessRules/decisionTable/businessFlow/dataDictionary/technicalRules are
            // five further independent LLM/DB calls with no dependency on each other —
            // run them concurrently too, instead of paying their latency one after another.
            if (!outOfScope) {
                Future<List<BusinessRule>> rulesFuture = executor.submit(
                        () -> businessInsightService.extractBusinessRules(question, answer, contextBlock, chunks));
                Future<List<DecisionTableRow>> tableFuture = executor.submit(
                        () -> businessInsightService.extractDecisionTable(question, answer, contextBlock, chunks));
                Future<BusinessFlow> flowFuture = executor.submit(
                        () -> businessInsightService.buildBusinessFlow(graphContext));
                Future<List<DataDictionaryEntry>> dictionaryFuture = executor.submit(
                        () -> businessInsightService.extractDataDictionary(question, answer, contextBlock, chunks));
                Future<List<TechnicalRule>> technicalRulesFuture = executor.submit(
                        () -> businessInsightService.extractTechnicalRules(question, answer, contextBlock, chunks));

                try {
                    businessRules = rulesFuture.get();
                    decisionTable = tableFuture.get();
                    businessFlow = flowFuture.get();
                    dataDictionary = dictionaryFuture.get();
                    technicalRules = technicalRulesFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    // Each task is already best-effort/self-catching; this is just a safety net.
                }
            }
        }

        boolean outOfScope = isOutOfScope(answer);
        List<SourceCitation> sources = outOfScope ? List.of() : toCitations(chunks);
        List<GraphRelationship> visibleGraphContext = outOfScope ? List.of() : graphContext;
        List<String> followUps = outOfScope ? List.of() : generateFollowUps(question, answer, contextBlock);

        return new AskResponse(answer, sources, visibleGraphContext, chunks.size(), followUps, impactAnalysis,
                businessRules, decisionTable, businessFlow, dataDictionary, technicalRules);
    }

    /**
     * Streaming variant — returns an SSE Flux:
     *   1st event : JSON metadata  { type, sources, graphContext, chunksRetrieved }
     *   N events  : JSON tokens    { type:"token", content:"..." }
     *   Last event: "[DONE]"
     */
    public Flux<String> askStream(String question) {
        // Synchronous RAG retrieval (DB calls are blocking, done before streaming starts)
        List<ChunkResult> chunks = vectorSearch.search(question);

        List<String> programIds = chunks.stream()
                .map(ChunkResult::programId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        List<GraphRelationship> graphContext = graphSearch.findRelationships(programIds, extractKeywords(question));

        // Impact analysis doesn't depend on the answer text either — same reasoning
        // and pattern as businessFlowMono below: fire it now, on a background thread,
        // and tap the (likely-already-finished) result later as its own SSE event,
        // rather than blocking here and delaying the metadata event (and therefore
        // the first token) behind a potentially-slow Neo4j traversal.
        Mono<String> impactAnalysisMono = Mono.fromCallable(() -> {
                    if (!looksLikeChangeRequest(question)) return null;
                    ImpactAnalysis analysis = impactAnalysisService.analyze(programIds);
                    return analysis == null ? null : toJson(Map.of("type", "impactAnalysis", "analysis", analysis));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .cache();
        impactAnalysisMono.subscribe(); // fire-and-forget: start the background work now

        // Business flow depends only on graphContext (not the answer text), so it can
        // start right away and run concurrently with the main answer's token stream —
        // by the time anything downstream actually asks for it, it's often already
        // done, hiding its latency (including its own LLM polish call) almost entirely.
        // .cache() means whichever subscriber asks for it later (or first, if it's
        // already finished) just reads the one computed result.
        Mono<String> businessFlowMono = Mono.fromCallable(() -> {
                    BusinessFlow flow = businessInsightService.buildBusinessFlow(graphContext);
                    return flow == null ? null : toJson(Map.of("type", "businessFlow", "flow", flow));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .cache();
        businessFlowMono.subscribe(); // fire-and-forget: start the background work now

        List<SourceCitation> sources = toCitations(chunks);
        String contextBlock = buildContextBlock(chunks, graphContext);

        String userMessage = """
                %s

                Question: %s
                """.formatted(contextBlock, question);

        // Event 1: metadata (sources + graph context arrive before the first token —
        // impact analysis is no longer included here; it arrives as its own later
        // event once impactAnalysisMono resolves, so it never delays this event).
        Map<String, Object> metaPayload = new LinkedHashMap<>();
        metaPayload.put("type", "metadata");
        metaPayload.put("sources", sources);
        metaPayload.put("graphContext", graphContext);
        metaPayload.put("chunksRetrieved", chunks.size());
        Flux<String> metaFlux = Flux.just(toJson(metaPayload));

        // Events 2..N: streamed LLM tokens — via orderedStreamClient, NOT
        // chatModel.stream(), so token order is guaranteed (see its Javadoc).
        StringBuilder fullAnswer = new StringBuilder();
        Flux<String> tokenFlux = orderedStreamClient.streamText(SYSTEM_PROMPT, userMessage)
        .mapNotNull(text -> {
            if (text == null || text.isEmpty()) return null;
            fullAnswer.append(text);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("type", "token");
            payload.put("content", text);
            return toJson(payload);
        });

        // Event N+1: correction — only sent if the fully-streamed answer turned out to
        // be the out-of-scope fallback, which isn't known until every token has arrived.
        // Retracts any citations and graph relationships sent in the metadata event so
        // off-topic answers never display sources or key relationships, even though
        // retrieval necessarily ran before the LLM call.
        Flux<String> correctionFlux = Flux.defer(() -> {
            boolean hasSomethingToRetract = !sources.isEmpty() || !graphContext.isEmpty();
            if (!hasSomethingToRetract || !isOutOfScope(fullAnswer.toString())) {
                return Flux.empty();
            }
            Map<String, Object> correction = new LinkedHashMap<>();
            correction.put("type", "correction");
            correction.put("sources", List.of());
            correction.put("graphContext", List.of());
            correction.put("impactAnalysis", null);
            correction.put("businessRules", List.of());
            correction.put("decisionTable", List.of());
            correction.put("businessFlow", null);
            correction.put("dataDictionary", List.of());
            correction.put("technicalRules", List.of());
            return Flux.just(toJson(correction));
        });

        // Events N+2..5: follow-ups + business rules + decision table + data
        // dictionary — four independent LLM calls, all grounded in the full answer,
        // none depending on the others' output. Run them concurrently (each offloaded
        // to boundedElastic, Reactor's idiom for wrapping a blocking call) and merge
        // as each completes, rather than paying their latency one after another.
        Flux<String> postAnswerInsightsFlux = Flux.defer(() -> {
            String answer = fullAnswer.toString();
            if (isOutOfScope(answer)) {
                return Flux.empty();
            }

            Mono<String> followupMono = Mono.fromCallable(() -> {
                List<String> followUps = generateFollowUps(question, answer, contextBlock);
                return followUps.isEmpty() ? null : toJson(Map.of("type", "followups", "questions", followUps));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> businessRulesMono = Mono.fromCallable(() -> {
                List<BusinessRule> rules = businessInsightService.extractBusinessRules(question, answer, contextBlock, chunks);
                return rules.isEmpty() ? null : toJson(Map.of("type", "businessRules", "rules", rules));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> decisionTableMono = Mono.fromCallable(() -> {
                List<DecisionTableRow> rows = businessInsightService.extractDecisionTable(question, answer, contextBlock, chunks);
                return rows.isEmpty() ? null : toJson(Map.of("type", "decisionTable", "rows", rows));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> dataDictionaryMono = Mono.fromCallable(() -> {
                List<DataDictionaryEntry> entries = businessInsightService.extractDataDictionary(question, answer, contextBlock, chunks);
                return entries.isEmpty() ? null : toJson(Map.of("type", "dataDictionary", "entries", entries));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> technicalRulesMono = Mono.fromCallable(() -> {
                List<TechnicalRule> rules = businessInsightService.extractTechnicalRules(question, answer, contextBlock, chunks);
                return rules.isEmpty() ? null : toJson(Map.of("type", "technicalRules", "rules", rules));
            }).subscribeOn(Schedulers.boundedElastic());

            return Flux.merge(followupMono, businessRulesMono, decisionTableMono, dataDictionaryMono, technicalRulesMono)
                    .filter(Objects::nonNull);
        });

        // Event N+5: business flow — taps the mono kicked off back when graphContext
        // was first computed, so this is often instant by the time we get here.
        Flux<String> businessFlowFlux = Flux.defer(() -> isOutOfScope(fullAnswer.toString())
                ? Flux.empty()
                : businessFlowMono.flux().filter(Objects::nonNull));

        // Event N+6: impact analysis — taps the mono kicked off back when graphContext
        // was first computed (see impactAnalysisMono above), so this is usually
        // instant by the time we get here rather than delaying the whole response.
        Flux<String> impactAnalysisFlux = Flux.defer(() -> isOutOfScope(fullAnswer.toString())
                ? Flux.empty()
                : impactAnalysisMono.flux().filter(Objects::nonNull));

        // Final event: done signal
        Flux<String> doneFlux = Flux.just("[DONE]");

        return Flux.concat(metaFlux, tokenFlux, correctionFlux,
                postAnswerInsightsFlux, businessFlowFlux, impactAnalysisFlux, doneFlux);
    }

    /**
     * Fires once the app is accepting traffic, so the very first real request
     * for starter suggestions never has to wait on a live LLM call — by the
     * time anyone reaches the home screen, the cache is (almost always)
     * already warm.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmSuggestionsCache() {
        triggerBackgroundRefresh();
    }

    /**
     * Home-screen starter question suggestions, generated from a random sample of
     * whatever codebase is actually ingested right now — never hardcoded, so they
     * can't drift out of sync with the loaded corpus.
     *
     * Stale-while-revalidate: once {@link #SUGGESTIONS_TTL_MS} lapses, this still
     * returns the cached list immediately and kicks off a single background
     * refresh for next time, rather than making the caller wait on the LLM. Only
     * the very first call ever (before {@link #warmSuggestionsCache()} has had a
     * chance to complete) blocks on a live generation.
     */
    public List<String> getStarterSuggestions() {
        List<String> cached = cachedSuggestions;
        if (cached.isEmpty()) {
            return refreshSuggestionsSync();
        }
        if (System.currentTimeMillis() - suggestionsCachedAt >= SUGGESTIONS_TTL_MS) {
            triggerBackgroundRefresh();
        }
        return cached;
    }

    private void triggerBackgroundRefresh() {
        if (suggestionsRefreshing.compareAndSet(false, true)) {
            Thread.ofVirtual().start(() -> {
                try {
                    refreshSuggestionsSync();
                } finally {
                    suggestionsRefreshing.set(false);
                }
            });
        }
    }

    private List<String> refreshSuggestionsSync() {
        List<String> generated = generateStarterSuggestions();
        if (!generated.isEmpty()) {
            cachedSuggestions = generated;
            suggestionsCachedAt = System.currentTimeMillis();
        }
        return generated;
    }

    private List<String> generateStarterSuggestions() {
        try {
            List<CorpusSample> samples = vectorSearch.sampleForSuggestions(20);
            if (samples.isEmpty()) {
                return List.of();
            }

            StringBuilder sb = new StringBuilder();
            samples.forEach(s -> sb
                    .append("- Program: ").append(safe(s.programId()))
                    .append(" | Domain: ").append(safe(s.domain())).append("/").append(safe(s.subDomain()))
                    .append(" | Section: ").append(safe(s.sectionName()))
                    .append(" | Purpose: ").append(safe(s.sectionPurpose()))
                    .append("\n"));

            String userMessage = """
                    Here is a random sample of programs and sections actually present in the codebase:

                    %s
                    Suggest exactly 3 example starter questions grounded in the sample above.
                    """.formatted(sb);

            var response = chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage(STARTER_SUGGESTIONS_SYSTEM_PROMPT),
                            new UserMessage(userMessage)
                    ))
            );

            String text = response.getResult().getOutput().getText();
            String json = extractJsonArray(text);
            List<String> suggestions = objectMapper.readValue(json, new TypeReference<List<String>>() {});

            return suggestions.stream()
                    .filter(q -> q != null && !q.isBlank())
                    .limit(3)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isOutOfScope(String answer) {
        return answer != null && answer.trim().equals(OUT_OF_SCOPE_MESSAGE.trim());
    }

    private boolean looksLikeChangeRequest(String question) {
        return question != null && CHANGE_REQUEST_WORD.matcher(question).find();
    }

    private List<String> generateFollowUps(String question, String answer, String contextBlock) {
        try {
            String userMessage = """
                    Original question: %s

                    Assistant's answer:
                    %s

                    Retrieved context:
                    %s
                    """.formatted(question, answer, contextBlock);

            var response = chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage(FOLLOWUP_SYSTEM_PROMPT),
                            new UserMessage(userMessage)
                    ))
            );

            String text = response.getResult().getOutput().getText();
            String json = extractJsonArray(text);
            List<String> followUps = objectMapper.readValue(json, new TypeReference<List<String>>() {});

            return followUps.stream()
                    .filter(q -> q != null && !q.isBlank())
                    .limit(3)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start == -1 || end == -1 || end < start) return "[]";
        return text.substring(start, end + 1);
    }

    private List<SourceCitation> toCitations(List<ChunkResult> chunks) {
        return chunks.stream()
                .filter(c -> c.sourceFile() != null && !c.sourceFile().isBlank())
                .map(c -> new SourceCitation(
                        c.chunkId(),
                        c.sourceFile(),
                        c.programId(),
                        c.sectionName(),
                        c.sectionPurpose(),
                        c.lineStart(),
                        c.lineEnd(),
                        c.fileType(),
                        Math.round(c.similarity() * 100.0) / 100.0,
                        c.content()
                ))
                .toList();
    }

    private String buildContextBlock(List<ChunkResult> chunks, List<GraphRelationship> graphContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== RETRIEVED CODE CHUNKS (Vector Search) ===\n");
        if (chunks.isEmpty()) {
            sb.append("No relevant chunks found.\n");
        } else {
            for (int i = 0; i < chunks.size(); i++) {
                ChunkResult c = chunks.get(i);
                sb.append("\n--- Chunk ").append(i + 1)
                  .append(" [similarity: ").append(String.format("%.2f", c.similarity())).append("] ---\n")
                  .append("Chunk ID: ").append(safe(c.chunkId())).append("\n")
                  .append("File    : ").append(safe(c.sourceFile())).append("\n")
                  .append("Program : ").append(safe(c.programId())).append("\n")
                  .append("Type    : ").append(safe(c.fileType())).append("\n")
                  .append("Domain  : ").append(safe(c.domain())).append(" / ").append(safe(c.subDomain())).append("\n")
                  .append("Section : ").append(safe(c.sectionName())).append("\n")
                  .append("Purpose : ").append(safe(c.sectionPurpose())).append("\n")
                  .append("Content :\n").append(safe(c.content())).append("\n");
            }
        }

        sb.append("\n=== PROGRAM RELATIONSHIPS (Graph Search) ===\n");
        if (graphContext.isEmpty()) {
            sb.append("No graph relationships found.\n");
        } else {
            graphContext.forEach(rel -> sb.append("  ")
                    .append(rel.fromLabel()).append(" -[").append(rel.relType()).append("]-> ")
                    .append(rel.toLabel()).append("\n"));
        }

        return sb.toString();
    }

    private List<String> extractKeywords(String question) {
        return Arrays.stream(question.split("\\s+"))
                .map(w -> w.replaceAll("[^A-Za-z0-9]", ""))
                .filter(w -> w.length() > 3)
                .filter(w -> !STOPWORD.matcher(w).matches())
                .distinct()
                .limit(10)
                .toList();
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}