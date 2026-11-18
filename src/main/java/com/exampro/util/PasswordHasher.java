package com.exampro.util;

import com.exampro.config.AppConfig;
import org.mindrot.jbcrypt.BCrypt;

/**
 * BCrypt, with the cost factor pulled from config.
 *
 * The cost is a knob: 10 is comfortable on a lab machine, 12 is safer but
 * noticeably slower on older hardware, and seeding 23 accounts multiplies
 * whatever is chosen here.
 */
public final class PasswordHasher {

    private PasswordHasher() {}

    public static String hash(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(AppConfig.BCRYPT_ROUNDS));
    }

    /** False rather than an exception on a malformed stored hash. */
    public static boolean matches(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plaintext, storedHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
