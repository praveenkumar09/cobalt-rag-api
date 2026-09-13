package com.cobalt.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record ConversationMessageDto(
        String id,
        String role,
        String content,
        JsonNode payload,
        Instant createdAt,
        String parentId,
        List<String> siblingIds,
        int siblingIndex
) {
}
