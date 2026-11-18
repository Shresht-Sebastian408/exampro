package com.exampro.service;

import com.exampro.config.AppConfig;
import com.exampro.dao.UserDao;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.exception.AccountLockedException;
import com.exampro.exception.AuthenticationException;
import com.exampro.exception.InvalidCredentialsException;
import com.exampro.exception.SessionExpiredException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Role;
import com.exampro.util.PasswordHasher;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sign in, sign out, and who is signed in now.
 *
 * Two rules worth stating because they are easy to get wrong:
 *
 * An unknown username and a wrong password produce the same exception with
 * the same message. Distinguishing them tells an attacker which accounts
 * exist, and the UI has nothing useful to do with the difference.
 *
 * The deactivated-account check runs after the password check, for the same
 * reason: otherwise a wrong password on a disabled account still confirms
 * the account is real.
 */
public class AuthService {

    /** The signed-in user plus the window the sign-in is good for. */
    public record Session(User user, Instant startedAt, Instant expiresAt) {
        public boolean isExpired() {
            return !Instant.now().isBefore(expiresAt);
        }
    }

    private final UserDao userDao;
    private final Duration lifetime;

    // ponytail: in-memory, per-process, so a restart clears a lockout and a
    // second instance of the app has its own count. A failed_attempts column
    // on users is the upgrade if lockout ever has to survive a restart.
    private final Map<String, Integer> failures = new HashMap<>();

    private Session current;

    public AuthService() {
        this(new UserDaoImpl(), AppConfig.SESSION_LIFETIME);
    }

    public AuthService(UserDao userDao, Duration lifetime) {
        this.userDao = userDao;
        this.lifetime = lifetime;
    }

    public Session login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new ValidationException("Enter both a username and a password.");
        }

        String key = username.trim().toLowerCase();
        int failed = failures.getOrDefault(key, 0);
        if (failed >= AppConfig.MAX_LOGIN_ATTEMPTS) {
            throw new AccountLockedException(
                "Too many failed attempts. Restart the application or ask an administrator.");
        }

        Optional<User> found = userDao.findByUsername(username.trim());
        if (found.isEmpty() || !PasswordHasher.matches(
                password, found.get().getPassword())) {
            failures.put(key, failed + 1);
            throw new InvalidCredentialsException("Username or password is incorrect.");
        }

        User user = found.get();
        if (!user.isActive()) {
            throw new AccountLockedException("This account has been deactivated.");
        }

        failures.remove(key);
        Instant now = Instant.now();
        current = new Session(user, now, now.plus(lifetime));
        return current;
    }

    public void logout() {
        current = null;
    }

    public Optional<User> currentUser() {
        return current == null || current.isExpired()
             ? Optional.empty()
             : Optional.of(current.user());
    }

    /**
     * For any screen that cannot function without an identity. An expired
     * session is cleared here, so the caller cannot keep using a stale one.
     */
    public User requireCurrentUser() {
        if (current == null) {
            throw new AuthenticationException("You are not signed in.");
        }
        if (current.isExpired()) {
            current = null;
            throw new SessionExpiredException("Your session has expired. Sign in again.");
        }
        return current.user();
    }

    public boolean hasRole(Role role) {
        return currentUser().map(u -> u.hasRole(role)).orElse(false);
    }

    /** Remaining failed attempts before the lockout trips, for the login screen. */
    public int attemptsLeft(String username) {
        if (username == null || username.isBlank()) {
            return AppConfig.MAX_LOGIN_ATTEMPTS;
        }
        return AppConfig.MAX_LOGIN_ATTEMPTS
             - failures.getOrDefault(username.trim().toLowerCase(), 0);
    }
}
