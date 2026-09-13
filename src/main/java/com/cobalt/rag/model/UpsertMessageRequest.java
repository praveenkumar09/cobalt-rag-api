package com.cobalt.rag.model;

import com.fasterxml.jackson.databind.JsonNode;

public record UpsertMessageRequest(String role, String content, JsonNode payload, String parentId) {
}
