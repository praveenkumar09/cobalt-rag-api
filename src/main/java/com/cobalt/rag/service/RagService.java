package com.cobalt.rag.service;

import com.cobalt.rag.model.AskResponse;
import com.cobalt.rag.model.BusinessFlow;
import com.cobalt.rag.model.BusinessRule;
import com.cobalt.rag.model.ChatTurn;
import com.cobalt.rag.model.ChunkResult;
import com.cobalt.rag.model.CorpusSample;
import com.cobalt.rag.model.DataDictionaryEntry;
import com.cobalt.rag.model.DecisionTableRow;
import com.cobalt.rag.model.GraphRelationship;
import com.cobalt.rag.model.ImpactAnalysis;
import com.cobalt.rag.model.ScenarioTrace;
import com.cobalt.rag.model.SourceCitation;
import com.cobalt.rag.model.TechnicalRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final RagMetrics metrics;
    private final SecurityEventStore securityEventStore;
    private final SecurityPreFilter securityPreFilter;
    private final ChatMemoryService chatMemoryService;
    private final RerankService rerankService;
    private final int topK;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Loads a prompt body from src/main/resources/prompts/ — kept as plain text
    // files rather than Java text blocks so prompt content (long, product-facing
    // copy) can be read/edited/diffed independently of this class's actual logic.
    // Read once, at class-init time, into the static final prompt fields below —
    // same cost profile as a Java text block (in-memory for the process lifetime,
    // zero per-request overhead), just sourced from a file instead of inline.
    private static String loadPrompt(String resourceName) {
        String path = "/prompts/" + resourceName;
        try (InputStream in = RagService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt resource: " + path, e);
        }
    }

    // Canned fallback answer the LLM is instructed to return verbatim for off-topic
    // or unsupported-by-context questions. Used to detect that case after the LLM
    // responds, so citations can be suppressed for exactly the responses where the
    // model itself decided the retrieved context didn't actually answer the question.
    private static final String OUT_OF_SCOPE_MESSAGE =
            "Hi, I'm Orbit! For this proof of concept, I can help with four areas of our " +
            "life insurance COBOL/AS400 codebase: **Surrender Processing**, **Payment " +
            "Processing (Batch)**, **Partial Withdrawal**, and **Claims Processing**. For " +
            "example, you could ask how a surrender value is calculated, how the payment " +
            "processing batch job runs, how partial withdrawal eligibility is validated, or " +
            "how a claim gets assessed and approved. Could you ask something within one of " +
            "these four areas?";

    // Canned fallback answers for the three security categories below — same trick
    // as OUT_OF_SCOPE_MESSAGE: the LLM is instructed to return one of these verbatim,
    // so the app can detect exactly which category (if any) applied by string
    // comparison after the fact, without a second classification LLM call. See
    // classifySecurityViolation().
    private static final String SECURITY_PROMPT_INJECTION_MESSAGE =
            "I can't follow instructions that try to override my configured role, reveal my " +
            "internal system prompt, or make me act outside COBOL/AS400 code analysis and " +
            "change/modernization work. I'm happy to help with Surrender Processing, Payment " +
            "Processing (Batch), Partial Withdrawal, or Claims Processing — what would you " +
            "like to know?";

    private static final String SECURITY_PII_REQUEST_MESSAGE =
            "I'm not able to look up or disclose personally identifiable information (PII) — " +
            "names, NRIC/SSN, policy numbers tied to a real person, addresses, contact details, " +
            "or similar — even if that data exists in the underlying systems. I can explain the " +
            "COBOL logic and field structures that handle this data without exposing real " +
            "values. Could you rephrase your question about the code or process itself?";

    private static final String SECURITY_PII_PROVIDED_MESSAGE =
            "It looks like your message may contain personal information (e.g. an ID number, " +
            "name with contact details, or similar). For your privacy, please don't share real " +
            "personal data here — this tool analyzes COBOL/AS400 code, it doesn't process real " +
            "customer records. Please resend your question without any personal details.";

    // ── System Prompt ──────────────────────────────────────────────────────────
    // Shared between the tech (SYSTEM_PROMPT) and business (BUSINESS_SYSTEM_PROMPT)
    // variants — scope definition, security guidelines, and the out-of-scope
    // fallback must never diverge between view modes, since the app's own
    // isOutOfScope()/classifySecurityViolation() do exact string matching against
    // the canned messages regardless of which variant answered. Only the "Answer
    // Rules"/"Output Format"/"Example" sections differ by audience. Prompt bodies
    // live under src/main/resources/prompts/ (content, edited independently of
    // this class's logic) — see loadPrompt(). The canned messages above stay as
    // Java constants since isOutOfScope()/classifySecurityViolation() compare
    // against them directly; keeping them here makes that dependency obvious.
    private static final String SHARED_PREAMBLE = loadPrompt("shared-preamble.md");
    private static final String TECH_ANSWER_RULES = loadPrompt("tech-answer-rules.md");
    private static final String SHARED_OUT_OF_SCOPE = loadPrompt("shared-out-of-scope.md");

    private static final String SYSTEM_PROMPT =
            (SHARED_PREAMBLE + TECH_ANSWER_RULES + SHARED_OUT_OF_SCOPE).formatted(
                    SECURITY_PROMPT_INJECTION_MESSAGE, SECURITY_PII_REQUEST_MESSAGE,
                    SECURITY_PII_PROVIDED_MESSAGE, OUT_OF_SCOPE_MESSAGE);

    // Business-mode variant: same scope/security/out-of-scope rules (shared
    // above), but answers explain the RETRIEVED code from a business
    // perspective — long, elaborative, plain-language-first — for a reader
    // (business analyst, underwriter, product owner) who knows the insurance
    // business deeply but has never read a line of COBOL. Technical facts
    // (field names, paragraph names) are supporting evidence to ground a
    // claim, never the headline of the answer.
    private static final String BUSINESS_ANSWER_RULES = loadPrompt("business-answer-rules.md");

    private static final String BUSINESS_SYSTEM_PROMPT =
            (SHARED_PREAMBLE + BUSINESS_ANSWER_RULES + SHARED_OUT_OF_SCOPE).formatted(
                    SECURITY_PROMPT_INJECTION_MESSAGE, SECURITY_PII_REQUEST_MESSAGE,
                    SECURITY_PII_PROVIDED_MESSAGE, OUT_OF_SCOPE_MESSAGE);

    // ── Follow-up suggestion prompt ───────────────────────────────────────────
    private static final String FOLLOWUP_SYSTEM_PROMPT = loadPrompt("followup-system-prompt.md");

    // ── Starter-suggestion prompt (home-screen chips) ─────────────────────────
    private static final String STARTER_SUGGESTIONS_SYSTEM_PROMPT =
            loadPrompt("starter-suggestions-system-prompt.md");

    // ── HyDE query-expansion prompt (retrieval aid, see expandQuery) ──────────
    private static final String HYDE_EXPANSION_SYSTEM_PROMPT =
            loadPrompt("hyde-expansion-system-prompt.md");

    // ── Prompt-injection pre-check ─────────────────────────────────────────────
    // A separate, narrow, single-purpose classification call, run before the main
    // answer prompt is built. The main prompt's own Security Guidelines category 1
    // asks the SAME model that's also busy writing a long, elaborate answer to
    // simultaneously self-police for injection attempts — in practice this proved
    // unstable: the exact same legitimate change-request question (e.g. "add a
    // name field with validation: 1) ... 2) ...") would sometimes pass and
    // sometimes get refused, a sampling-variance problem that more worked examples
    // and role-definition tweaks could reduce but never fully eliminate, since
    // it's the same generative call making the call. A short, single-purpose
    // yes/no classification is inherently more reliable than a side-task buried
    // inside a much longer multi-instruction prompt. When this says SAFE, a
    // reassurance note is added to the main prompt's user message so the answer
    // model doesn't re-litigate the question — but its own Security Guidelines
    // stay fully in place as a second, independent layer (this only ever ADDS a
    // positive signal; it never suppresses the main model's own judgment), and
    // the deterministic SecurityPreFilter (regex-based, checked earlier, before
    // this ever runs) remains the first, cost-free line of defense against
    // blatant, well-known injection phrasings.
    private static final String INJECTION_PRECHECK_PROMPT = loadPrompt("injection-precheck-system-prompt.md");

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
            "migrat(e|es|ed|ing)|fix(es|ed|ing)?|skip(s|ped|ping)?)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // A question that OPENS with a purely explanatory lead-in ("How does X
    // handle Y?", "What is the purpose of Z?") routinely uses a change-word as
    // an ordinary noun/verb describing EXISTING behavior — "handle beneficiary
    // updates" isn't a request to update anything — so CHANGE_REQUEST_WORD
    // alone over-fires on it. looksLikeChangeRequest() suppresses that match
    // unless the question also carries a stronger actionable cue (below):
    // something genuinely hypothetical/directive, not just descriptive.
    private static final Pattern DESCRIPTIVE_LEAD_IN = Pattern.compile(
            "^\\s*(what\\s+(is|are|does|do)|how\\s+(is|are|does|do)|explain|describe|" +
            "tell\\s+me\\s+about|what'?s\\s+the\\s+(purpose|functionality|role))\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRONG_CHANGE_SIGNAL = Pattern.compile(
            "\\bwhat\\s+if\\b|\\bshould\\s+we\\b|\\bcan\\s+we\\b|\\bif\\s+we\\b|" +
            "\\bwe\\s+(want|need)\\s+to\\b|\\blet'?s\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Gates the Scenario Simulator (extractScenarioTrace): only fires the extra
    // LLM call when the question actually reads like a concrete "what if" —
    // ordinary questions never pay for it. Same cost-gating pattern as
    // CHANGE_REQUEST_WORD/looksLikeChangeRequest above.
    private static final Pattern SCENARIO_QUESTION_PATTERN = Pattern.compile(
            "\\bwhat\\s+(if|happens|would happen)\\b|\\bsuppose\\b|\\blet'?s say\\b|" +
            "\\bwalk me through\\b|\\bsimulate\\b",
            Pattern.CASE_INSENSITIVE
    );

    // COBOL field-name-shaped tokens (2+ hyphen-separated uppercase segments,
    // e.g. WS-COI-RATE, PMR-COVERAGE-AMOUNT) found directly in the ANSWER text —
    // see extractDiscussedFields. This is the primary signal now, not a
    // secondary one: relying only on the retrieved chunks' own key_data_fields
    // metadata (the original approach) silently misses fields the answer
    // clearly names whenever the chunk that happens to carry that field in its
    // metadata isn't among the handful of chunks retrieval picked as sources —
    // increasingly likely the more chunks a program has. The answer text itself
    // is the authoritative record of what it actually discussed; ImpactAnalysisService's
    // own FIELD_EXISTS_QUERY is the safety net against a false-positive match
    // (a capitalized non-field phrase) that isn't a real field anywhere.
    private static final Pattern FIELD_NAME_TOKEN = Pattern.compile(
            "\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+){1,6}\\b"
    );

    public RagService(VectorSearchService vectorSearch,
                      GraphSearchService graphSearch,
                      ImpactAnalysisService impactAnalysisService,
                      BusinessInsightService businessInsightService,
                      ChatModel chatModel,
                      OrderedOpenAiStreamClient orderedStreamClient,
                      RagMetrics metrics,
                      SecurityEventStore securityEventStore,
                      SecurityPreFilter securityPreFilter,
                      ChatMemoryService chatMemoryService,
                      RerankService rerankService,
                      @Value("${cobalt.rag.top-k:5}") int topK) {
        this.vectorSearch = vectorSearch;
        this.graphSearch  = graphSearch;
        this.impactAnalysisService = impactAnalysisService;
        this.businessInsightService = businessInsightService;
        this.chatModel    = chatModel;
        this.orderedStreamClient = orderedStreamClient;
        this.metrics = metrics;
        this.securityEventStore = securityEventStore;
        this.securityPreFilter = securityPreFilter;
        this.chatMemoryService = chatMemoryService;
        this.rerankService = rerankService;
        this.topK = topK;
    }

    /**
     * Cheap, capped-output HyDE-style query expansion (see
     * prompts/hyde-expansion-system-prompt.md): a short hypothetical answer to
     * the question, plausible COBOL vocabulary and all, embedded ALONGSIDE the
     * real question by VectorSearchService — often recovers matches a literal
     * question-embedding alone misses, since a hypothetical answer's vocabulary
     * is closer to how the target code chunks actually describe themselves.
     * Never throws — a failure here just means retrieval falls back to
     * question-only vector + keyword search, not a broken question.
     */
    private String expandQuery(String question) {
        Timer.Sample sample = metrics.startLlmCall();
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(HYDE_EXPANSION_SYSTEM_PROMPT), new UserMessage(question))));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            metrics.recordLlmCallError("hyde_expansion");
            return null;
        } finally {
            metrics.stopLlmCall(sample, "hyde_expansion");
        }
    }

    /** Retrieval pipeline shared by ask()/askStream(): HyDE-expand, hybrid
     * search a widened candidate pool, rerank down to the final top-k actually
     * used in the prompt. See VectorSearchService and RerankService. */
    private List<ChunkResult> retrieve(String question) {
        String hyde = expandQuery(question);
        List<ChunkResult> candidates = vectorSearch.search(question, hyde);
        return rerankService.rerank(question, candidates, topK);
    }

    /**
     * Prior turns for this conversation, for injection into the main answer's
     * prompt — auth-only (matches how ConversationStore already scopes chat
     * history by authenticated user): anonymous callers or questions with no
     * conversationId get no history, identical to pre-chat-memory behavior.
     * Never throws — see ChatMemoryService.
     */
    private List<ChatTurn> resolveHistory(String userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return chatMemoryService.recentTurns(userId, conversationId);
    }

    /**
     * Backs the "Export Functional Requirement Report" action (see
     * BusinessInsightService#generateFunctionalRequirement) — a thin passthrough,
     * same pattern as this service delegating to CodeChangeService for the
     * propose-change flow, so RagController never talks to BusinessInsightService
     * directly. Throws on failure (no silent empty result — see that method's
     * Javadoc for why); RagController turns that into an error response.
     */
    public String generateFunctionalRequirement(String question, String answer,
                                                  List<BusinessRule> businessRules,
                                                  List<DecisionTableRow> decisionTable,
                                                  List<DataDictionaryEntry> dataDictionary) {
        return businessInsightService.generateFunctionalRequirement(
                question, answer, businessRules, decisionTable, dataDictionary);
    }

    public AskResponse ask(String question, String userId, String viewMode, String conversationId) {
        boolean businessMode = "business".equals(viewMode);
        List<ChatTurn> history = resolveHistory(userId, conversationId);
        // 0. Deterministic regex/deny-list backstop, checked BEFORE any retrieval or
        // LLM call — see SecurityPreFilter's Javadoc for why this exists alongside
        // the LLM's own judgment. Short-circuiting here also saves the cost of a
        // vector search + LLM call for these (typically scripted) obvious cases.
        String preFilterViolation = checkPreFilter(question);
        if (preFilterViolation != null) {
            recordSecurityViolation(preFilterViolation, userId, question);
            metrics.recordAnswer("security_violation");
            String canned = cannedSecurityMessage(preFilterViolation);
            return new AskResponse(canned, List.of(), List.of(), 0, List.of(), null,
                    List.of(), List.of(), null, List.of(), List.of(), null);
        }

        // 1. Retrieval: HyDE-expand, hybrid (vector + keyword) search a widened
        // candidate pool, rerank down to the final chunks actually used — see
        // retrieve()/VectorSearchService/RerankService.
        List<ChunkResult> chunks = retrieve(question);
        metrics.recordChunksRetrieved(chunks.size());

        // 2. Extract program IDs and keywords for graph traversal
        List<String> programIds = chunks.stream()
                .map(ChunkResult::programId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        List<String> keywords = extractKeywords(question);

        // 3. Graph search — find program relationships in Neo4j
        List<GraphRelationship> graphContext = graphSearch.findRelationships(programIds, keywords);
        metrics.recordGraphContext(!graphContext.isEmpty());

        // 4. Build augmented context block (vector + graph)
        String contextBlock = buildContextBlock(chunks, graphContext);

        // 5. Compose user message with context + question
        String userMessage = buildUserMessage(contextBlock, question);

        String answer;
        ImpactAnalysis impactAnalysis;
        List<BusinessRule> businessRules = List.of();
        List<DecisionTableRow> decisionTable = List.of();
        BusinessFlow businessFlow = null;
        List<DataDictionaryEntry> dataDictionary = List.of();
        List<TechnicalRule> technicalRules = List.of();
        ScenarioTrace scenarioTrace = null;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 3b. Impact analysis doesn't depend on the answer text — start it now, on
            // a virtual thread, so its (potentially slow) Neo4j traversal runs
            // concurrently with the main LLM call below instead of blocking ahead of it.
            // The Tech chat view is still the only one that renders this inline
            // (MessageBubble gates it on viewMode === 'tech'), but Business-mode
            // messages need it too for the Change Impact Report export, so it's no
            // longer skipped by view mode — only by looksLikeChangeRequest(question),
            // same as before, so ordinary (non-change-request) questions in either
            // mode still submit a virtual thread that returns immediately without
            // touching Neo4j or the LLM — negligible added cost.
            Future<ImpactAnalysis> impactFuture = executor.submit(() -> {
                if (!looksLikeChangeRequest(question)) return null;
                metrics.recordImpactAnalysisTriggered();
                return impactAnalysisService.analyze(programIds);
            });

            // 6. Call LLM
            Timer.Sample answerSample = metrics.startLlmCall();
            try {
                List<Message> promptMessages = new ArrayList<>();
                promptMessages.add(new SystemMessage(businessMode ? BUSINESS_SYSTEM_PROMPT : SYSTEM_PROMPT));
                for (ChatTurn turn : history) {
                    promptMessages.add("assistant".equals(turn.role())
                            ? new AssistantMessage(turn.content())
                            : new UserMessage(turn.content()));
                }
                promptMessages.add(new UserMessage(userMessage));

                var response = chatModel.call(new Prompt(promptMessages));
                answer = response.getResult().getOutput().getText();
            } finally {
                metrics.stopLlmCall(answerSample, "answer");
            }
            boolean outOfScope = isOutOfScope(answer, !chunks.isEmpty());
            String securityViolation = classifySecurityViolation(answer);
            metrics.recordAnswer(outcomeLabel(answer, securityViolation, !chunks.isEmpty()));
            if (securityViolation != null) {
                recordSecurityViolation(securityViolation, userId, question);
            }
            boolean suppressExtras = outOfScope || securityViolation != null;

            ImpactAnalysis resolvedImpact = null;
            if (impactFuture != null) {
                try {
                    resolvedImpact = impactFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    // best-effort
                }
            }
            // Now that the answer is known, narrow the (structural) impact result to
            // the specific field(s) it actually discusses, if any were identified.
            // This is precise on purpose: analyze() returns just the seed tier (not
            // null) when nothing field-relevant survives, so "narrowed to nothing"
            // correctly replaces the broader unfiltered list here rather than being
            // silently ignored in favor of it.
            if (!suppressExtras && resolvedImpact != null) {
                Set<String> fieldNames = extractDiscussedFields(chunks, answer);
                if (!fieldNames.isEmpty()) {
                    ImpactAnalysis filtered = impactAnalysisService.analyze(programIds, fieldNames);
                    if (filtered != null) resolvedImpact = filtered;
                }
            }
            impactAnalysis = suppressExtras ? null : resolvedImpact;

            // businessRules/decisionTable/businessFlow/technicalRules are each only
            // rendered in one view mode (see MessageBubble) — only compute the ones the
            // active mode will actually show. dataDictionary renders in both, so it
            // always runs. Independent calls, so run whichever apply concurrently
            // rather than paying their latency one after another.
            if (!suppressExtras) {
                Future<List<BusinessRule>> rulesFuture = businessMode ? executor.submit(
                        () -> businessInsightService.extractBusinessRules(question, answer, contextBlock, chunks)) : null;
                Future<List<DecisionTableRow>> tableFuture = businessMode ? executor.submit(
                        () -> businessInsightService.extractDecisionTable(question, answer, contextBlock, chunks)) : null;
                Future<BusinessFlow> flowFuture = businessMode ? executor.submit(
                        () -> businessInsightService.buildBusinessFlow(graphContext)) : null;
                Future<List<DataDictionaryEntry>> dictionaryFuture = executor.submit(
                        () -> businessInsightService.extractDataDictionary(question, answer, contextBlock, chunks));
                Future<List<TechnicalRule>> technicalRulesFuture = businessMode ? null : executor.submit(
                        () -> businessInsightService.extractTechnicalRules(question, answer, contextBlock, chunks));
                // Scenario Simulator: only for business-mode questions that actually read
                // like a concrete "what if" — see looksLikeScenarioQuestion. Ordinary
                // business questions never pay for this extra LLM call.
                Future<ScenarioTrace> scenarioFuture = (businessMode && looksLikeScenarioQuestion(question))
                        ? executor.submit(() -> businessInsightService.extractScenarioTrace(question, answer, contextBlock, chunks))
                        : null;

                try {
                    if (rulesFuture != null) businessRules = rulesFuture.get();
                    if (tableFuture != null) decisionTable = tableFuture.get();
                    if (flowFuture != null) businessFlow = flowFuture.get();
                    dataDictionary = dictionaryFuture.get();
                    if (technicalRulesFuture != null) technicalRules = technicalRulesFuture.get();
                    if (scenarioFuture != null) scenarioTrace = scenarioFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    // Each task is already best-effort/self-catching; this is just a safety net.
                }
            }
        }

        boolean suppressExtras = isOutOfScope(answer, !chunks.isEmpty()) || classifySecurityViolation(answer) != null;
        List<SourceCitation> sources = suppressExtras ? List.of() : toCitations(chunks);
        List<GraphRelationship> visibleGraphContext = suppressExtras ? List.of() : graphContext;
        List<String> followUps = suppressExtras ? List.of() : generateFollowUps(question, answer, contextBlock);
        if (scenarioTrace != null && scenarioTrace.steps().isEmpty()) {
            scenarioTrace = null;
        }

        return new AskResponse(answer, sources, visibleGraphContext, chunks.size(), followUps, impactAnalysis,
                businessRules, decisionTable, businessFlow, dataDictionary, technicalRules, scenarioTrace);
    }

    /**
     * Streaming variant — returns an SSE Flux:
     *   1st event : JSON metadata  { type, sources, graphContext, chunksRetrieved }
     *   N events  : JSON tokens    { type:"token", content:"..." }
     *   Last event: "[DONE]"
     */
    public Flux<String> askStream(String question, String userId, String viewMode, String conversationId) {
        boolean businessMode = "business".equals(viewMode);
        List<ChatTurn> history = resolveHistory(userId, conversationId);
        // 0. Same deterministic backstop as ask() — see its comment above.
        String preFilterViolation = checkPreFilter(question);
        if (preFilterViolation != null) {
            recordSecurityViolation(preFilterViolation, userId, question);
            metrics.recordAnswer("security_violation");
            String canned = cannedSecurityMessage(preFilterViolation);
            Map<String, Object> metaPayload = new LinkedHashMap<>();
            metaPayload.put("type", "metadata");
            metaPayload.put("sources", List.of());
            metaPayload.put("graphContext", List.of());
            metaPayload.put("chunksRetrieved", 0);
            Map<String, String> tokenPayload = new LinkedHashMap<>();
            tokenPayload.put("type", "token");
            tokenPayload.put("content", canned);
            return Flux.just(toJson(metaPayload), toJson(tokenPayload), "[DONE]");
        }

        // Synchronous RAG retrieval (DB/LLM calls are blocking, done before streaming
        // starts): HyDE-expand, hybrid search a widened pool, rerank down — see retrieve().
        List<ChunkResult> chunks = retrieve(question);
        metrics.recordChunksRetrieved(chunks.size());

        List<String> programIds = chunks.stream()
                .map(ChunkResult::programId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        List<GraphRelationship> graphContext = graphSearch.findRelationships(programIds, extractKeywords(question));
        metrics.recordGraphContext(!graphContext.isEmpty());

        // Impact analysis doesn't depend on the answer text either — same reasoning
        // and pattern as businessFlowMono below: fire it now, on a background thread,
        // and tap the (likely-already-finished) result later as its own SSE event,
        // rather than blocking here and delaying the metadata event (and therefore
        // the first token) behind a potentially-slow Neo4j traversal. The Tech chat
        // view is still the only one that renders this inline, but Business-mode
        // messages need it too for the Change Impact Report export, so it's no
        // longer skipped by view mode — only by looksLikeChangeRequest(question),
        // same as before, so ordinary questions in either mode resolve to null
        // almost instantly without touching Neo4j or the LLM.
        Mono<ImpactAnalysis> impactAnalysisMono = Mono.fromCallable(() -> {
                    if (!looksLikeChangeRequest(question)) return null;
                    metrics.recordImpactAnalysisTriggered();
                    return impactAnalysisService.analyze(programIds);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .cache();
        impactAnalysisMono.subscribe(); // fire-and-forget: start the background work now

        // Business flow depends only on graphContext (not the answer text), so it can
        // start right away and run concurrently with the main answer's token stream —
        // by the time anything downstream actually asks for it, it's often already
        // done, hiding its latency (including its own LLM polish call) almost entirely.
        // .cache() means whichever subscriber asks for it later (or first, if it's
        // already finished) just reads the one computed result. Only the Business view
        // renders it, so Tech-mode questions skip the call entirely.
        Mono<String> businessFlowMono = !businessMode ? Mono.empty() : Mono.fromCallable(() -> {
                    BusinessFlow flow = businessInsightService.buildBusinessFlow(graphContext);
                    return flow == null ? null : toJson(Map.of("type", "businessFlow", "flow", flow));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .cache();
        businessFlowMono.subscribe(); // fire-and-forget: start the background work now

        List<SourceCitation> sources = toCitations(chunks);
        String contextBlock = buildContextBlock(chunks, graphContext);

        String userMessage = buildUserMessage(contextBlock, question);

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
        AtomicReference<Timer.Sample> answerStreamSample = new AtomicReference<>();
        Flux<String> tokenFlux = orderedStreamClient.streamText(
                businessMode ? BUSINESS_SYSTEM_PROMPT : SYSTEM_PROMPT, history, userMessage)
        .doOnSubscribe(sub -> answerStreamSample.set(metrics.startLlmCall()))
        .doFinally(signal -> {
            Timer.Sample sample = answerStreamSample.get();
            if (sample != null) metrics.stopLlmCall(sample, "answer_stream");
        })
        .mapNotNull(text -> {
            if (text == null || text.isEmpty()) return null;
            fullAnswer.append(text);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("type", "token");
            payload.put("content", text);
            return toJson(payload);
        });

        // Records the outcome (+ any security violation) exactly once, right after
        // the token stream finishes — independent of correctionFlux below, which
        // only runs when there's something to retract.
        Flux<String> metricsFlux = Flux.defer(() -> {
            String finalAnswer = fullAnswer.toString();
            String securityViolation = classifySecurityViolation(finalAnswer);
            metrics.recordAnswer(outcomeLabel(finalAnswer, securityViolation, !chunks.isEmpty()));
            if (securityViolation != null) {
                recordSecurityViolation(securityViolation, userId, question);
            }
            return Flux.empty();
        });

        // Event N+1: correction — only sent if the fully-streamed answer turned out to
        // be the out-of-scope fallback, which isn't known until every token has arrived.
        // Retracts any citations and graph relationships sent in the metadata event so
        // off-topic answers never display sources or key relationships, even though
        // retrieval necessarily ran before the LLM call.
        Flux<String> correctionFlux = Flux.defer(() -> {
            boolean hasSomethingToRetract = !sources.isEmpty() || !graphContext.isEmpty();
            if (!hasSomethingToRetract || !isSuppressedResponse(fullAnswer.toString(), !chunks.isEmpty())) {
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
            correction.put("scenarioTrace", null);
            return Flux.just(toJson(correction));
        });

        // Events N+2..5: follow-ups + business rules + decision table + data
        // dictionary — independent LLM calls, all grounded in the full answer, none
        // depending on the others' output. businessRules/decisionTable/technicalRules
        // are each only rendered in one view mode (see MessageBubble) — only compute
        // the ones the active mode will actually show; dataDictionary/followups
        // render in both, so they always run. Whichever apply run concurrently (each
        // offloaded to boundedElastic, Reactor's idiom for wrapping a blocking call)
        // and merge as each completes, rather than paying their latency one after
        // another — or, for the skipped ones, not at all.
        Flux<String> postAnswerInsightsFlux = Flux.defer(() -> {
            String answer = fullAnswer.toString();
            if (isSuppressedResponse(answer, !chunks.isEmpty())) {
                return Flux.empty();
            }

            Mono<String> followupMono = Mono.fromCallable(() -> {
                List<String> followUps = generateFollowUps(question, answer, contextBlock);
                return followUps.isEmpty() ? null : toJson(Map.of("type", "followups", "questions", followUps));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> businessRulesMono = !businessMode ? Mono.empty() : Mono.fromCallable(() -> {
                List<BusinessRule> rules = businessInsightService.extractBusinessRules(question, answer, contextBlock, chunks);
                return rules.isEmpty() ? null : toJson(Map.of("type", "businessRules", "rules", rules));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> decisionTableMono = !businessMode ? Mono.empty() : Mono.fromCallable(() -> {
                List<DecisionTableRow> rows = businessInsightService.extractDecisionTable(question, answer, contextBlock, chunks);
                return rows.isEmpty() ? null : toJson(Map.of("type", "decisionTable", "rows", rows));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> dataDictionaryMono = Mono.fromCallable(() -> {
                List<DataDictionaryEntry> entries = businessInsightService.extractDataDictionary(question, answer, contextBlock, chunks);
                return entries.isEmpty() ? null : toJson(Map.of("type", "dataDictionary", "entries", entries));
            }).subscribeOn(Schedulers.boundedElastic());

            Mono<String> technicalRulesMono = businessMode ? Mono.empty() : Mono.fromCallable(() -> {
                List<TechnicalRule> rules = businessInsightService.extractTechnicalRules(question, answer, contextBlock, chunks);
                return rules.isEmpty() ? null : toJson(Map.of("type", "technicalRules", "rules", rules));
            }).subscribeOn(Schedulers.boundedElastic());

            // Scenario Simulator: only for business-mode questions that actually read
            // like a concrete "what if" — see looksLikeScenarioQuestion. Ordinary
            // business questions never pay for this extra LLM call.
            Mono<String> scenarioTraceMono = (!businessMode || !looksLikeScenarioQuestion(question))
                    ? Mono.empty() : Mono.fromCallable(() -> {
                ScenarioTrace trace = businessInsightService.extractScenarioTrace(question, answer, contextBlock, chunks);
                return trace.steps().isEmpty() ? null : toJson(Map.of("type", "scenarioTrace", "trace", trace));
            }).subscribeOn(Schedulers.boundedElastic());

            return Flux.merge(followupMono, businessRulesMono, decisionTableMono, dataDictionaryMono,
                            technicalRulesMono, scenarioTraceMono)
                    .filter(Objects::nonNull);
        });

        // Event N+5: business flow — taps the mono kicked off back when graphContext
        // was first computed, so this is often instant by the time we get here.
        Flux<String> businessFlowFlux = Flux.defer(() -> isSuppressedResponse(fullAnswer.toString(), !chunks.isEmpty())
                ? Flux.empty()
                : businessFlowMono.flux().filter(Objects::nonNull));

        // Event N+6: impact analysis — taps the mono kicked off back when graphContext
        // was first computed (see impactAnalysisMono above), so this is usually
        // instant by the time we get here rather than delaying the whole response.
        // Now that the answer is known, narrow it to the specific field(s) it
        // actually discusses, if any were identified — one extra, cheap, bounded
        // Neo4j lookup on top of the already-computed unfiltered result. The
        // narrowed analyze() call is precise on purpose: when it finds nothing
        // field-relevant, that's the real answer (nothing else genuinely needs
        // this change), not a signal to fall back to the wider unfiltered list —
        // switchIfEmpty below only ever fires on a genuine failure (analyze()
        // throwing internally), as a resilience fallback, not a precision one.
        Flux<String> impactAnalysisFlux = Flux.defer(() -> {
            if (isSuppressedResponse(fullAnswer.toString(), !chunks.isEmpty())) {
                return Flux.empty();
            }
            // Same intent gate impactAnalysisMono itself applies internally — without
            // this check here too, a purely descriptive question whose answer happens
            // to name real fields (e.g. "what does surrender processing do?" naturally
            // mentioning PMR-COVERAGE-AMOUNT, WS-SURRENDER-CHARGE, etc.) would make
            // extractDiscussedFields non-empty and fall into the branch below, which
            // calls impactAnalysisService.analyze(...) directly — bypassing the gate
            // entirely and showing Impact Analysis for a question that never asked
            // about a change. ask()'s equivalent narrowing step avoids this because it
            // only runs when resolvedImpact is already non-null (i.e. the gate already
            // passed); this streaming branch needs the same condition made explicit.
            if (!looksLikeChangeRequest(question)) {
                return Flux.empty();
            }
            Set<String> fieldNames = extractDiscussedFields(chunks, fullAnswer.toString());
            Mono<ImpactAnalysis> resultMono = fieldNames.isEmpty()
                    ? impactAnalysisMono
                    : Mono.fromCallable(() -> impactAnalysisService.analyze(programIds, fieldNames))
                            .subscribeOn(Schedulers.boundedElastic())
                            .switchIfEmpty(impactAnalysisMono);
            return resultMono
                    .filter(Objects::nonNull)
                    .map(analysis -> toJson(Map.of("type", "impactAnalysis", "analysis", analysis)))
                    .flux();
        });

        // Final event: done signal
        Flux<String> doneFlux = Flux.just("[DONE]");

        return Flux.concat(metaFlux, tokenFlux, metricsFlux, correctionFlux,
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
        Timer.Sample sample = metrics.startLlmCall();
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
            metrics.recordLlmCallError("starter_suggestions");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "starter_suggestions");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * True if the answer should be treated as the canned out-of-scope fallback.
     * The LLM is instructed to return {@link #OUT_OF_SCOPE_MESSAGE} verbatim in
     * this case, but doesn't always comply — it sometimes improvises its own
     * short refusal instead (e.g. "I'm sorry, but I can't assist with that...").
     * Relying on exact string matching alone under-counts those as "in_scope" in
     * metrics/Grafana and leaves their (nonexistent) citations un-suppressed.
     * {@code hasRetrievedContext} is a reliable, LLM-phrasing-independent backstop:
     * per Answer Rule 1, there is no legitimate way to produce a genuine in-scope
     * answer when retrieval found nothing, so an empty retrieval always means
     * out-of-scope regardless of how the refusal was worded.
     */
    private boolean isOutOfScope(String answer, boolean hasRetrievedContext) {
        if (answer == null) return false;
        if (!hasRetrievedContext) return true;
        return answer.trim().equals(OUT_OF_SCOPE_MESSAGE.trim());
    }

    /** @return "prompt_injection", "pii_requested", "pii_provided", or null if the answer is a normal/out-of-scope response */
    private String classifySecurityViolation(String answer) {
        if (answer == null) return null;
        String trimmed = answer.trim();
        if (trimmed.equals(SECURITY_PROMPT_INJECTION_MESSAGE.trim())) return "prompt_injection";
        if (trimmed.equals(SECURITY_PII_REQUEST_MESSAGE.trim())) return "pii_requested";
        if (trimmed.equals(SECURITY_PII_PROVIDED_MESSAGE.trim())) return "pii_provided";
        return null;
    }

    /** @return "prompt_injection", "pii_provided", or null — see {@link SecurityPreFilter} */
    private String checkPreFilter(String question) {
        String violation = securityPreFilter.checkInjection(question);
        if (violation != null) return violation;
        return securityPreFilter.checkPiiProvided(question);
    }

    private String cannedSecurityMessage(String violationType) {
        return switch (violationType) {
            case "prompt_injection" -> SECURITY_PROMPT_INJECTION_MESSAGE;
            case "pii_requested" -> SECURITY_PII_REQUEST_MESSAGE;
            case "pii_provided" -> SECURITY_PII_PROVIDED_MESSAGE;
            default -> throw new IllegalArgumentException("Unknown violation type: " + violationType);
        };
    }

    /** Records the violation (metric + Postgres audit row) exactly once per detected request. */
    private void recordSecurityViolation(String violationType, String userId, String question) {
        metrics.recordSecurityViolation(violationType);
        securityEventStore.record(userId, question, violationType);
    }

    /** @return "in_scope", "out_of_scope", or "security_violation" */
    private String outcomeLabel(String answer, String securityViolation, boolean hasRetrievedContext) {
        if (securityViolation != null) return "security_violation";
        return isOutOfScope(answer, hasRetrievedContext) ? "out_of_scope" : "in_scope";
    }

    /** True for any canned special-case response (out-of-scope OR a security violation) that should suppress citations/extras. */
    private boolean isSuppressedResponse(String answer, boolean hasRetrievedContext) {
        return isOutOfScope(answer, hasRetrievedContext) || classifySecurityViolation(answer) != null;
    }

    private boolean looksLikeChangeRequest(String question) {
        if (question == null || !CHANGE_REQUEST_WORD.matcher(question).find()) {
            return false;
        }
        // "How does BNFUPD handle beneficiary updates?" matches "updates" as a
        // plain noun, not a request — suppress unless a stronger cue is present.
        if (DESCRIPTIVE_LEAD_IN.matcher(question).find() && !STRONG_CHANGE_SIGNAL.matcher(question).find()) {
            return false;
        }
        return true;
    }

    private boolean looksLikeScenarioQuestion(String question) {
        return question != null && SCENARIO_QUESTION_PATTERN.matcher(question).find();
    }

    /**
     * Runs the injection pre-check (see INJECTION_PRECHECK_PROMPT's Javadoc-style
     * comment above) and returns true only when it confidently says SAFE. Any
     * failure to call the model, a malformed/ambiguous response, or an explicit
     * INJECTION verdict all return false — the safe default of "don't add a
     * reassurance note," which just leaves the main prompt's own judgment
     * unassisted, exactly as it behaves today. This call can never make the app
     * LESS safe than before; it can only add a positive signal on top.
     */
    private boolean precheckSaysSafe(String question) {
        try {
            String prompt = INJECTION_PRECHECK_PROMPT.formatted(question);
            Timer.Sample sample = metrics.startLlmCall();
            String verdict;
            try {
                var response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
                verdict = response.getResult().getOutput().getText();
            } finally {
                metrics.stopLlmCall(sample, "injection_precheck");
            }
            return verdict != null && verdict.trim().toUpperCase().startsWith("SAFE");
        } catch (Exception e) {
            return false;
        }
    }

    /** Builds the main answer prompt's user message — context, the question, and
     * (only when the injection pre-check confidently clears it) a reassurance
     * note so the answer model doesn't independently re-litigate a question
     * that's already been confirmed genuine. See precheckSaysSafe(). */
    private String buildUserMessage(String contextBlock, String question) {
        String base = """
                %s

                Question: %s
                """.formatted(contextBlock, question);
        if (!precheckSaysSafe(question)) {
            return base;
        }
        return base + """

                [A separate automated pre-check already confirmed this specific question is a \
                genuine question or change-request about the codebase, not an attempt to \
                override these instructions — answer it normally per the Answer Rules; do not \
                classify it under Security Guidelines category 1.]
                """;
    }

    /**
     * Which specific COBOL field(s) the answer actually discusses — used to
     * narrow impact analysis from "structurally reachable" to "actually
     * references this field." Grounded in two independent signals rather than
     * guessing at the answer's business-language phrasing: a field only
     * qualifies if it was ingestion's own pick as one of a retrieved chunk's
     * key data fields AND the answer text literally names it, so a field that
     * merely appeared in a retrieved-but-irrelevant chunk doesn't count.
     */
    private Set<String> extractDiscussedFields(List<ChunkResult> chunks, String answer) {
        if (answer == null || answer.isBlank()) {
            return Set.of();
        }
        Set<String> discussed = new LinkedHashSet<>();

        // Primary signal: field-name-shaped tokens the answer itself names
        // directly — see FIELD_NAME_TOKEN's Javadoc for why this replaced
        // relying solely on the retrieved chunks' key_data_fields below.
        var matcher = FIELD_NAME_TOKEN.matcher(answer);
        while (matcher.find()) {
            discussed.add(matcher.group());
        }

        // Secondary: still worth keeping as a fallback for the rare case where a
        // chunk's key_data_fields entry doesn't match the FIELD_NAME_TOKEN shape
        // (e.g. contains characters the regex doesn't expect) but the answer
        // clearly discusses it in lowercase prose instead of quoting it verbatim.
        if (chunks != null && !chunks.isEmpty()) {
            String lowerAnswer = answer.toLowerCase();
            for (ChunkResult chunk : chunks) {
                if (chunk.keyDataFields() == null) continue;
                for (String field : chunk.keyDataFields()) {
                    if (field != null && !field.isBlank() && lowerAnswer.contains(field.toLowerCase())) {
                        discussed.add(field);
                    }
                }
            }
        }
        return discussed;
    }

    private List<String> generateFollowUps(String question, String answer, String contextBlock) {
        Timer.Sample sample = metrics.startLlmCall();
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
            metrics.recordLlmCallError("followups");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "followups");
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