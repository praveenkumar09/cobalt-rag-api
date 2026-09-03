package com.cobalt.rag.model;

import java.util.List;

public record AskResponse(
        String answer,
        List<String> sources,
        List<String> graphContext,
        int chunksRetrieved
) {
}