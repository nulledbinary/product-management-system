package com.hopepms.auth;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.hopepms.config.HopePmsProperties;
import com.hopepms.security.HopePrincipal;
import com.hopepms.security.Owner;
import com.hopepms.security.SessionRecord;
import com.hopepms.security.VolatileSessionFilter;
import com.hopepms.security.VolatileSessionStore;
import com.hopepms.util.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final Auth0Service auth0;
    private final AuthFlowStore flowStore;
    private final ProvisioningService provisioning;
    private final VolatileSessionStore sessions;
    private final HopePmsProperties props;

    public AuthController(
            Auth0Service auth0,
            AuthFlowStore flowStore,
            ProvisioningService provisioning,
            VolatileSessionStore sessions,
            HopePmsProperties props
    ) {
        this.auth0 = auth0;
        this.flowStore = flowStore;
        this.provisioning = provisioning;
        this.sessions = sessions;
        this.props = props;
    }

    /** Step 1 — frontend hits this; we 302 to Auth0 with a PKCE challenge. */
    @GetMapping("/start")
    public ResponseEntity<Void> start(
            @RequestParam(value = "connection", required = false) String connection,
            @RequestParam(value = "login_hint", required = false) String loginHint,
            @RequestParam(value = "screen_hint", required = false) String screenHint,
            @RequestParam(value = "returnTo", required = false) String returnTo
    ) {
        String state = PkceUtil.randomState();
        String verifier = PkceUtil.randomVerifier();
        String challenge = PkceUtil.challengeFor(verifier);

        flowStore.save(state, verifier, sanitizeReturnTo(returnTo));
        URI authorize = auth0.buildAuthorizeUri(state, challenge, connection, loginHint, screenHint);

        return ResponseEntity.status(HttpStatus.FOUND).location(authorize).build();
    }

    /** Step 2 — Auth0 calls this with ?code=…&state=…; we exchange + set cookie. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam("state") String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletResponse res
    ) {
        if (error != null) {
            log.warn("Auth0 redirected to /callback with error={} description={}", error, errorDescription);
            flowStore.consume(state);
            URI to = URI.create(props.auth0().logoutReturnTo() + "?reason=auth_failed");
            return ResponseEntity.status(HttpStatus.FOUND).location(to).build();
        }
        if (code == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_code", "Authorization code missing");
        }
        AuthFlowStore.FlowEntry entry = flowStore.consume(state)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_state", "Login state expired or invalid"));

        DecodedJWT claims = auth0.exchangeCode(code, entry.verifier());

        String sub = claims.getSubject();
        String email = claims.getClaim("email").asString();
        String fullName = claims.getClaim("name").asString();
        String givenName = claims.getClaim("given_name").asString();
        String familyName = claims.getClaim("family_name").asString();
        String preferredUsername = claims.getClaim("preferred_username").asString();

        ProvisioningService.UserAccount account = provisioning.provisionOrLoad(
                sub, email, preferredUsername != null ? preferredUsername : fullName, givenName, familyName
        );

        if (!"ACTIVE".equals(account.recordStatus())) {
            URI to = URI.create(props.auth0().logoutReturnTo() + "?reason=not_activated");
            return ResponseEntity.status(HttpStatus.FOUND).location(to).build();
        }

        SessionRecord session = sessions.issue(
                account.userId(), account.username(), account.email(),
                account.userType(), account.rights()
        );

        res.addHeader(HttpHeaders.SET_COOKIE, VolatileSessionFilter.buildSessionCookie(
                props.session().cookieName(), session.sessionId(), props.session().cookieSecure()
        ));

        URI to = URI.create(rootOf(props.auth0().logoutReturnTo()) + safe(entry.returnTo()));
        return ResponseEntity.status(HttpStatus.FOUND).location(to).build();
    }

    /** Authenticated read of the current session, including rights — used by the frontend bootstrap. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal HopePrincipal principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "No active session");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", principal.userId());
        body.put("username", principal.username());
        body.put("email", principal.email());
        body.put("userType", principal.userType());
        body.put("rights", principal.rights());
        body.put("owner", Owner.is(principal));
        return ResponseEntity.ok(body);
    }

    /** Explicit logout — clears server state and cookie, returns Auth0 logout URL for client redirect. */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest req, HttpServletResponse res) {
        readSid(req).ifPresent(sessions::invalidate);
        res.addHeader(HttpHeaders.SET_COOKIE,
                VolatileSessionFilter.buildClearedCookie(props.session().cookieName(), props.session().cookieSecure()));
        return ResponseEntity.ok(Map.of("logoutUrl", auth0.buildLogoutUri().toString()));
    }

    /** Best-effort invalidation on tab close (navigator.sendBeacon). Public to avoid auth churn. */
    @PostMapping("/invalidate")
    public ResponseEntity<Void> invalidate(HttpServletRequest req, HttpServletResponse res) {
        readSid(req).ifPresent(sessions::invalidate);
        res.addHeader(HttpHeaders.SET_COOKIE,
                VolatileSessionFilter.buildClearedCookie(props.session().cookieName(), props.session().cookieSecure()));
        return ResponseEntity.noContent().build();
    }

    private Optional<String> readSid(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> props.session().cookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private static String sanitizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/") || returnTo.startsWith("//")) {
            return "/products";
        }
        return returnTo;
    }

    private static String rootOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String path) {
        return (path == null || path.isBlank()) ? "/products" : path;
    }
}
