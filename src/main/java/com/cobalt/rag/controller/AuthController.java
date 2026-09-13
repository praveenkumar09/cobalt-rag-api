package com.cobalt.rag.controller;

import com.cobalt.rag.model.AuthRequest;
import com.cobalt.rag.model.AuthResponse;
import com.cobalt.rag.model.ResetPasswordRequest;
import com.cobalt.rag.service.AuthStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MIN_PASSWORD_LENGTH = 5;

    private final AuthStore authStore;

    public AuthController(AuthStore authStore) {
        this.authStore = authStore;
    }

    @PostMapping(value = "/signup", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> signup(@RequestBody AuthRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        try {
            String token = authStore.signup(request.email(), request.password());
            return ResponseEntity.ok(new AuthResponse(token, normalizedEmail(request.email())));
        } catch (AuthStore.EmailAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "An account with that email already exists"));
        }
    }

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> login(@RequestBody AuthRequest request) {
        try {
            String token = authStore.login(request.email(), request.password());
            return ResponseEntity.ok(new AuthResponse(token, normalizedEmail(request.email())));
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password"));
        }
    }

    /**
     * Resets by email alone — no ownership verification (no email sent, no
     * token). Deliberately simple, per the explicit product ask; see
     * {@link AuthStore} Javadoc for the trade-off this implies.
     */
    @PostMapping(value = "/reset-password", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resetPassword(@RequestBody ResetPasswordRequest request) {
        if (request.newPassword() == null || !request.newPassword().equals(request.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Passwords do not match"));
        }
        if (request.newPassword().length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        try {
            authStore.resetPassword(request.email(), request.newPassword());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (AuthStore.UserNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No account found with that email"));
        }
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> me(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        Optional<String> email = authStore.resolveUserId(token).flatMap(authStore::emailForUser);
        if (email.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(Map.of("email", email.get()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        if (token != null) {
            authStore.logout(token);
        }
        return ResponseEntity.ok().build();
    }

    private String normalizedEmail(String email) {
        return email.trim().toLowerCase();
    }
}
