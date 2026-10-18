package com.cinema.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** HMAC-SHA256 (JDK) cho callback thanh toán mock (design.md Security). */
public final class HmacUtil {
    private static final String ALGORITHM = "HmacSHA256";
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacUtil() { }

    /** Cryptographically random token encoded as hex (used for CSRF / reset tokens). */
    public static String randomToken() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return toHex(buf);
    }

    /** HMAC-SHA256 of {@code payload} using a constant internal secret.
     *  Sufficient for short-lived, low-privilege reset tokens; the signature
     *  is verified server-side via {@link #verify(String, String)} against the
     *  same constant secret. */
    public static String sign(String payload) {
        return sign("cinema-hmac-secret-do-not-use-in-prod", payload);
    }

    public static boolean verify(String payload, String providedHmac) {
        return verify("cinema-hmac-secret-do-not-use-in-prod", payload, providedHmac);
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** Tính HMAC-SHA256 hex của payload với secret. */
    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 không khả dụng", e);
        }
    }

    /** So khớp constant-time chống timing attack. */
    public static boolean verify(String secret, String payload, String providedHmac) {
        if (providedHmac == null || providedHmac.isBlank()) return false;
        byte[] expected = sign(secret, payload).getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedHmac.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }
}
