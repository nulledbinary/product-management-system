package com.hopepms.domain.users;

import com.hopepms.security.HopePrincipal;
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

    public AdminUsersController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @RequiresRight("ADM_USER")
    public List<Map<String, Object>> list(@AuthenticationPrincipal HopePrincipal me) {
        return jdbc.sql("""
                SELECT "userId", username, "firstName", "lastName", email,
                       user_type, record_status, stamp, created_at
                  FROM hopedb."user"
                 ORDER BY created_at DESC
                """)
                .query((rs, n) -> {
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
                })
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
     * Hard-delete (eradicate) an account for off-boarding. Their access rows
     * are removed; any audit stamp that pointed at them is rewritten so the
     * record keeps the person's full name but no longer leaks their
     * username / opaque user id.
     */
    @DeleteMapping("/{userId}")
    @RequiresRight("ADM_USER")
    @Transactional
    public Map<String, Object> eradicate(@AuthenticationPrincipal HopePrincipal me, @PathVariable String userId) {
        requireSuperAdmin(me, "Only a SUPERADMIN can eradicate accounts");
        if (userId.equals(me.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "self", "You cannot eradicate your own account");
        }
        List<Map<String, Object>> hits = jdbc.sql("""
                SELECT "userId", "firstName", "lastName", user_type
                  FROM hopedb."user" WHERE "userId" = :uid
                """)
                .param("uid", userId)
                .query().listOfRows();
        if (hits.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found");
        }
        Map<String, Object> target = hits.get(0);

        if ("SUPERADMIN".equals(target.get("user_type"))) {
            Integer others = jdbc.sql("""
                    SELECT COUNT(*) FROM hopedb."user"
                     WHERE user_type = 'SUPERADMIN' AND "userId" <> :uid
                    """).param("uid", userId).query(Integer.class).single();
            if (others == null || others < 1) {
                throw new ApiException(HttpStatus.CONFLICT, "last_superadmin",
                        "Cannot eradicate the last SUPERADMIN");
            }
        }

        String fullName = ((String) target.getOrDefault("firstName", "")
                + " " + (String) target.getOrDefault("lastName", "")).trim();
        if (fullName.isBlank()) fullName = "Former user";

        setCaller(me.userId());

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

        return Map.of("ok", true, "userId", userId,
                "eradicated", true, "stampsRebound", scrubbedProducts);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @Transactional
    Map<String, Object> setStatus(HopePrincipal me, String userId, String next) {
        String targetType = jdbc.sql("SELECT user_type FROM hopedb.\"user\" WHERE \"userId\" = :uid")
                .param("uid", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found"));

        if ("SUPERADMIN".equals(targetType) && !me.isSuperAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "SUPERADMIN accounts cannot be modified");
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
        return Map.of("ok", true, "userId", userId, "recordStatus", next);
    }

    private Map<String, Object> changeType(HopePrincipal me, String userId, boolean up) {
        requireSuperAdmin(me, "Only a SUPERADMIN can change roles");
        String current = jdbc.sql("SELECT user_type FROM hopedb.\"user\" WHERE \"userId\" = :uid")
                .param("uid", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "User not found"));

        String next = up ? nextUp(current) : nextDown(current);
        if (next.equals(current)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "noop",
                    up ? "User is already SUPERADMIN" : "User is already a USER");
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
        return Map.of("ok", true, "userId", userId, "userType", next);
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

    private void setCaller(String callerId) {
        jdbc.sql("SELECT set_config('hopepms.caller_userid', :uid, true)")
                .param("uid", callerId)
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
