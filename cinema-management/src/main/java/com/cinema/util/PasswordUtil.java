package com.cinema.util;

import org.mindrot.jbcrypt.BCrypt;

/** BCrypt-based password utility for hashing and verification. */
public final class PasswordUtil {
    private static final int BCRYPT_ROUNDS = 12;

    private PasswordUtil() {}

    /**
     * Hash a plain-text password using BCrypt.
     * @param plainPassword the password to hash
     * @return BCrypt hash
     */
    public static String hash(String plainPassword) {
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    /**
     * Verify a plain-text password against a BCrypt hash.
     * @param plainPassword the plain-text password to check
     * @param hash the stored BCrypt hash
     * @return true if password matches, false otherwise
     */
    public static boolean verify(String plainPassword, String hash) {
        if (plainPassword == null || plainPassword.isBlank() || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, hash);
        } catch (Exception e) {
            return false; // Invalid hash format
        }
    }

    /**
     * Validate password policy: minimum length 8, must contain at least 1 digit and 1 letter.
     * @param password the password to validate
     * @return true if password meets policy, false otherwise
     */
    public static boolean isStrong(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        boolean hasLetter = password.matches(".*[a-zA-Z].*");
        boolean hasDigit = password.matches(".*\\d.*");
        return hasLetter && hasDigit;
    }
}
