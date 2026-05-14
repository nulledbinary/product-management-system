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

@Component
public class VolatileSessionFilter extends OncePerRequestFilter {

    private final VolatileSessionStore store;
    private final HopePmsProperties props;

    public VolatileSessionFilter(VolatileSessionStore store, HopePmsProperties props) {
        this.store = store;
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
                List<SimpleGrantedAuthority> authorities = record.rights().stream()
                        .map(r -> new SimpleGrantedAuthority("RIGHT_" + r))
                        .toList();

                HopePrincipal principal = new HopePrincipal(
                        record.userId(), record.username(), record.email(),
                        record.userType(), record.rights()
                );

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);

                store.touch(record);
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
