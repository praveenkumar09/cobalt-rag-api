package com.cobalt.rag.model;

import java.util.List;

public record ConversationListResponse(
        List<ConversationSummary> conversations,
        boolean hasMore
) {
}
