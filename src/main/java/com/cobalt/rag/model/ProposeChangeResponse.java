package com.cobalt.rag.model;

/** An LLM-proposed modified version of a program's source, grounded in its real current source. */
public record ProposeChangeResponse(String programId, String proposedSource) {
}
