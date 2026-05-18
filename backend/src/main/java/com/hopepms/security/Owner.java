package com.hopepms.security;

/**
 * The single system owner ("Infra / IT Tech" escalation authority).
 *
 * <p>This identity has every bypass for SUPERADMIN governance: only the owner
 * may mint, demote, deactivate or delete a SUPERADMIN, and the owner is the
 * only principal that sees the exclusive SUPERADMIN-control dashboard. Regular
 * ADMIN/SUPERADMIN operators can manage USER/ADMIN accounts but can never
 * escalate anyone to (or strip) the SUPERADMIN tier.
 *
 * <p>Kept as an in-code security constant rather than DB/config on purpose:
 * it is a defence anchor (peer to the V3 trigger guards), must not be
 * editable through the very admin surface it governs, and the grant itself
 * lives out-of-repo in prod (see project memory: boris SUPERADMIN grant).
 */
public final class Owner {

    /** Personal email of the system owner; matched case-insensitively. */
    public static final String EMAIL = "borisgamaliel.duque@neu.edu.ph";

    private Owner() {}

    public static boolean is(HopePrincipal p) {
        return p != null && is(p.email());
    }

    public static boolean is(String email) {
        return email != null && EMAIL.equalsIgnoreCase(email.trim());
    }
}
