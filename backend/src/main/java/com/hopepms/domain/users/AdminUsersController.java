package com.hopepms.domain.users;

import com.hopepms.domain.admin.AdminLogService;
import com.hopepms.security.HopePrincipal;
import com.hopepms.security.Owner;
import com.hopepms.security.RequiresRight;
import com.hopepms.util.ApiException;
import com.hopepms.util.StampHelper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {

    private final JdbcClient jdbc;
    private final AdminLogService audit;

    public AdminUsersController(JdbcClient jdbc, AdminLogService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * The everyday "Manage users" dashboard. SUPERADMIN accounts are never
     * listed here for anyone — they are governed only from the owner-exclusive
     * SUPERADMIN-control dashboard (see {@link #superadmins}).
     */
    @GetMapping
    @RequiresRight("ADM_USER")
    public List<Map<String, Object>> list(@AuthenticationPrincipal HopePrincipal me) {
        return jdbc.sql("""
                SELECT "userId", username, "firstName", "lastName", email,
                       user_type, record_status, stamp, created_at
                  FROM hopedb."user"
                 WHERE user_type <> 'SUPERADMIN'
                 ORDER BY created_at DESC
                """)
                .query(AdminUsersController::mapUser)
                .list();
    }

    /**
     * Owner-exclusive SUPERADMIN roster. Only the system owner (Owner.EMAIL)
     * may see or act on SUPERADMIN accounts; every other principal — including
     * other SUPERADMINs — gets 403.
     */
    @GetMapping("/superadmins")
    @RequiresRight("ADM_USER")
    public List<Map<String, Object>> superadmins(@AuthenticationPrincipal HopePrincipal me) {
        requireOwner(me, "Only the system owner can view SUPERADMIN accounts");
        return jdbc.sql("""
                SELECT "userId", username, "firstName", "lastName", email,
                       user_type, record_status, stamp, created_at
                  FROM hopedb."user"
                 WHERE user_type = 'SUPERADMIN'
                 ORDER BY created_at DESC
                """)
                .query(AdminUsersController::mapUser)
                .list();
    }

    @PostMapping("/{userId}/activate")
    @RequiresRight("ADM_USER")
    public Map<String, Object> activate(@AuthenticationPrincipal HopePrincipal me, @PathVariable String userId) {
        return setStatus(me, userId, "ACTIVE");
    }

    @PostMapping("/{userId}/deactivate")
    @RequiresRight("ADM_USER")
    public Map<String, Object> deactivate(@AuthenticationPrincipal HopePrincipal me, @PathVariable String userId) {
        return setStatus(me, userId, "INACTIVE");
    }

    /** SUPERADMIN provisions a new account; it reconciles by email at first Auth0 login. */
    @PostMapping
    @RequiresRight("ADM_USER")
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal HopePrincipal me,
                                      @Valid @RequestBody CreateUserRequest req) {
        requireSuperAdmin(me, "Only a SUPERADMIN can create accounts");
        String wantType = req.userType == null || req.userType.isBlank() ? "USER" : req.userType;
        if (!"USER".equals(wantType) && !"ADMIN".equals(wantType) && !"SUPERADMIN".equals(wantType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "userType must be USER, ADMIN or SUPERADMIN");
        }
        // Privilege ceiling: minting a SUPERADMIN is an escalation reserved
        // for the owner (Infra / IT Tech). A regular SUPERADMIN can only
        // create USER / ADMIN accounts.
        if ("SUPERADMIN".equals(wantType)) {
            requireOwner(me, "Only the system owner can create a SUPERADMIN");
        }

        Integer dupe = jdbc.sql("""
                SELECT 1 FROM hopedb."user"
                 WHERE LOWER(email) = LOWER(:e) OR LOWER(username) = LOWER(:u) LIMIT 1
                """)
                .param("e", req.email).param("u", req.username)
                .query(Integer.class).optional().orElse(null);
        if (dupe != null) {
            throw new ApiException(HttpStatus.CONFLICT, "conflict", "A user with that email or username already exists");
        }

        String newId = "seed|" + UUID.randomUUID().toString().replace("-", "");
        setCaller(me.userId());
        jdbc.sql("""
                INSERT INTO hopedb."user" ("userId", username, "firstName", "lastName", email,
                                            user_type, record_status, stamp)
                VALUES (:uid, :uname, :fn, :ln, :em, :ut, 'ACTIVE', :st)
                """)
                .param("uid", newId).param("uname", req.username)
                .param("fn", req.firstName).param("ln", req.lastName)
                .param("em", req.email).param("ut", wantType)
                .param("st", StampHelper.make("CREATED", me.userId()))
                .update();

        seedModules(newId);
        seedRights(newId, wantType);

        audit.record(me, "USER_CREATE", "@" + req.username,
                wantType + " · " + req.firstName + " " + req.lastName + " <" + req.email + ">");
        return Map.of("ok", true, "userId", newId, "userType", wantType);
    }

    @PostMapping("/{userId}/promote")
    @RequiresRight("ADM_USER")
    @Transactional
    public Map<String, Object> promote(@AuthenticationPrincipal HopePrincipal me, @PathVariable String userId) {
        return changeType(me, userId, true);
    }

    @PostMapping("/{userId}/demote")
    @RequiresRight("ADM_USER")
    @Transactional
    public Map<String, Object> demote(@AuthenticationPrincipal HopePrincipal me, @PathVariable String userId) {
        return changeType(me, userId, false);
    }

    /**
     * Hard-delete (off-board) an account. Their access rows are removed; any
     * audit stamp that pointed at them is rewritten so the record keeps the
     * person's full name but no longer leaks their username / opaque user id.
     *
     * <p>The V3 no-hard-delete guard would otherwise reject the
     * {@code DELETE FROM "user"} — this flow opts in for the "user" table only
     * via {@code hopepms.allow_user_delete} (see V9). Deleting a SUPERADMIN is
     * an owner-only action.
     */
    @DeleteMapping("/{userId}")
    @RequiresRight("ADM_USER")
    @Transactional
    public Map<String, Object> eradicate(@AuthenticationPrincipal HopePrincipal me, @PathVariable String userId) {
        requireSuperAdmin(me, "Only a SUPERADMIN can delete accounts");
        if (userId.equals(me.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "self", "You cannot delete your own account");
        }
        List<Map<String, Object>> hits = jdbc.sql("""
                SELECT "userId", username, "firstName", "lastName", user_type
                  FROM hopedb."user" WHERE "userId" = :uid
                """)
                .param("uid", userId)
                .query().listOfRows();
        if (hits.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found");
        }
        Map<String, Object> target = hits.get(0);

        if ("SUPERADMIN".equals(target.get("user_type"))) {
            requireOwner(me, "Only the system owner can delete a SUPERADMIN");
            Integer others = jdbc.sql("""
                    SELECT COUNT(*) FROM hopedb."user"
                     WHERE user_type = 'SUPERADMIN' AND "userId" <> :uid
                    """).param("uid", userId).query(Integer.class).single();
            if (others == null || others < 1) {
                throw new ApiException(HttpStatus.CONFLICT, "last_superadmin",
                        "Cannot delete the last SUPERADMIN");
            }
        }

        String fullName = ((String) target.getOrDefault("firstName", "")
                + " " + (String) target.getOrDefault("lastName", "")).trim();
        if (fullName.isBlank()) fullName = "Former user";
        String username = (String) target.getOrDefault("username", userId);

        setCaller(me.userId());
        allowUserDelete();

        // Keep provenance human-readable; strip the username / id from stamps.
        int scrubbedProducts = jdbc.sql("""
                UPDATE hopedb.product SET stamp = REPLACE(stamp, :uid, :name)
                 WHERE stamp LIKE '%' || :uid || '%'
                """).param("uid", userId).param("name", fullName).update();
        jdbc.sql("""
                UPDATE hopedb."priceHist" SET stamp = REPLACE(stamp, :uid, :name)
                 WHERE stamp LIKE '%' || :uid || '%'
                """).param("uid", userId).param("name", fullName).update();
        jdbc.sql("""
                UPDATE hopedb."user" SET stamp = REPLACE(stamp, :uid, :name)
                 WHERE stamp LIKE '%' || :uid || '%' AND "userId" <> :uid
                """).param("uid", userId).param("name", fullName).update();

        jdbc.sql("DELETE FROM hopedb.\"UserModule_Rights\" WHERE userid = :uid")
                .param("uid", userId).update();
        jdbc.sql("DELETE FROM hopedb.user_module WHERE userid = :uid")
                .param("uid", userId).update();
        int gone = jdbc.sql("DELETE FROM hopedb.\"user\" WHERE \"userId\" = :uid")
                .param("uid", userId).update();
        if (gone == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found");
        }

        audit.record(me, "USER_DELETE", "@" + username,
                fullName + " (" + target.get("user_type") + ") permanently deleted; "
                        + scrubbedProducts + " product stamp(s) rebound");
        return Map.of("ok", true, "userId", userId,
                "eradicated", true, "stampsRebound", scrubbedProducts);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @Transactional
    Map<String, Object> setStatus(HopePrincipal me, String userId, String next) {
        Map<String, Object> row = jdbc.sql("""
                SELECT username, user_type FROM hopedb."user" WHERE "userId" = :uid
                """)
                .param("uid", userId)
                .query(AdminUsersController::mapNameType)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found"));
        String targetType = (String) row.get("user_type");
        String username = (String) row.getOrDefault("username", userId);

        if ("SUPERADMIN".equals(targetType)) {
            requireOwner(me, "SUPERADMIN accounts can only be managed by the system owner");
        }

        setCaller(me.userId());

        int rows = jdbc.sql("""
                UPDATE hopedb."user"
                   SET record_status = :st,
                       stamp         = :s
                 WHERE "userId" = :uid
                """)
                .param("uid", userId)
                .param("st", next)
                .param("s", StampHelper.make("INACTIVE".equals(next) ? "DEACTIVATED" : "ACTIVATED", me.userId()))
                .update();

        if (rows == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found");
        }
        audit.record(me, "INACTIVE".equals(next) ? "USER_DEACTIVATE" : "USER_ACTIVATE",
                "@" + username, targetType + " set " + next);
        return Map.of("ok", true, "userId", userId, "recordStatus", next);
    }

    private Map<String, Object> changeType(HopePrincipal me, String userId, boolean up) {
        requireSuperAdmin(me, "Only a SUPERADMIN can change roles");
        Map<String, Object> row = jdbc.sql("""
                SELECT username, user_type FROM hopedb."user" WHERE "userId" = :uid
                """)
                .param("uid", userId)
                .query(AdminUsersController::mapNameType)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found"));
        String current = (String) row.get("user_type");
        String username = (String) row.getOrDefault("username", userId);

        String next = up ? nextUp(current) : nextDown(current);
        if (next.equals(current)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "noop",
                    up ? "User is already SUPERADMIN" : "User is already a USER");
        }

        // Privilege ceiling. Crossing the SUPERADMIN tier in either direction
        // is an escalation reserved for the owner (Infra / IT Tech). A regular
        // SUPERADMIN can only move accounts between USER and ADMIN.
        if ("SUPERADMIN".equals(next) || "SUPERADMIN".equals(current)) {
            requireOwner(me, "Only the system owner can grant or remove SUPERADMIN");
        }

        if (userId.equals(me.userId()) && "SUPERADMIN".equals(current) && !up) {
            Integer others = jdbc.sql("""
                    SELECT COUNT(*) FROM hopedb."user"
                     WHERE user_type = 'SUPERADMIN' AND "userId" <> :uid
                    """).param("uid", userId).query(Integer.class).single();
            if (others == null || others < 1) {
                throw new ApiException(HttpStatus.CONFLICT, "last_superadmin",
                        "Cannot demote the last SUPERADMIN");
            }
        }

        setCaller(me.userId());
        jdbc.sql("""
                UPDATE hopedb."user" SET user_type = :t, stamp = :s WHERE "userId" = :uid
                """)
                .param("t", next).param("uid", userId)
                .param("s", StampHelper.make(up ? "PROMOTED" : "DEMOTED", me.userId()))
                .update();
        seedRights(userId, next);
        audit.record(me, up ? "USER_PROMOTE" : "USER_DEMOTE",
                "@" + username, current + " → " + next);
        return Map.of("ok", true, "userId", userId, "userType", next);
    }

    private static Map<String, Object> mapUser(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("userId", rs.getString("userId"));
        r.put("username", rs.getString("username"));
        r.put("firstName", rs.getString("firstName"));
        r.put("lastName", rs.getString("lastName"));
        r.put("email", rs.getString("email"));
        r.put("userType", rs.getString("user_type"));
        r.put("recordStatus", rs.getString("record_status"));
        r.put("stamp", rs.getString("stamp"));
        return r;
    }

    private static Map<String, Object> mapNameType(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("username", rs.getString("username"));
        r.put("user_type", rs.getString("user_type"));
        return r;
    }

    private static String nextUp(String t) {
        return switch (t) { case "USER" -> "ADMIN"; case "ADMIN" -> "SUPERADMIN"; default -> "SUPERADMIN"; };
    }

    private static String nextDown(String t) {
        return switch (t) { case "SUPERADMIN" -> "ADMIN"; case "ADMIN" -> "USER"; default -> "USER"; };
    }

    private void requireSuperAdmin(HopePrincipal me, String msg) {
        if (!me.isSuperAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", msg);
        }
    }

    private void requireOwner(HopePrincipal me, String msg) {
        if (!Owner.is(me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", msg);
        }
    }

    private void setCaller(String callerId) {
        jdbc.sql("SELECT set_config('hopepms.caller_userid', :uid, true)")
                .param("uid", callerId)
                .query(String.class)
                .optional();
    }

    /** Opt this transaction in to the V9 guarded hard-delete of "user". */
    private void allowUserDelete() {
        jdbc.sql("SELECT set_config('hopepms.allow_user_delete', 'on', true)")
                .query(String.class)
                .optional();
    }

    private void seedModules(String uid) {
        List<Object[]> mods = List.of(
                new Object[]{"Prod_Mod", 1}, new Object[]{"Report_Mod", 1}, new Object[]{"Adm_Mod", 0});
        for (Object[] m : mods) {
            jdbc.sql("""
                    INSERT INTO hopedb.user_module (userid, "Module_ID", rights_value, record_status, stamp)
                    VALUES (:uid, :mid, :val, 'ACTIVE', 'AUTO')
                    ON CONFLICT (userid, "Module_ID") DO NOTHING
                    """)
                    .param("uid", uid).param("mid", m[0]).param("val", m[1]).update();
        }
    }

    /** Align fine-grained rights with the role tier so promote/demote is real. */
    private void seedRights(String uid, String userType) {
        boolean admin = "ADMIN".equals(userType) || "SUPERADMIN".equals(userType);
        boolean superA = "SUPERADMIN".equals(userType);
        List<Object[]> rights = List.of(
                new Object[]{"PRD_ADD", 1},
                new Object[]{"PRD_EDIT", 1},
                new Object[]{"PRD_DEL", admin ? 1 : 0},
                new Object[]{"REP_001", 1},
                new Object[]{"REP_002", superA ? 1 : 0},
                new Object[]{"ADM_USER", admin ? 1 : 0});
        for (Object[] r : rights) {
            jdbc.sql("""
                    INSERT INTO hopedb."UserModule_Rights" (userid, "Right_ID", "Right_value", "Record_status", "Stamp")
                    VALUES (:uid, :rid, :val, 'ACTIVE', 'AUTO')
                    ON CONFLICT (userid, "Right_ID")
                    DO UPDATE SET "Right_value" = EXCLUDED."Right_value", "Record_status" = 'ACTIVE'
                    """)
                    .param("uid", uid).param("rid", r[0]).param("val", r[1]).update();
        }
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$") String username,
            @NotBlank @Size(max = 49) String firstName,
            @NotBlank @Size(max = 49) String lastName,
            @NotBlank @Email @Size(max = 120) String email,
            String userType
    ) {}
}
