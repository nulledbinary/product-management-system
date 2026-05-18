package com.hopepms.security;

import com.hopepms.config.HopePmsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class VolatileSessionFilter extends OncePerRequestFilter {

    private final VolatileSessionStore store;
    private final UserAccessService access;
    private final HopePmsProperties props;

    public VolatileSessionFilter(VolatileSessionStore store, UserAccessService access, HopePmsProperties props) {
        this.store = store;
        this.access = access;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        Optional<String> sid = readSessionId(req);
        if (sid.isPresent()) {
            Optional<SessionRecord> opt = store.lookup(sid.get());
            if (opt.isPresent()) {
                SessionRecord record = opt.get();

                // Authorization state is resolved LIVE from the per-user ACL
                // (cache → DB) so a promote/demote/right-grant takes effect on
                // this very request and a deactivated/deleted account is torn
                // down here instead of lingering until the session TTL.
                //
                // Crucially this fails OPEN, not closed. snapshot() returns:
                //   • a non-null snapshot → AUTHORITATIVE (good DB read, or a
                //     cache entry derived from one). Honour it: tear the
                //     session down iff it is not active.
                //   • null → the live state could NOT be resolved (DB threw /
                //     unreachable / indeterminate). This is NOT evidence the
                //     account is invalid, so we must NOT log the user out over
                //     infrastructure noise — fall back to the login-time
                //     session snapshot exactly as the pre-real-time filter did.
                //     The only cost is that a rights change is delayed until
                //     the DB is reachable again (the documented, acceptable
                //     degradation), never a spurious logout.
                UserAccessSnapshot snap = access.snapshot(record.userId());

                if (snap == null) {
                    // Unresolved → keep the existing session, build the
                    // principal from its frozen snapshot, slide the TTL.
                    authenticate(record.userId(), record.username(), record.email(),
                            record.userType(), record.rights());
                    store.touch(record);
                } else if (!snap.isActive()) {
                    // Authoritative: deactivated (record_status != ACTIVE) or
                    // row gone. Kill the server session + cookie and leave the
                    // request unauthenticated so any protected endpoint 401s
                    // and the SPA reconciler bounces them to /login.
                    store.invalidate(sid.get());
                    clearCookie(res);
                } else {
                    authenticate(snap.userId(), snap.username(), snap.email(),
                            snap.userType(), snap.rights());
                    store.touch(record);
                }
            } else {
                clearCookie(res);
            }
        }

        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Install the request {@link HopePrincipal} + authorities from a resolved
     *  (live or session-snapshot) identity. Same shape either way. */
    private void authenticate(String userId, String username, String email,
                              String userType, Set<String> rights) {
        List<SimpleGrantedAuthority> authorities = rights.stream()
                .map(r -> new SimpleGrantedAuthority("RIGHT_" + r))
                .toList();
        HopePrincipal principal = new HopePrincipal(userId, username, email, userType, rights);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Optional<String> readSessionId(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> props.session().cookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && v.matches("^[a-f0-9]{64}$"))
                .findFirst();
    }

    private void clearCookie(HttpServletResponse res) {
        res.addHeader("Set-Cookie", buildClearedCookie(props.session().cookieName(), props.session().cookieSecure()));
    }

    public static String buildSessionCookie(String name, String value, boolean secure) {
        return name + "=" + value
                + "; Path=/"
                + "; HttpOnly"
                + (secure ? "; Secure" : "")
                + "; SameSite=Strict";
    }

    public static String buildClearedCookie(String name, boolean secure) {
        return name + "="
                + "; Path=/"
                + "; HttpOnly"
                + (secure ? "; Secure" : "")
                + "; SameSite=Strict"
                + "; Max-Age=0";
    }
}
