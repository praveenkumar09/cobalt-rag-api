package com.cobalt.rag.model;

import java.util.List;

public record ConversationDetail(
        String id,
        String title,
        List<ConversationMessageDto> messages
) {
}
