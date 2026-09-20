package com.cinema.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Session-bound CSRF token utility for state-changing requests. */
public final class CsrfUtil {
    public static final String SESSION_ATTRIBUTE = "csrfToken";
    public static final String PARAMETER = "_csrf";
    public static final String HEADER = "X-CSRF-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfUtil() { }

    public static String token(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object existing = session.getAttribute(SESSION_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) return value;
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SESSION_ATTRIBUTE, generated);
        return generated;
    }

    public static boolean matches(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        Object expected = session.getAttribute(SESSION_ATTRIBUTE);
        String supplied = request.getHeader(HEADER);
        if (supplied == null || supplied.isBlank()) supplied = request.getParameter(PARAMETER);
        return expected instanceof String && supplied != null &&
                MessageDigest.isEqual(((String) expected).getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8));
    }
}
