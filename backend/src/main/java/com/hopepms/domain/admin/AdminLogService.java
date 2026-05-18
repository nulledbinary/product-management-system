package com.hopepms.domain.admin;

import com.hopepms.security.HopePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only administrative activity log.
 *
 * <p>Writes run in their own REQUIRES_NEW transaction so an audit-insert
 * failure can never roll back (or be rolled back with) the business action it
 * is recording — the log is best-effort and must not become a new failure
 * mode for "Delete user" / "Add product".
 */
@Service
public class AdminLogService {

    private static final Logger log = LoggerFactory.getLogger(AdminLogService.class);

    private final JdbcClient jdbc;

    public AdminLogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HopePrincipal actor, String action, String target, String detail) {
        try {
            String actorId = actor == null ? null : actor.userId();
            String actorName = actor == null ? null : actor.username();
            String actorEmail = actor == null ? null : actor.email();
            jdbc.sql("""
                    INSERT INTO hopedb.admin_log
                           (actor_id, actor_name, actor_email, action, target, detail)
                    VALUES (:aid, :an, :ae, :ac, :tg, :dt)
                    """)
                    .param("aid", clip(actorId, 64))
                    .param("an", clip(actorName, 120))
                    .param("ae", clip(actorEmail, 320))
                    .param("ac", clip(action, 40))
                    .param("tg", clip(target, 160))
                    .param("dt", clip(detail, 500))
                    .update();
        } catch (RuntimeException ex) {
            // Never let auditing break the action it audits.
            log.warn("admin_log write failed for action={} target={}: {}", action, target, ex.toString());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(int limit) {
        int n = Math.max(1, Math.min(500, limit));
        return jdbc.sql("""
                SELECT id, at, actor_id, actor_name, actor_email, action, target, detail
                  FROM hopedb.admin_log
                 ORDER BY at DESC, id DESC
                 LIMIT :n
                """)
                .param("n", n)
                .query((rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", rs.getLong("id"));
                    r.put("at", String.valueOf(rs.getObject("at")));
                    r.put("actorId", rs.getString("actor_id"));
                    r.put("actorName", rs.getString("actor_name"));
                    r.put("actorEmail", rs.getString("actor_email"));
                    r.put("action", rs.getString("action"));
                    r.put("target", rs.getString("target"));
                    r.put("detail", rs.getString("detail"));
                    return r;
                })
                .list();
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
