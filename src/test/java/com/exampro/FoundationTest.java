package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.SubjectDao;
import com.exampro.dao.TopicDao;
import com.exampro.dao.UserDao;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.exception.DuplicateEntityException;
import com.exampro.model.entity.Subject;
import com.exampro.model.entity.Topic;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Role;
import com.exampro.util.PasswordHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One check over the whole week-one stack: schema, pool, DAOs, seeding.
 *
 * It runs the production SchemaInitializer and DataSeeder against an
 * in-memory H2, so a broken DDL statement, a bad column name in a mapRow,
 * or a wrong parameter index fails here rather than at launch.
 */
class FoundationTest {

    static {
        // Set before any ExamPro class loads, because DatabaseConfig reads
        // this once into a static final field.
        System.setProperty("exampro.jdbcUrl",
                           "jdbc:h2:mem:exampro_test;DB_CLOSE_DELAY=-1");
    }

    private static final SubjectDao subjects = new SubjectDaoImpl();
    private static final TopicDao topics = new TopicDaoImpl();
    private static final UserDao users = new UserDaoImpl();

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        assertTrue(new DataSeeder().seedIfEmpty(), "first seed should run");
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    @Test
    void schemaInitialisationIsIdempotent() {
        new SchemaInitializer().initialize();
        assertFalse(new DataSeeder().seedIfEmpty(), "second seed must be a no-op");
    }

    @Test
    void seedsOneAdminTwoFacultyAndTwentyStudents() {
        assertEquals(1, users.findByRole(Role.ADMIN).size());
        assertEquals(2, users.findByRole(Role.FACULTY).size());
        assertEquals(20, users.findByRole(Role.STUDENT).size());
        assertEquals(23, users.count());
    }

    @Test
    void seededPasswordIsHashedAndVerifiable() {
        User admin = users.findByUsername("admin").orElseThrow();
        assertTrue(admin.getPassword().startsWith("$2"), "must be a BCrypt hash");
        assertTrue(PasswordHasher.matches(DataSeeder.DEFAULT_ADMIN_PASSWORD, admin.getPassword()));
        assertFalse(PasswordHasher.matches("wrong", admin.getPassword()));
        assertTrue(admin.hasRole(Role.ADMIN));
    }

    @Test
    void subjectCountsDoNotFanOut() {
        List<Subject> all = subjects.findAllWithCounts();
        assertEquals(3, all.size());
        Subject law = subjects.findByCode("LAW101").orElseThrow();
        // 3 roots plus 8 children, and the count must not be multiplied by
        // the question subquery.
        assertEquals(11, all.stream()
                            .filter(s -> s.getId().equals(law.getId()))
                            .findFirst().orElseThrow()
                            .getTopicCount());
    }

    @Test
    void topicTreeNestsChildrenUnderRoots() {
        long subjectId = subjects.findByCode("CS201").orElseThrow().getId();
        List<Topic> roots = topics.findTree(subjectId);
        assertEquals(3, roots.size());
        assertTrue(roots.stream().allMatch(Topic::isRoot));
        assertEquals(9, roots.stream().mapToInt(r -> r.getChildren().size()).sum());
    }

    @Test
    void duplicateUsernameTranslatesToDuplicateEntityException() {
        User clash = new User("admin", PasswordHasher.hash("whatever"),
                              "Impostor", "x@y.z", Role.STUDENT);
        assertThrows(DuplicateEntityException.class, () -> users.insert(clash));
    }
}
