package com.hopepms.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * RFC 7636 PKCE helpers.
 *
 * The verifier is stored server-side in Redis under a one-shot key keyed by
 * the OAuth `state` parameter; the challenge is sent to Auth0 in the
 * /authorize redirect. Auth0's token endpoint then verifies the verifier
 * we send back during the code exchange.
 */
public final class PkceUtil {

    private static final SecureRandom RNG = new SecureRandom();

    private PkceUtil() {}

    public static String randomState() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    public static String randomVerifier() {
        byte[] buf = new byte[48];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
