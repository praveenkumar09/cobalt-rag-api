package com.cobalt.rag.model;

/** An LLM-generated, formal functional requirement document for one chat question, in Markdown. */
public record FunctionalRequirementResponse(String requirement) {
}
