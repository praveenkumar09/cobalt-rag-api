package com.cobalt.rag.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic, regex-based backstop checked BEFORE the LLM call — defense
 * in depth alongside the LLM's own "Security Guidelines" judgment in
 * {@link RagService#SYSTEM_PROMPT}. Unlike the LLM check, this can never be
 * argued out of its answer by clever phrasing, and it also saves a full
 * retrieval + LLM round-trip for the (common, scripted) obvious cases.
 *
 * Deliberately narrow in scope: it only catches well-known, high-confidence
 * patterns. It is NOT a replacement for the LLM's semantic judgment — novel
 * phrasing, and "is this question asking for PII" (which requires
 * understanding intent, not just pattern-matching the text), still rely on
 * the LLM. See docs/SECURITY_TESTING.md for the layered testing strategy
 * this backstop is one layer of.
 */
@Component
public class SecurityPreFilter {

    // Common jailbreak/injection phrasings. Deliberately conservative — false
    // positives here block a real question outright with no LLM fallback, so
    // each pattern targets a well-known, unambiguous attack phrase rather
    // than a broad keyword.
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(the\\s+)?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?(your\\s+)?(previous\\s+|prior\\s+)?instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(DAN|a\\s+different|an?\\s+unrestricted|an?\\s+jailbroken)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDAN\\b"), // "Do Anything Now" jailbreak persona — case-sensitive, all-caps only
            Pattern.compile("act\\s+as\\s+(an?\\s+)?(unrestricted|uncensored|jailbroken)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reveal\\s+(your|the)\\s+(system\\s+prompt|internal\\s+instructions)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(what|show me)\\s+(is|are)\\s+your\\s+(system\\s+prompt|instructions)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("repeat\\s+(everything|all)\\s+above", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer\\s+mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjailbreak\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsystem\\s*:\\s*\\S", Pattern.CASE_INSENSITIVE) // fake role-tag injection, e.g. "SYSTEM: ..."
    );

    // Singapore NRIC/FIN: letter + 7 digits + letter (e.g. S1234567D)
    private static final Pattern NRIC = Pattern.compile("\\b[STFGstfg]\\d{7}[A-Za-z]\\b");
    // US SSN: 123-45-6789
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    // Candidate card number: 13-19 digits, optionally grouped with spaces/dashes.
    // Confirmed with a Luhn checksum below to avoid false-positiving on long
    // policy/claim/account numbers, which don't pass Luhn.
    private static final Pattern CARD_NUMBER_CANDIDATE = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    public String checkInjection(String question) {
        if (question == null) return null;
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(question).find()) {
                return "prompt_injection";
            }
        }
        return null;
    }

    public String checkPiiProvided(String question) {
        if (question == null) return null;
        if (NRIC.matcher(question).find() || SSN.matcher(question).find()) {
            return "pii_provided";
        }
        var matcher = CARD_NUMBER_CANDIDATE.matcher(question);
        while (matcher.find()) {
            String digitsOnly = matcher.group().replaceAll("[^0-9]", "");
            if (passesLuhn(digitsOnly)) {
                return "pii_provided";
            }
        }
        return null;
    }

    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
