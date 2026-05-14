package com.hopepms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "hopepms")
public record HopePmsProperties(
        Auth0 auth0,
        Session session,
        Cors cors
) {
    public record Auth0(
            String domain,
            String clientId,
            String clientSecret,
            String audience,
            String redirectUri,
            String logoutReturnTo
    ) {
        public String issuer() {
            return "https://" + domain + "/";
        }
        public String jwksUrl() {
            return "https://" + domain + "/.well-known/jwks.json";
        }
        public String tokenUrl() {
            return "https://" + domain + "/oauth/token";
        }
        public String authorizeUrl() {
            return "https://" + domain + "/authorize";
        }
        public String logoutUrl() {
            return "https://" + domain + "/v2/logout";
        }
    }

    public record Session(
            String cookieName,
            int ttlStandardMinutes,
            int ttlSuperadminMinutes,
            boolean cookieSecure
    ) {
        public Duration standardTtl()   { return Duration.ofMinutes(ttlStandardMinutes); }
        public Duration superadminTtl() { return Duration.ofMinutes(ttlSuperadminMinutes); }
    }

    public record Cors(List<String> allowedOrigins) {}
}
