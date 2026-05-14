package com.hopepms.security;

import java.security.Principal;
import java.util.Set;

public record HopePrincipal(
        String userId,
        String username,
        String email,
        String userType,
        Set<String> rights
) implements Principal {

    @Override
    public String getName() {
        return userId;
    }

    public boolean isSuperAdmin() { return "SUPERADMIN".equals(userType); }
    public boolean isAdmin()      { return "ADMIN".equals(userType) || isSuperAdmin(); }
    public boolean hasRight(String rightId) { return rights.contains(rightId); }
}
