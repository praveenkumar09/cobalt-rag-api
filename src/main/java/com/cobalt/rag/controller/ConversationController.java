package com.cobalt.rag.controller;

import com.cobalt.rag.model.ConversationDetail;
import com.cobalt.rag.model.ConversationListResponse;
import com.cobalt.rag.model.ConversationSummary;
import com.cobalt.rag.model.SelectBranchRequest;
import com.cobalt.rag.model.UpsertMessageRequest;
import com.cobalt.rag.service.AuthStore;
import com.cobalt.rag.service.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Persistent chat history, scoped by the authenticated user resolved from the
 * X-Session-Token header (see {@link AuthStore}) — not by browser identity.
 * See {@link ConversationStore} for the 1-day expiry-after-last-activity policy.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationStore store;
    private final AuthStore authStore;

    public ConversationController(ConversationStore store, AuthStore authStore) {
        this.store = store;
        this.authStore = authStore;
    }

    /**
     * PUT /api/conversations/{conversationId}/messages/{messageId}
     *
     * Upserts one message. Creates the conversation row on first use (title
     * derived from the first user message). Using PUT/upsert rather than
     * POST/append means a regenerated assistant message (same messageId)
     * naturally overwrites its prior stored content instead of duplicating it.
     */
    @PutMapping(value = "/{conversationId}/messages/{messageId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> upsertMessage(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @PathVariable String conversationId,
            @PathVariable String messageId,
            @RequestBody UpsertMessageRequest request) {
        try {
            String userId = authStore.requireUserId(token);
            store.upsertMessage(userId, conversationId, messageId, request.role(), request.content(),
                    request.payload(), request.parentId(), request.viewMode());
            return ResponseEntity.ok().build();
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /** GET /api/conversations?limit=10&offset=0 — paginated, most recently active first. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationListResponse> list(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            String userId = authStore.requireUserId(token);
            List<ConversationSummary> conversations = store.listConversations(userId, limit, offset);
            boolean hasMore = store.hasMore(userId, limit, offset);
            return ResponseEntity.ok(new ConversationListResponse(conversations, hasMore));
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /** GET /api/conversations/{id} — full message list, for restoring a past conversation. */
    @GetMapping(value = "/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationDetail> get(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @PathVariable String conversationId) {
        try {
            String userId = authStore.requireUserId(token);
            return store.getConversation(userId, conversationId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /**
     * POST /api/conversations/{conversationId}/select-branch { "messageId": "..." }
     *
     * Switches the conversation's active path so it runs through messageId,
     * without adding any new message — used when the user picks a sibling
     * branch via the prev/next navigation on a message with multiple children.
     */
    @PostMapping(value = "/{conversationId}/select-branch", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationDetail> selectBranch(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @PathVariable String conversationId,
            @RequestBody SelectBranchRequest request) {
        try {
            String userId = authStore.requireUserId(token);
            return store.selectBranch(userId, conversationId, request.messageId())
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /** DELETE /api/conversations/{id} */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @PathVariable String conversationId) {
        try {
            String userId = authStore.requireUserId(token);
            boolean deleted = store.deleteConversation(userId, conversationId);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
