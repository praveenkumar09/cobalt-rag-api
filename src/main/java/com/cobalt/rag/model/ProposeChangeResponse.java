package com.cobalt.rag.model;

import java.util.List;

/**
 * An LLM-proposed modified version of a program's source, grounded in its real
 * current source. {@code steps} is the agent's "thinking" narration — locate →
 * generate → splice for a large file, or a single fast-path note for a small
 * one — shown in the UI regardless of response mode (see CodeChangeService's
 * Javadoc). {@code error} is non-null (and {@code proposedSource} null) only
 * when the agent could not produce a change at all — e.g. it couldn't
 * confidently locate which section of a large file to touch.
 */
public record ProposeChangeResponse(String programId, String proposedSource, String error, List<String> steps) {
    public ProposeChangeResponse(String programId, String proposedSource, List<String> steps) {
        this(programId, proposedSource, null, steps);
    }
}
