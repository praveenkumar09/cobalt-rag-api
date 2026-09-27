package com.cobalt.rag.model;

/** An LLM-generated QA test-scenario document for one chat question, in Markdown. */
public record TestScenariosResponse(String scenarios) {
}
