package com.hopepms.auth;

import com.hopepms.util.StampHelper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On first sign-in, provisions a user row + default module/rights grants.
 *
 * Mirrors the auth.users INSERT trigger from the original guide (Section 4.4),
 * but executed in Java so we keep all writes inside a Spring-managed
 * transaction. The user is created as USER/INACTIVE — an ADMIN/SUPERADMIN
 * must then activate them via the Admin module.
 */
@Service
public class ProvisioningService {

    private final JdbcClient jdbc;

    public ProvisioningService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record UserAccount(
            String userId, String username, String email,
            String userType, String recordStatus, Set<String> rights
    ) {}

    @Transactional
    public UserAccount provisionOrLoad(String auth0Sub, String email,
                                       String fallbackUsername, String firstName, String lastName) {
        UserAccount existing = loadOrNull(auth0Sub);
        if (existing != null) return existing;

        // Reconcile against the email-seeded SUPERADMIN row (or any pre-existing
        // row with the same email but a placeholder userId).
        if (email != null && !email.isBlank()) {
            String seededUserId = jdbc.sql("""
                    SELECT "userId" FROM hopedb."user" WHERE LOWER(email) = LOWER(:email) LIMIT 1
                    """)
                    .param("email", email)
                    .query(String.class)
                    .optional()
                    .orElse(null);

            if (seededUserId != null && !seededUserId.equals(auth0Sub)) {
                rebindUserId(seededUserId, auth0Sub);
                UserAccount reconciled = loadOrNull(auth0Sub);
                if (reconciled != null) return reconciled;
            }
        }

        String username = (fallbackUsername == null || fallbackUsername.isBlank())
                ? (email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : auth0Sub)
                : fallbackUsername;
        String fName = firstName == null ? "" : firstName;
        String lName = lastName == null ? "" : lastName;

        jdbc.sql("""
                INSERT INTO hopedb."user" ("userId", username, "firstName", "lastName", email,
                                            user_type, record_status, stamp)
                VALUES (:uid, :uname, :fname, :lname, :email, 'USER', 'INACTIVE', :stamp)
                ON CONFLICT ("userId") DO NOTHING
                """)
                .param("uid", auth0Sub)
                .param("uname", username)
                .param("fname", fName)
                .param("lname", lName)
                .param("email", email == null ? "" : email)
                .param("stamp", StampHelper.make("REGISTERED", auth0Sub))
                .update();

        seedDefaultModules(auth0Sub);
        seedDefaultRights(auth0Sub);

        return loadOrNull(auth0Sub);
    }

    /**
     * Re-key the seed row (and its module/right rows) onto the real Auth0 sub.
     * Used exactly once per seeded SUPERADMIN at first sign-in.
     */
    private void rebindUserId(String oldId, String newId) {
        // Sidestep the SUPERADMIN protection trigger: this is a controlled bootstrap.
        jdbc.sql("SELECT set_config('hopepms.caller_userid', :uid, true)")
                .param("uid", oldId)
                .query(String.class)
                .optional();
        jdbc.sql("""
                UPDATE hopedb."UserModule_Rights" SET userid = :nid WHERE userid = :oid
                """).param("nid", newId).param("oid", oldId).update();
        jdbc.sql("""
                UPDATE hopedb.user_module SET userid = :nid WHERE userid = :oid
                """).param("nid", newId).param("oid", oldId).update();
        jdbc.sql("""
                UPDATE hopedb."user" SET "userId" = :nid WHERE "userId" = :oid
                """).param("nid", newId).param("oid", oldId).update();
    }

    public UserAccount loadOrNull(String auth0Sub) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT "userId", username, email, user_type, record_status
                  FROM hopedb."user"
                 WHERE "userId" = :uid
                """)
                .param("uid", auth0Sub)
                .query()
                .listOfRows();
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);

        Set<String> rights = new HashSet<>(jdbc.sql("""
                SELECT umr."Right_ID"
                  FROM hopedb."UserModule_Rights" umr
                  JOIN hopedb."rights" rt ON rt."Right_ID" = umr."Right_ID"
                 WHERE umr.userid = :uid
                   AND umr."Right_value" = 1
                   AND umr."Record_status" = 'ACTIVE'
                   AND rt.record_status = 'ACTIVE'
                """)
                .param("uid", auth0Sub)
                .query(String.class)
                .list());

        return new UserAccount(
                (String) r.get("userId"),
                (String) r.get("username"),
                (String) r.get("email"),
                (String) r.get("user_type"),
                (String) r.get("record_status"),
                rights
        );
    }

    private void seedDefaultModules(String uid) {
        List<String[]> defaults = List.of(
                new String[]{"Prod_Mod",   "1"},
                new String[]{"Report_Mod", "1"},
                new String[]{"Adm_Mod",    "0"}
        );
        for (String[] d : defaults) {
            jdbc.sql("""
                    INSERT INTO hopedb.user_module (userid, "Module_ID", rights_value, record_status, stamp)
                    VALUES (:uid, :mid, :val, 'ACTIVE', 'AUTO')
                    ON CONFLICT (userid, "Module_ID") DO NOTHING
                    """)
                    .param("uid", uid)
                    .param("mid", d[0])
                    .param("val", Integer.parseInt(d[1]))
                    .update();
        }
    }

    private void seedDefaultRights(String uid) {
        List<String[]> defaults = List.of(
                new String[]{"PRD_ADD",  "1"},
                new String[]{"PRD_EDIT", "1"},
                new String[]{"PRD_DEL",  "0"},
                new String[]{"REP_001",  "1"},
                new String[]{"REP_002",  "0"},
                new String[]{"ADM_USER", "0"}
        );
        for (String[] d : defaults) {
            jdbc.sql("""
                    INSERT INTO hopedb."UserModule_Rights" (userid, "Right_ID", "Right_value", "Record_status", "Stamp")
                    VALUES (:uid, :rid, :val, 'ACTIVE', 'AUTO')
                    ON CONFLICT (userid, "Right_ID") DO NOTHING
                    """)
                    .param("uid", uid)
                    .param("rid", d[0])
                    .param("val", Integer.parseInt(d[1]))
                    .update();
        }
    }
}
