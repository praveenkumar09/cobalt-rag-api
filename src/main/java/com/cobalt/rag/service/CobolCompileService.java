package com.cobalt.rag.service;

import com.cobalt.rag.model.ProgramSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shells out to a real GnuCOBOL compiler (the {@code cobc} binary installed
 * in the runtime image — see the Dockerfile) to syntax-check a proposed
 * change, instead of trusting the LLM's own claim that it produced valid
 * COBOL.
 *
 * <p>These insurance test programs target IBM AS/400-style COBOL and were
 * never run through a real compiler when they were authored — in practice
 * EVERY one of them already has pre-existing compiler diagnostics that have
 * nothing to do with any specific change (a stray line past the
 * continuation column, a condition-name used somewhere GnuCOBOL wants a
 * literal, a PICTURE-clause quirk, and so on). Blaming a proposed change for
 * those would send the auto-fix loop chasing errors it didn't cause and has
 * no realistic way to fix. So {@link Session} always compiles the ORIGINAL
 * source once as a baseline, and a candidate is only considered to have
 * "failed" when it produces a compiler error whose message text (line
 * number aside) isn't already present in that baseline — the same idea as a
 * CI linter run in "diff mode" against main instead of failing every PR for
 * issues that were already there.
 */
@Service
public class CobolCompileService {

    private static final Pattern COPY_STATEMENT = Pattern.compile("(?i)\\bCOPY\\s+([A-Z0-9][A-Z0-9-]*)\\b");
    // "PROGRAM.cbl:123: error: message text" / "COPYBOOK.CPY:45: warning: ..."
    // — only "error" diagnostics affect success; warnings (e.g. GnuCOBOL's
    // "line not terminated by a newline") are noise for this purpose.
    private static final Pattern DIAGNOSTIC_LINE = Pattern.compile("^[^:]+:(\\d+):\\s*(error|warning):\\s*(.*)$");
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(45);

    private final VectorSearchService vectorSearch;

    public CobolCompileService(VectorSearchService vectorSearch) {
        this.vectorSearch = vectorSearch;
    }

    public record CompileResult(boolean success, List<String> newErrors, int preExistingIgnored, String infraError) {
        static CompileResult unavailable(String reason) {
            return new CompileResult(true, List.of(), 0, reason);
        }
    }

    private record Diagnostic(String raw, String fingerprint) {
    }

    /**
     * One compile-verification session for a single propose-change request:
     * writes the program's copybooks to a shared scratch directory once,
     * compiles the untouched original source once as the baseline, then lets
     * the caller {@link #verify} any number of candidate rewrites against
     * that same baseline — reused across the auto-fix retry loop so only the
     * changing candidate is ever recompiled, never the unchanged original.
     * Always {@link #close} it (try-with-resources) to clean up the scratch
     * directory.
     */
    public final class Session implements AutoCloseable {
        private final Path dir;
        private final String programId;
        private final Set<String> baselineFingerprints;
        private final String infraError;

        private Session(Path dir, String programId, Set<String> baselineFingerprints, String infraError) {
            this.dir = dir;
            this.programId = programId;
            this.baselineFingerprints = baselineFingerprints;
            this.infraError = infraError;
        }

        public CompileResult verify(String candidateSource) {
            if (infraError != null) {
                return CompileResult.unavailable(infraError);
            }
            try {
                List<Diagnostic> diagnostics = compile(dir, programId, candidateSource);
                List<String> newErrors = diagnostics.stream()
                        .filter(d -> !baselineFingerprints.contains(d.fingerprint()))
                        .map(Diagnostic::raw)
                        .distinct()
                        .toList();
                return new CompileResult(newErrors.isEmpty(), newErrors, diagnostics.size() - newErrors.size(), null);
            } catch (Exception e) {
                return CompileResult.unavailable(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            deleteRecursively(dir);
        }
    }

    public Session startSession(String programId, String originalSource) {
        try {
            Path dir = Files.createTempDirectory("cobol-compile-");
            Set<String> copybookNames = extractCopybookNames(originalSource);
            writeCopybooks(dir, copybookNames);
            List<Diagnostic> baseline = compile(dir, programId, originalSource);
            Set<String> fingerprints = baseline.stream().map(Diagnostic::fingerprint).collect(Collectors.toSet());
            return new Session(dir, programId, fingerprints, null);
        } catch (Exception e) {
            return new Session(null, programId, Set.of(), e.getMessage() != null ? e.getMessage() : "compiler unavailable");
        }
    }

    private void writeCopybooks(Path dir, Set<String> initialNames) throws IOException {
        Set<String> resolved = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(initialNames);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (name == null || !resolved.add(name)) continue;
            Optional<ProgramSource> src = vectorSearch.fetchFullSource(name);
            if (src.isEmpty()) continue;
            Files.writeString(dir.resolve(name + ".cpy"), src.get().content(), StandardCharsets.UTF_8);
            for (String nested : extractCopybookNames(src.get().content())) {
                if (!resolved.contains(nested)) queue.offer(nested);
            }
        }
    }

    private Set<String> extractCopybookNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        if (source == null) return names;
        Matcher m = COPY_STATEMENT.matcher(source);
        while (m.find()) names.add(m.group(1).toUpperCase());
        return names;
    }

    private List<Diagnostic> compile(Path dir, String programId, String source) throws IOException, InterruptedException {
        Path srcFile = dir.resolve(programId + ".cbl");
        Files.writeString(srcFile, source, StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(
                "cobc", "-fsyntax-only", "-std=ibm", "-I", dir.toString(), srcFile.toString());
        pb.redirectErrorStream(true);
        pb.directory(dir.toFile());
        Process proc = pb.start();
        String output;
        try (var in = proc.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = proc.waitFor(COMPILE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("cobc timed out after " + COMPILE_TIMEOUT.toSeconds() + "s");
        }

        // cobc reports diagnostics with the full scratch-dir path ahead of the
        // filename (e.g. "/tmp/cobol-compile-.../PREMINQ.cbl:114: error: ...") —
        // strip that internal detail before it's ever shown to a user or fed
        // back into a fix prompt.
        String dirPrefix = dir.toString() + java.io.File.separator;
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith(dirPrefix)) {
                line = line.substring(dirPrefix.length());
            }
            Matcher m = DIAGNOSTIC_LINE.matcher(line);
            if (m.matches() && "error".equals(m.group(2))) {
                diagnostics.add(new Diagnostic(line, m.group(3).trim()));
            }
        }
        return diagnostics;
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a scratch temp dir
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of a scratch temp dir
        }
    }
}
