package com.cobalt.rag.model;

import java.time.Instant;

public record ConversationSummary(
        String id,
        String title,
        Instant lastActiveAt,
        int messageCount
) {
}
