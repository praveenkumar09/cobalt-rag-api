package com.cobalt.rag.service;

import com.cobalt.rag.model.AskResponse;
import com.cobalt.rag.model.ChunkResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class RagService {

    private final VectorSearchService vectorSearch;
    private final GraphSearchService graphSearch;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── System Prompt ──────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT = """
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

            "I'm COBOL AI, specialized in analyzing life insurance COBOL/AS400 mainframe \
            codebases. I can answer questions about policy processing logic, surrender and \
            withdrawal flows, GIRO and premium collection, claims handling, fund management, \
            and the underlying COBOL programs that implement these processes. Your question \
            appears to be outside this domain — could you rephrase it in the context of \
            the life insurance codebase?"
            """;

    // Stopwords filtered out before sending keywords to the graph search
    private static final Pattern STOPWORD = Pattern.compile(
            "\\b(what|does|do|the|a|an|is|are|how|which|where|when|who|why|and|or|in|" +
            "on|of|to|for|with|this|that|it|its|can|will|has|have|be|been|being|by|" +
            "from|at|as|was|were|about|if|then|so|but|each|their|they|some|into)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public RagService(VectorSearchService vectorSearch,
                      GraphSearchService graphSearch,
                      ChatModel chatModel) {
        this.vectorSearch = vectorSearch;
        this.graphSearch  = graphSearch;
        this.chatModel    = chatModel;
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
        List<String> graphContext = graphSearch.findRelationships(programIds, keywords);

        // 4. Build augmented context block (vector + graph)
        String contextBlock = buildContextBlock(chunks, graphContext);

        // 5. Compose user message with context + question
        String userMessage = """
                %s

                Question: %s
                """.formatted(contextBlock, question);

        // 6. Call LLM
        var response = chatModel.call(
                new Prompt(List.of(
                        new SystemMessage(SYSTEM_PROMPT),
                        new UserMessage(userMessage)
                ))
        );

        String answer = response.getResult().getOutput().getText();

        List<String> sources = chunks.stream()
                .map(ChunkResult::sourceFile)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        return new AskResponse(answer, sources, graphContext, chunks.size());
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

        List<String> graphContext = graphSearch.findRelationships(programIds, extractKeywords(question));

        List<String> sources = chunks.stream()
                .map(ChunkResult::sourceFile)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        String userMessage = """
                %s

                Question: %s
                """.formatted(buildContextBlock(chunks, graphContext), question);

        // Event 1: metadata (sources + graph context arrive before the first token)
        Flux<String> metaFlux = Flux.just(toJson(Map.of(
                "type", "metadata",
                "sources", sources,
                "graphContext", graphContext,
                "chunksRetrieved", chunks.size()
        )));

        // Events 2..N: streamed LLM tokens
        Flux<String> tokenFlux = chatModel.stream(
                new Prompt(List.of(
                        new SystemMessage(SYSTEM_PROMPT),
                        new UserMessage(userMessage)
                ))
        )
        .mapNotNull(resp -> {
            String text = resp.getResult().getOutput().getText();
            if (text == null || text.isEmpty()) return null;
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("type", "token");
            payload.put("content", text);
            return toJson(payload);
        });

        // Final event: done signal
        Flux<String> doneFlux = Flux.just("[DONE]");

        return Flux.concat(metaFlux, tokenFlux, doneFlux);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String buildContextBlock(List<ChunkResult> chunks, List<String> graphContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== RETRIEVED CODE CHUNKS (Vector Search) ===\n");
        if (chunks.isEmpty()) {
            sb.append("No relevant chunks found.\n");
        } else {
            for (int i = 0; i < chunks.size(); i++) {
                ChunkResult c = chunks.get(i);
                sb.append("\n--- Chunk ").append(i + 1)
                  .append(" [similarity: ").append(String.format("%.2f", c.similarity())).append("] ---\n")
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
            graphContext.forEach(rel -> sb.append("  ").append(rel).append("\n"));
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