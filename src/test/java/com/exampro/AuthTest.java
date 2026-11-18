package com.exampro;

import com.exampro.config.AppConfig;
import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.UserDao;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.exception.AccountLockedException;
import com.exampro.exception.AuthenticationException;
import com.exampro.exception.InvalidCredentialsException;
import com.exampro.exception.SessionExpiredException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Role;
import com.exampro.service.AuthService;
import com.exampro.util.PasswordHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week two: the authentication paths that must not silently pass. */
class AuthTest {

    static {
        System.setProperty("exampro.jdbcUrl",
                           "jdbc:h2:mem:exampro_auth_test;DB_CLOSE_DELAY=-1");
    }

    private static final UserDao users = new UserDaoImpl();

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private AuthService service() {
        return new AuthService(users, AppConfig.SESSION_LIFETIME);
    }

    @Test
    void goodCredentialsOpenASessionWithRoles() {
        AuthService auth = service();
        AuthService.Session session = auth.login("admin", DataSeeder.DEFAULT_ADMIN_PASSWORD);

        assertFalse(session.isExpired());
        assertTrue(session.user().hasRole(Role.ADMIN));
        assertTrue(auth.hasRole(Role.ADMIN));
        assertEquals("admin", auth.requireCurrentUser().getUsername());
    }

    @Test
    void unknownUserAndWrongPasswordAreIndistinguishable() {
        AuthService auth = service();
        InvalidCredentialsException wrong = assertThrows(InvalidCredentialsException.class,
            () -> auth.login("admin", "not the password"));
        InvalidCredentialsException absent = assertThrows(InvalidCredentialsException.class,
            () -> auth.login("nobody_at_all", "not the password"));

        assertEquals(wrong.getMessage(), absent.getMessage());
    }

    @Test
    void blankInputIsRejectedBeforeAnyQuery() {
        assertThrows(ValidationException.class, () -> service().login("  ", ""));
    }

    @Test
    void lockoutTripsAfterTheConfiguredAttempts() {
        AuthService auth = service();
        for (int i = 0; i < AppConfig.MAX_LOGIN_ATTEMPTS; i++) {
            assertThrows(InvalidCredentialsException.class,
                         () -> auth.login("faculty1", "wrong"));
        }
        assertEquals(0, auth.attemptsLeft("faculty1"));
        // The right password no longer helps, which is the point of a lockout.
        assertThrows(AccountLockedException.class,
                     () -> auth.login("faculty1", DataSeeder.DEFAULT_FACULTY_PASSWORD));
    }

    @Test
    void deactivatedAccountCannotSignInEvenWithTheRightPassword() {
        User disabled = new User("disabled_user", PasswordHasher.hash("letmein"),
                                 "Disabled Account", "d@x.z", Role.STUDENT);
        disabled.setActive(false);
        users.insert(disabled);

        assertThrows(AccountLockedException.class,
                     () -> service().login("disabled_user", "letmein"));
    }

    @Test
    void expiredSessionIsClearedOnTheNextCheck() {
        AuthService auth = new AuthService(users, Duration.ZERO);
        auth.login("admin", DataSeeder.DEFAULT_ADMIN_PASSWORD);

        assertTrue(auth.currentUser().isEmpty());
        assertThrows(SessionExpiredException.class, auth::requireCurrentUser);
        // Cleared, so the second call is "not signed in" rather than "expired".
        assertThrows(AuthenticationException.class, auth::requireCurrentUser);
    }

    @Test
    void logoutDropsTheSession() {
        AuthService auth = service();
        auth.login("student01", DataSeeder.DEFAULT_STUDENT_PASSWORD);
        auth.logout();

        assertTrue(auth.currentUser().isEmpty());
        assertThrows(AuthenticationException.class, auth::requireCurrentUser);
    }
}
