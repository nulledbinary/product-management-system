package com.hopepms.security;

import java.time.Instant;
import java.util.Set;

public record SessionRecord(
        String sessionId,
        String userId,
        String username,
        String email,
        String userType,
        Set<String> rights,
        Instant issuedAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
