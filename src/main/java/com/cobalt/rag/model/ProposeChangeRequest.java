package com.cobalt.rag.model;

/** The original chat Q&amp;A that described the recommended code change, used to ground the proposal. */
public record ProposeChangeRequest(String question, String answer) {
}
