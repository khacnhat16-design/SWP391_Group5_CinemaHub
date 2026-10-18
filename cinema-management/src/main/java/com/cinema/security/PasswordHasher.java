package com.cinema.security;

import org.mindrot.jbcrypt.BCrypt;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordHasher() {}

    public static String hash(String plain) {
        return BCrypt.hashpw(plain, BCrypt.gensalt(12));
    }

    public static boolean verify(String plain, String hash) {
        return BCrypt.checkpw(plain, hash);
    }

    public static String newCsrfToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static boolean passwordMeetsPolicy(String plain) {
        if (plain == null || plain.length() < 8) return false;
        boolean hasLetter = false, hasDigit = false;
        for (char c : plain.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }
}
