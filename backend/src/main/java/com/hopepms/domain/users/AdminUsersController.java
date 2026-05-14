package com.hopepms.domain.users;

import com.hopepms.security.HopePrincipal;
import com.hopepms.security.RequiresRight;
import com.hopepms.util.ApiException;
import com.hopepms.util.StampHelper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // Set the caller_userid GUC so the DB trigger can double-check.
        jdbc.sql("SELECT set_config('hopepms.caller_userid', :uid, true)")
                .param("uid", me.userId())
                .query(String.class)
                .optional();

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
}
