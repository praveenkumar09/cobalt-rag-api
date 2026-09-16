package com.cobalt.rag.model;

/** Body of POST /api/feedback — a "Suggest improvement" note tied to one assistant answer. */
public record FeedbackRequest(String question, String answer, String message) {
}
