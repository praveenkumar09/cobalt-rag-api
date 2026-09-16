package com.cobalt.rag.model;

import java.time.Instant;

/** One stored "Suggest improvement" note, for display on the /admin page. */
public record FeedbackEntry(String id, String userEmail, String question, String answerSnippet,
                             String message, Instant createdAt) {
}
