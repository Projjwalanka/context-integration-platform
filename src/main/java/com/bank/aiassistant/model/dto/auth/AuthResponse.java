package com.bank.aiassistant.model.dto.auth;

import java.util.List;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String userId,
        String email,
        String fullName,
        List<String> roles
) {
    public static AuthResponse of(String accessToken, String refreshToken,
                                   long expiresIn, String userId,
                                   String email, String fullName, List<String> roles) {
        return new AuthResponse(accessToken, refreshToken, "Bearer",
                expiresIn, userId, email, fullName, roles);
    }
}
