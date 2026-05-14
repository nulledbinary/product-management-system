package com.hopepms.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopepms.config.HopePmsProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side session store backed by Redis (ElastiCache in prod).
 *
 * Why Redis and not the JWT pattern: the directive requires server-side TTL
 * with different windows for SUPERADMIN vs. standard users, and immediate
 * server-side invalidation on logout/refresh. Stateless JWTs can't be revoked
 * without a denylist — at which point you're round-tripping to a store anyway.
 *
 * Session IDs are 256-bit cryptographically random hex strings. They are
 * opaque to the client and meaningless without the Redis lookup.
 */
@Component
public class VolatileSessionStore {

    private static final String KEY_PREFIX = "hpms:sess:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final HopePmsProperties props;
    private final SecureRandom rng = new SecureRandom();

    public VolatileSessionStore(StringRedisTemplate redis, ObjectMapper json, HopePmsProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public SessionRecord issue(
            String userId, String username, String email,
            String userType, Set<String> rights
    ) {
        byte[] buf = new byte[32];
        rng.nextBytes(buf);
        String sid = HexFormat.of().formatHex(buf);

        Duration ttl = "SUPERADMIN".equals(userType)
                ? props.session().superadminTtl()
                : props.session().standardTtl();

        Instant now = Instant.now();
        SessionRecord record = new SessionRecord(
                sid, userId, username, email, userType, rights, now, now.plus(ttl)
        );
        persist(record, ttl);
        return record;
    }

    public Optional<SessionRecord> lookup(String sessionId) {
        String raw = redis.opsForValue().get(KEY_PREFIX + sessionId);
        if (raw == null) return Optional.empty();
        try {
            Map<String, Object> m = json.readValue(raw, new TypeReference<>() {});
            Instant issued = Instant.parse((String) m.get("issuedAt"));
            Instant expires = Instant.parse((String) m.get("expiresAt"));
            @SuppressWarnings("unchecked")
            Set<String> rights = Set.copyOf((java.util.Collection<String>) m.get("rights"));

            SessionRecord record = new SessionRecord(
                    sessionId,
                    (String) m.get("userId"),
                    (String) m.get("username"),
                    (String) m.get("email"),
                    (String) m.get("userType"),
                    rights,
                    issued,
                    expires
            );
            if (record.isExpired()) {
                invalidate(sessionId);
                return Optional.empty();
            }
            return Optional.of(record);
        } catch (Exception e) {
            invalidate(sessionId);
            return Optional.empty();
        }
    }

    public void invalidate(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
    }

    /**
     * Sliding-window touch: USER/ADMIN sessions extend by their standard TTL
     * on each request. SUPERADMIN sessions DO NOT slide — they expire at
     * `issuedAt + superadminTtl` no matter what (privileged-credential window).
     */
    public void touch(SessionRecord record) {
        if ("SUPERADMIN".equals(record.userType())) return;
        Duration ttl = props.session().standardTtl();
        Instant newExpiry = Instant.now().plus(ttl);
        SessionRecord rolled = new SessionRecord(
                record.sessionId(), record.userId(), record.username(),
                record.email(), record.userType(), record.rights(),
                record.issuedAt(), newExpiry
        );
        persist(rolled, ttl);
    }

    private void persist(SessionRecord r, Duration ttl) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "userId", r.userId(),
                    "username", r.username(),
                    "email", r.email() == null ? "" : r.email(),
                    "userType", r.userType(),
                    "rights", r.rights(),
                    "issuedAt", r.issuedAt().toString(),
                    "expiresAt", r.expiresAt().toString()
            ));
            redis.opsForValue().set(KEY_PREFIX + r.sessionId(), payload, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist session", e);
        }
    }
}
