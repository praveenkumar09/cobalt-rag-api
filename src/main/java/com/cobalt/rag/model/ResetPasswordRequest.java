package com.cobalt.rag.model;

public record ResetPasswordRequest(String email, String newPassword, String confirmPassword) {
}
