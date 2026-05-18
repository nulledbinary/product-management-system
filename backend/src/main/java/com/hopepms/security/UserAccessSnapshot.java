package com.hopepms.security;

import java.util.Set;

/**
 * The live access state of a single account, resolved from the database (never
 * from the login-time session snapshot). This is what {@link VolatileSessionFilter}
 * builds the request {@link HopePrincipal} from on every request, so a
 * promote/demote/right-grant/deactivate/delete takes effect on the very next
 * request instead of waiting for the session TTL or a re-login.
 *
 * <p>{@code present} is {@code false} when the user row no longer exists
 * (hard-deleted). {@code recordStatus} is the {@code "user".record_status}
 * column — a value other than {@code ACTIVE} means the account was
 * deactivated and the live session must be torn down.
 */
public record UserAccessSnapshot(
        boolean present,
        String userId,
        String username,
        String email,
        String userType,
        String recordStatus,
        Set<String> rights
) {
    public boolean isActive() {
        return present && "ACTIVE".equals(recordStatus);
    }

    public static UserAccessSnapshot absent(String userId) {
        return new UserAccessSnapshot(false, userId, null, null, null, null, Set.of());
    }
}
