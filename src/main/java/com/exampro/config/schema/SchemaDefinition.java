package com.exampro.config.schema;

import java.util.List;

/**
 * The entire database schema, as Java.
 *
 * There is no .sql file anywhere in this project by design: every DDL
 * statement lives here as a text block and is executed through JDBC by
 * {@link SchemaInitializer}. JSON-shaped columns are declared CLOB rather
 * than H2's native JSON type so the schema stays portable to HSQLDB or
 * Derby if the embedded engine ever has to change.
 *
 * {@link #ALL_TABLES} is ordered so that a foreign key's parent table is
 * always created before its children.
 */
public final class SchemaDefinition {

    // ------------------------------------------------------------------
    // meta
    // ------------------------------------------------------------------

    public static final String CREATE_SCHEMA_VERSION = """
        CREATE TABLE IF NOT EXISTS schema_version (
            version     INT       NOT NULL PRIMARY KEY,
            applied_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """;

    // ------------------------------------------------------------------
    // identity
    // ------------------------------------------------------------------

    public static final String CREATE_USERS = """
        CREATE TABLE IF NOT EXISTS users (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            username    VARCHAR(50)  NOT NULL UNIQUE,
            password    VARCHAR(100) NOT NULL,
            full_name   VARCHAR(120) NOT NULL,
            email       VARCHAR(150),
            is_active   BOOLEAN   DEFAULT TRUE,
            created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
        """;

    public static final String CREATE_ROLES = """
        CREATE TABLE IF NOT EXISTS roles (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            name        VARCHAR(20) NOT NULL UNIQUE,
            description VARCHAR(200)
        )
        """;

    public static final String CREATE_USER_ROLES = """
        CREATE TABLE IF NOT EXISTS user_roles (
            user_id     BIGINT NOT NULL,
            role_id     BIGINT NOT NULL,
            granted_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, role_id),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (role_id) REFERENCES roles(id)
        )
        """;

    public static final String CREATE_SESSIONS = """
        CREATE TABLE IF NOT EXISTS sessions (
            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id      BIGINT      NOT NULL,
            token        VARCHAR(64) NOT NULL UNIQUE,
            started_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
            expires_at   TIMESTAMP   NOT NULL,
            ended_at     TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
        """;

    // ------------------------------------------------------------------
    // content
    // ------------------------------------------------------------------

    public static final String CREATE_SUBJECTS = """
        CREATE TABLE IF NOT EXISTS subjects (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            code        VARCHAR(20)  NOT NULL UNIQUE,
            name        VARCHAR(150) NOT NULL,
            description VARCHAR(500),
            created_by  BIGINT,
            is_active   BOOLEAN   DEFAULT TRUE,
            created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (created_by) REFERENCES users(id)
        )
        """;

    /** Self-referencing parent_id gives the nested topic tree. */
    public static final String CREATE_TOPICS = """
        CREATE TABLE IF NOT EXISTS topics (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            subject_id    BIGINT       NOT NULL,
            parent_id     BIGINT,
            name          VARCHAR(150) NOT NULL,
            display_order INT       DEFAULT 0,
            is_active     BOOLEAN   DEFAULT TRUE,
            created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE,
            FOREIGN KEY (parent_id)  REFERENCES topics(id)
        )
        """;

    public static final String CREATE_QUESTIONS = """
        CREATE TABLE IF NOT EXISTS questions (
            id                BIGINT AUTO_INCREMENT PRIMARY KEY,
            topic_id          BIGINT      NOT NULL,
            type              VARCHAR(20) NOT NULL,
            stem              CLOB        NOT NULL,
            difficulty        VARCHAR(10) NOT NULL,
            marks             DOUBLE      NOT NULL,
            negative_marks    DOUBLE    DEFAULT 0,
            numeric_answer    DOUBLE,
            numeric_tolerance DOUBLE,
            keywords          CLOB,
            explanation       CLOB,
            created_by        BIGINT,
            is_active         BOOLEAN   DEFAULT TRUE,
            created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (topic_id)   REFERENCES topics(id),
            FOREIGN KEY (created_by) REFERENCES users(id)
        )
        """;

    /** TRUE_FALSE stores two rows like any other choice type, so grading stays uniform. */
    public static final String CREATE_QUESTION_OPTIONS = """
        CREATE TABLE IF NOT EXISTS question_options (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            question_id   BIGINT  NOT NULL,
            option_text   CLOB    NOT NULL,
            is_correct    BOOLEAN NOT NULL DEFAULT FALSE,
            display_order INT     NOT NULL DEFAULT 0,
            FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE
        )
        """;

    public static final String CREATE_TAGS = """
        CREATE TABLE IF NOT EXISTS tags (
            id    BIGINT AUTO_INCREMENT PRIMARY KEY,
            name  VARCHAR(50) NOT NULL UNIQUE,
            color VARCHAR(10)
        )
        """;

    public static final String CREATE_QUESTION_TAGS = """
        CREATE TABLE IF NOT EXISTS question_tags (
            question_id BIGINT NOT NULL,
            tag_id      BIGINT NOT NULL,
            PRIMARY KEY (question_id, tag_id),
            FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE,
            FOREIGN KEY (tag_id)      REFERENCES tags(id) ON DELETE CASCADE
        )
        """;

    // ------------------------------------------------------------------
    // exam definition
    // ------------------------------------------------------------------

    public static final String CREATE_EXAMS = """
        CREATE TABLE IF NOT EXISTS exams (
            id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
            subject_id          BIGINT       NOT NULL,
            title               VARCHAR(200) NOT NULL,
            instructions        CLOB,
            duration_minutes    INT          NOT NULL,
            opens_at            TIMESTAMP    NOT NULL,
            closes_at           TIMESTAMP    NOT NULL,
            total_marks         DOUBLE,
            generation_strategy VARCHAR(40)  NOT NULL DEFAULT 'BLUEPRINT',
            shuffle_questions   BOOLEAN   DEFAULT TRUE,
            shuffle_options     BOOLEAN   DEFAULT TRUE,
            proctoring_enabled  BOOLEAN   DEFAULT TRUE,
            is_published        BOOLEAN   DEFAULT FALSE,
            created_by          BIGINT,
            created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (subject_id) REFERENCES subjects(id),
            FOREIGN KEY (created_by) REFERENCES users(id)
        )
        """;

    public static final String CREATE_EXAM_BLUEPRINTS = """
        CREATE TABLE IF NOT EXISTS exam_blueprints (
            id         BIGINT AUTO_INCREMENT PRIMARY KEY,
            exam_id    BIGINT       NOT NULL,
            name       VARCHAR(150) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (exam_id) REFERENCES exams(id) ON DELETE CASCADE
        )
        """;

    /** One row is one instruction: "pull 5 MEDIUM MCQs from topic X". */
    public static final String CREATE_BLUEPRINT_RULES = """
        CREATE TABLE IF NOT EXISTS blueprint_rules (
            id             BIGINT AUTO_INCREMENT PRIMARY KEY,
            blueprint_id   BIGINT      NOT NULL,
            topic_id       BIGINT      NOT NULL,
            difficulty     VARCHAR(10) NOT NULL,
            question_type  VARCHAR(20) NOT NULL,
            question_count INT         NOT NULL,
            FOREIGN KEY (blueprint_id) REFERENCES exam_blueprints(id) ON DELETE CASCADE,
            FOREIGN KEY (topic_id)     REFERENCES topics(id)
        )
        """;

    // ------------------------------------------------------------------
    // exam instance
    // ------------------------------------------------------------------

    public static final String CREATE_EXAM_ATTEMPTS = """
        CREATE TABLE IF NOT EXISTS exam_attempts (
            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
            exam_id         BIGINT      NOT NULL,
            student_id      BIGINT      NOT NULL,
            status          VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
            started_at      TIMESTAMP,
            submitted_at    TIMESTAMP,
            expires_at      TIMESTAMP,
            total_score     DOUBLE,
            integrity_score DOUBLE,
            created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (exam_id)    REFERENCES exams(id),
            FOREIGN KEY (student_id) REFERENCES users(id)
        )
        """;

    /**
     * The frozen paper. Written once at generation time, which is what makes
     * per-student randomisation stable across a refresh or a crash recovery.
     */
    public static final String CREATE_ATTEMPT_QUESTIONS = """
        CREATE TABLE IF NOT EXISTS attempt_questions (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            attempt_id    BIGINT NOT NULL,
            question_id   BIGINT NOT NULL,
            display_order INT    NOT NULL,
            option_order  CLOB,
            FOREIGN KEY (attempt_id)  REFERENCES exam_attempts(id) ON DELETE CASCADE,
            FOREIGN KEY (question_id) REFERENCES questions(id)
        )
        """;

    public static final String CREATE_RESPONSES = """
        CREATE TABLE IF NOT EXISTS responses (
            id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
            attempt_id          BIGINT NOT NULL,
            question_id         BIGINT NOT NULL,
            selected_option_ids CLOB,
            text_answer         CLOB,
            numeric_answer      DOUBLE,
            is_flagged          BOOLEAN DEFAULT FALSE,
            time_spent_ms       BIGINT  DEFAULT 0,
            revision_count      INT     DEFAULT 0,
            auto_score          DOUBLE,
            manual_score        DOUBLE,
            final_score         DOUBLE,
            last_saved_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (attempt_id)  REFERENCES exam_attempts(id) ON DELETE CASCADE,
            FOREIGN KEY (question_id) REFERENCES questions(id)
        )
        """;

    // ------------------------------------------------------------------
    // proctoring
    // ------------------------------------------------------------------

    public static final String CREATE_PROCTOR_RULES = """
        CREATE TABLE IF NOT EXISTS proctor_rules (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            rule_key    VARCHAR(50) NOT NULL UNIQUE,
            description VARCHAR(300),
            severity    VARCHAR(10) NOT NULL,
            threshold   DOUBLE,
            is_enabled  BOOLEAN DEFAULT TRUE
        )
        """;

    public static final String CREATE_PROCTOR_EVENTS = """
        CREATE TABLE IF NOT EXISTS proctor_events (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            attempt_id  BIGINT      NOT NULL,
            event_type  VARCHAR(30) NOT NULL,
            severity    VARCHAR(10) NOT NULL,
            occurred_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
            duration_ms BIGINT,
            metadata    CLOB,
            FOREIGN KEY (attempt_id) REFERENCES exam_attempts(id) ON DELETE CASCADE
        )
        """;

    public static final String CREATE_INTEGRITY_SCORES = """
        CREATE TABLE IF NOT EXISTS integrity_scores (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            attempt_id    BIGINT NOT NULL UNIQUE,
            score         DOUBLE NOT NULL,
            penalty_total DOUBLE NOT NULL DEFAULT 0,
            breakdown     CLOB,
            computed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (attempt_id) REFERENCES exam_attempts(id) ON DELETE CASCADE
        )
        """;

    public static final String CREATE_PROCTOR_SNAPSHOTS = """
        CREATE TABLE IF NOT EXISTS proctor_snapshots (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            attempt_id  BIGINT       NOT NULL,
            file_path   VARCHAR(400) NOT NULL,
            captured_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (attempt_id) REFERENCES exam_attempts(id) ON DELETE CASCADE
        )
        """;

    // ------------------------------------------------------------------
    // grading
    // ------------------------------------------------------------------

    public static final String CREATE_RUBRICS = """
        CREATE TABLE IF NOT EXISTS rubrics (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            question_id BIGINT       NOT NULL,
            name        VARCHAR(150) NOT NULL,
            created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE
        )
        """;

    public static final String CREATE_RUBRIC_CRITERIA = """
        CREATE TABLE IF NOT EXISTS rubric_criteria (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            rubric_id     BIGINT       NOT NULL,
            description   VARCHAR(400) NOT NULL,
            max_marks     DOUBLE       NOT NULL,
            display_order INT DEFAULT 0,
            FOREIGN KEY (rubric_id) REFERENCES rubrics(id) ON DELETE CASCADE
        )
        """;

    public static final String CREATE_MANUAL_GRADES = """
        CREATE TABLE IF NOT EXISTS manual_grades (
            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
            response_id  BIGINT NOT NULL,
            criterion_id BIGINT,
            graded_by    BIGINT NOT NULL,
            marks        DOUBLE NOT NULL,
            comment      CLOB,
            graded_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (response_id)  REFERENCES responses(id) ON DELETE CASCADE,
            FOREIGN KEY (criterion_id) REFERENCES rubric_criteria(id),
            FOREIGN KEY (graded_by)    REFERENCES users(id)
        )
        """;

    // ------------------------------------------------------------------
    // analytics and audit
    // ------------------------------------------------------------------

    /** Materialised: recomputed once after an exam closes, not on every view. */
    public static final String CREATE_ITEM_STATISTICS = """
        CREATE TABLE IF NOT EXISTS item_statistics (
            id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
            question_id             BIGINT NOT NULL,
            exam_id                 BIGINT NOT NULL,
            attempts_count          INT    NOT NULL DEFAULT 0,
            p_value                 DOUBLE,
            discrimination_index    DOUBLE,
            avg_time_ms             BIGINT,
            distractor_distribution CLOB,
            computed_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE,
            FOREIGN KEY (exam_id)     REFERENCES exams(id) ON DELETE CASCADE
        )
        """;

    public static final String CREATE_AUDIT_LOGS = """
        CREATE TABLE IF NOT EXISTS audit_logs (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id     BIGINT,
            action      VARCHAR(80) NOT NULL,
            entity_type VARCHAR(50),
            entity_id   BIGINT,
            detail      CLOB,
            occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id)
        )
        """;

    // ------------------------------------------------------------------
    // indexes
    // ------------------------------------------------------------------

    /** Guards the autosave race: one response row per question per attempt. */
    public static final String IDX_RESPONSES_UNIQUE =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_responses_attempt_question "
        + "ON responses(attempt_id, question_id)";

    /** One attempt per student per exam unless faculty explicitly resets it. */
    public static final String IDX_ATTEMPTS_UNIQUE =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_attempts_exam_student "
        + "ON exam_attempts(exam_id, student_id)";

    public static final String IDX_ATTEMPT_QUESTIONS_UNIQUE =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_attempt_questions_unique "
        + "ON attempt_questions(attempt_id, question_id)";

    /** The live proctor monitor polls this constantly. */
    public static final String IDX_PROCTOR_EVENTS =
        "CREATE INDEX IF NOT EXISTS idx_proctor_events_attempt_time "
        + "ON proctor_events(attempt_id, occurred_at)";

    /** The paper generator hits this once per blueprint rule. */
    public static final String IDX_QUESTIONS_POOL =
        "CREATE INDEX IF NOT EXISTS idx_questions_pool "
        + "ON questions(topic_id, difficulty, type, is_active)";

    public static final String IDX_QUESTION_OPTIONS =
        "CREATE INDEX IF NOT EXISTS idx_question_options_question "
        + "ON question_options(question_id, display_order)";

    public static final String IDX_TOPICS_SUBJECT =
        "CREATE INDEX IF NOT EXISTS idx_topics_subject_parent "
        + "ON topics(subject_id, parent_id)";

    public static final String IDX_ITEM_STATS =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_item_stats_question_exam "
        + "ON item_statistics(question_id, exam_id)";

    public static final String IDX_AUDIT_LOGS =
        "CREATE INDEX IF NOT EXISTS idx_audit_logs_time ON audit_logs(occurred_at)";

    // ------------------------------------------------------------------
    // ordered execution lists
    // ------------------------------------------------------------------

    /** Parent-before-child. SchemaInitializer executes in exactly this order. */
    public static final List<String> ALL_TABLES = List.of(
        CREATE_USERS,
        CREATE_ROLES,
        CREATE_USER_ROLES,
        CREATE_SESSIONS,
        CREATE_SUBJECTS,
        CREATE_TOPICS,
        CREATE_QUESTIONS,
        CREATE_QUESTION_OPTIONS,
        CREATE_TAGS,
        CREATE_QUESTION_TAGS,
        CREATE_EXAMS,
        CREATE_EXAM_BLUEPRINTS,
        CREATE_BLUEPRINT_RULES,
        CREATE_EXAM_ATTEMPTS,
        CREATE_ATTEMPT_QUESTIONS,
        CREATE_RESPONSES,
        CREATE_PROCTOR_RULES,
        CREATE_PROCTOR_EVENTS,
        CREATE_INTEGRITY_SCORES,
        CREATE_PROCTOR_SNAPSHOTS,
        CREATE_RUBRICS,
        CREATE_RUBRIC_CRITERIA,
        CREATE_MANUAL_GRADES,
        CREATE_ITEM_STATISTICS,
        CREATE_AUDIT_LOGS
    );

    public static final List<String> ALL_INDEXES = List.of(
        IDX_RESPONSES_UNIQUE,
        IDX_ATTEMPTS_UNIQUE,
        IDX_ATTEMPT_QUESTIONS_UNIQUE,
        IDX_PROCTOR_EVENTS,
        IDX_QUESTIONS_POOL,
        IDX_QUESTION_OPTIONS,
        IDX_TOPICS_SUBJECT,
        IDX_ITEM_STATS,
        IDX_AUDIT_LOGS
    );

    /** Child-before-parent, for ResetDatabase. */
    public static final List<String> TABLE_NAMES_DROP_ORDER = List.of(
        "audit_logs", "item_statistics", "manual_grades", "rubric_criteria", "rubrics",
        "proctor_snapshots", "integrity_scores", "proctor_events", "proctor_rules",
        "responses", "attempt_questions", "exam_attempts",
        "blueprint_rules", "exam_blueprints", "exams",
        "question_tags", "tags", "question_options", "questions", "topics", "subjects",
        "sessions", "user_roles", "roles", "users",
        "schema_version"
    );

    private SchemaDefinition() {}
}
