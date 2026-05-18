package com.hopepms.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopepms.auth.ProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Source of truth for "what can this user do, right now".
 *
 * <p>Why this exists: the login-time {@link SessionRecord} froze {@code userType}
 * and {@code rights} into Redis. {@link VolatileSessionFilter} used to rebuild the
 * principal from that frozen snapshot, so an admin promoting/demoting someone — or
 * deactivating/deleting them — had no effect on that person's already-open
 * session until the TTL expired or they logged in again. Role changes were, in
 * effect, session-scoped and static.
 *
 * <p>This service makes them live without abandoning the Redis-first design the
 * codebase deliberately chose. It keeps a small per-user ACL cache at
 * {@code hpms:acl:{userId}} that mirrors {@link ProvisioningService#loadOrNull}
 * exactly (same tables, same rights SQL as login — parity guaranteed). The cache
 * carries a short TTL purely as a self-healing safety net; the real freshness
 * mechanism is event-driven: {@link UserAccessChangedListener} evicts the key the
 * instant a role/status/right change commits (see
 * {@link UserAccessChangedEvent}). On a cache miss the snapshot is rebuilt from
 * the database, so the very next request after a change observes the new state.
 *
 * <p>Redis is treated as best-effort: any cache failure falls straight through to
 * the database. Authorization must never break because the cache hiccuped.
 */
@Service
public class UserAccessService {

    private static final Logger log = LoggerFactory.getLogger(UserAccessService.class);

    private static final String KEY_PREFIX = "hpms:acl:";
    /** Safety-net only — the listener evicts on every real change. */
    private static final Duration PRESENT_TTL = Duration.ofSeconds(120);
    /** A deleted/deactivated user is torn down by the filter on first hit;
     *  a brief negative cache just absorbs any stale-cookie retry storm. */
    private static final Duration ABSENT_TTL = Duration.ofSeconds(15);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ProvisioningService provisioning;

    public UserAccessService(StringRedisTemplate redis, ObjectMapper json, ProvisioningService provisioning) {
        this.redis = redis;
        this.json = json;
        this.provisioning = provisioning;
    }

    /** Live access state for {@code userId}: cache → DB on miss. Never throws. */
    public UserAccessSnapshot snapshot(String userId) {
        UserAccessSnapshot cached = readCache(userId);
        if (cached != null) return cached;

        UserAccessSnapshot fresh = loadFromDb(userId);
        writeCache(fresh);
        return fresh;
    }

    /** Drop the cached ACL so the next request rebuilds it from the database. */
    public void evict(String userId) {
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (RuntimeException ex) {
            // A failed evict only means the change is visible after PRESENT_TTL
            // instead of immediately — still correct, just not instant.
            log.warn("ACL cache evict failed for user {}: {}", userId, ex.toString());
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private UserAccessSnapshot loadFromDb(String userId) {
        ProvisioningService.UserAccount acc = provisioning.loadOrNull(userId);
        if (acc == null) return UserAccessSnapshot.absent(userId);
        return new UserAccessSnapshot(
                true, acc.userId(), acc.username(), acc.email(),
                acc.userType(), acc.recordStatus(), Set.copyOf(acc.rights())
        );
    }

    private UserAccessSnapshot readCache(String userId) {
        try {
            String raw = redis.opsForValue().get(KEY_PREFIX + userId);
            if (raw == null) return null;
            Map<String, Object> m = json.readValue(raw, new TypeReference<>() {});
            boolean present = Boolean.TRUE.equals(m.get("present"));
            if (!present) return UserAccessSnapshot.absent(userId);
            @SuppressWarnings("unchecked")
            Set<String> rights = Set.copyOf((Collection<String>) m.getOrDefault("rights", Set.of()));
            return new UserAccessSnapshot(
                    true,
                    (String) m.get("userId"),
                    (String) m.get("username"),
                    (String) m.get("email"),
                    (String) m.get("userType"),
                    (String) m.get("recordStatus"),
                    rights
            );
        } catch (Exception ex) {
            log.warn("ACL cache read failed for user {} — falling back to DB: {}", userId, ex.toString());
            return null;
        }
    }

    private void writeCache(UserAccessSnapshot s) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("present", s.present());
            m.put("userId", s.userId());
            m.put("username", s.username() == null ? "" : s.username());
            m.put("email", s.email() == null ? "" : s.email());
            m.put("userType", s.userType());
            m.put("recordStatus", s.recordStatus());
            m.put("rights", s.rights());
            redis.opsForValue().set(
                    KEY_PREFIX + s.userId(),
                    json.writeValueAsString(m),
                    s.present() ? PRESENT_TTL : ABSENT_TTL
            );
        } catch (Exception ex) {
            // Purely a cache-fill optimisation; the DB path already returned the
            // correct snapshot, so swallowing this changes nothing functionally.
            log.warn("ACL cache write failed for user {}: {}", s.userId(), ex.toString());
        }
    }
}
