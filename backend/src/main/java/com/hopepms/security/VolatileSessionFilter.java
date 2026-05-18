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
                // this very request — WITHOUT the request itself ever ending
                // the session. A live session is terminated by exactly two
                // things: an explicit logout, or the inactivity/TTL timeout.
                // Nothing on this hot path destroys it. That is the product
                // contract: "make the changes live, but the session stays
                // intact unless logged out or timed out."
                //
                // snapshot() outcomes:
                //   • active snapshot   → rebuild the principal from it (new
                //     role/rights apply now) and slide the TTL.
                //   • null (DB threw / unreachable / indeterminate) → fail
                //     OPEN: rebuild from the login-time session snapshot and
                //     slide the TTL, exactly as the pre-real-time filter did.
                //     A rights change is merely delayed, never a logout.
                //   • not-active (deactivated, or row reported gone) → withhold
                //     ALL authority for this request so protected endpoints
                //     401, but DO NOT invalidate the store session or clear
                //     the cookie and DO NOT slide the TTL. A genuine
                //     deactivation thus stops working immediately (no rights)
                //     yet is routed by the normal SPA flow rather than an
                //     abrupt mid-request cookie nuke; and a *spurious*
                //     not-active (the documented clean-DB / trigger / blip
                //     fragility) silently self-heals on the very next request
                //     once the row resolves ACTIVE again — the user never
                //     notices, instead of being permanently logged out.
                UserAccessSnapshot snap = access.snapshot(record.userId());

                if (snap == null) {
                    authenticate(record.userId(), record.username(), record.email(),
                            record.userType(), record.rights());
                    store.touch(record);
                } else if (snap.isActive()) {
                    authenticate(snap.userId(), snap.username(), snap.email(),
                            snap.userType(), snap.rights());
                    store.touch(record);
                }
                // else: not-active → leave the request unauthenticated, but
                // keep the session + cookie. Recoverable, never an abrupt
                // logout; explicit sign-out / timeout remain the only enders.
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
