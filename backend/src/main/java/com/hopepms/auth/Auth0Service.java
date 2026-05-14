package com.hopepms.auth;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopepms.config.HopePmsProperties;
import com.hopepms.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.TimeUnit;

@Service
public class Auth0Service {

    private final HopePmsProperties props;
    private final RestClient http;
    private final JwkProvider jwks;
    private final ObjectMapper json;

    public Auth0Service(HopePmsProperties props, ObjectMapper json) {
        this.props = props;
        this.http = RestClient.create();
        this.json = json;
        this.jwks = new JwkProviderBuilder(props.auth0().domain())
                .cached(20, 1, TimeUnit.HOURS)
                .rateLimited(20, 1, TimeUnit.MINUTES)
                .build();
    }

    /** Build the Authorize redirect URL (Authorization Code + PKCE). */
    public URI buildAuthorizeUri(String state, String pkceChallenge, String connection, String loginHint, String screenHint) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(props.auth0().authorizeUrl())
                .queryParam("response_type", "code")
                .queryParam("client_id", props.auth0().clientId())
                .queryParam("redirect_uri", props.auth0().redirectUri())
                .queryParam("scope", "openid profile email")
                .queryParam("audience", props.auth0().audience())
                .queryParam("state", state)
                .queryParam("code_challenge", pkceChallenge)
                .queryParam("code_challenge_method", "S256");
        if (connection != null && !connection.isBlank()) b.queryParam("connection", connection);
        if (loginHint  != null && !loginHint.isBlank())  b.queryParam("login_hint", loginHint);
        if (screenHint != null && !screenHint.isBlank()) b.queryParam("screen_hint", screenHint);
        return b.build(true).toUri();
    }

    /** Exchange auth code for an id token. Returns the verified ID-token claims. */
    public DecodedJWT exchangeCode(String code, String pkceVerifier) {
        String body = "grant_type=authorization_code"
                + "&client_id=" + url(props.auth0().clientId())
                + "&client_secret=" + url(props.auth0().clientSecret())
                + "&code=" + url(code)
                + "&code_verifier=" + url(pkceVerifier)
                + "&redirect_uri=" + url(props.auth0().redirectUri());

        String response;
        try {
            response = http.post()
                    .uri(props.auth0().tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "auth0_token_failed", "Token exchange failed");
        }

        try {
            JsonNode node = json.readTree(response);
            String idToken = node.path("id_token").asText(null);
            if (idToken == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "auth0_no_id_token", "Auth0 did not return id_token");
            }
            return verifyIdToken(idToken);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "auth0_bad_response", "Invalid token response");
        }
    }

    public DecodedJWT verifyIdToken(String idToken) {
        try {
            DecodedJWT unverified = JWT.decode(idToken);
            String kid = unverified.getKeyId();
            RSAPublicKey publicKey = (RSAPublicKey) jwks.get(kid).getPublicKey();
            Algorithm alg = Algorithm.RSA256(publicKey, null);

            return JWT.require(alg)
                    .withIssuer(props.auth0().issuer())
                    .withAudience(props.auth0().clientId())
                    .acceptLeeway(30)
                    .build()
                    .verify(idToken);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "id_token_invalid", "Invalid or expired Auth0 token");
        }
    }

    public URI buildLogoutUri() {
        return UriComponentsBuilder.fromHttpUrl(props.auth0().logoutUrl())
                .queryParam("client_id", props.auth0().clientId())
                .queryParam("returnTo", props.auth0().logoutReturnTo())
                .build(true).toUri();
    }

    private static String url(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
