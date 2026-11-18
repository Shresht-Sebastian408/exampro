# ExamPro: Exam Proctoring and Question Bank System

Project plan and technical specification.

Constraint: 100 percent Java. No native dependencies, no standalone database server, no external .sql files. All SQL is defined and executed from Java through JDBC.

---

## 1. Overview

A desktop application for creating question banks, generating randomised exam papers from blueprints, delivering exams under behavioural proctoring, grading automatically, and producing item level analytics.

### Roles

- **Admin**: manage users, subjects, topics, system settings, audit logs.
- **Faculty**: build question bank, define blueprints, schedule exams, monitor live proctoring, grade subjective answers, view analytics.
- **Student**: take exams in a controlled window, review results and feedback.

### Functional modules

- Question Bank: hierarchical topics, difficulty tagging, five question types, bulk import and export.
- Paper Generation: blueprint driven, randomised per student, frozen at generation time.
- Exam Delivery: countdown timer, autosave, question palette, flag for review, crash recovery.
- Proctoring: behavioural event capture, rule evaluation, integrity scoring, live faculty monitor.
- Grading: automatic for objective types, rubric assisted manual for subjective.
- Analytics: item analysis, distractor analysis, performance reports, PDF export.

### Scope note on proctoring

Full webcam face detection requires OpenCV, which ships native binaries and breaks the pure Java constraint. Proctoring is therefore scoped as **behavioural proctoring**: focus loss, window switching, clipboard activity, idle gaps, answer velocity anomalies, and multi display detection. Optional periodic screen snapshots use `java.awt.Robot`, which is JDK standard.

---

## 2. Technology Stack

| Layer | Choice | Reason |
| --- | --- | --- |
| Language | Java 17 or 21 | Text blocks, records, sealed types, enhanced switch |
| UI | JavaFX 21 | Native animation, CSS theming, scene graph |
| Database | H2 embedded | Written entirely in Java, zero install, single file |
| Connection pooling | HikariCP | Pure Java, standard choice |
| JSON | Jackson Databind | JSON columns, import and export |
| PDF | Apache PDFBox | Pure Java PDF generation |
| Password hashing | jBCrypt | Pure Java |
| Testing | JUnit 5, Mockito | Unit and mock testing |
| Build | Maven | Dependency management, shaded jar |

### Dependency audit

Every dependency is pure Java bytecode. No native libraries.

Explicitly excluded for violating the constraint:

- OpenCV and all native vision libraries
- Any webcam capture library with native bindings
- MySQL, PostgreSQL, and any database requiring a separate server process

### Database connection

- Driver: `org.h2.Driver`
- URL: `jdbc:h2:./data/exampro;AUTO_SERVER=TRUE`
- H2 speaks standard SQL. Migrating to another engine later means changing one config value.
- Alternatives if needed: HSQLDB or Apache Derby, both also pure Java. H2 chosen for speed and documentation quality.

---

## 3. Database Design

### 3.1 Entity groups

- **Identity**: `users`, `roles`, `user_roles`, `sessions`
- **Content**: `subjects`, `topics`, `questions`, `question_options`, `tags`, `question_tags`
- **Exam definition**: `exams`, `exam_blueprints`, `blueprint_rules`
- **Exam instance**: `exam_attempts`, `attempt_questions`, `responses`
- **Proctoring**: `proctor_events`, `proctor_rules`, `integrity_scores`, `proctor_snapshots`
- **Grading**: `rubrics`, `rubric_criteria`, `manual_grades`
- **Analytics**: `item_statistics`

### 3.2 Key tables

**questions**

- `id`, `topic_id`, `type` (MCQ, MSQ, TRUE_FALSE, NUMERIC, SHORT_ANSWER)
- `stem` (CLOB), `difficulty` (EASY, MEDIUM, HARD), `marks`, `negative_marks`
- `numeric_answer`, `numeric_tolerance` (nullable, NUMERIC type only)
- `explanation`, `created_by`, `created_at`, `updated_at`, `is_active`

**question_options**

- `id`, `question_id`, `option_text`, `is_correct`, `display_order`
- TRUE_FALSE still stores two rows. Keeps grading logic uniform across types.

**blueprint_rules**

- `id`, `blueprint_id`, `topic_id`, `difficulty`, `question_type`, `count`
- Each row is one instruction, for example: pull 5 MEDIUM MCQs from topic "Contract Formation".

**attempt_questions**

- `id`, `attempt_id`, `question_id`, `display_order`, `option_order` (JSON array of option ids)
- This table is what makes per student randomisation stable. The paper is frozen here at generation time so a refresh does not reshuffle.

**responses**

- `id`, `attempt_id`, `question_id`, `selected_option_ids` (JSON), `text_answer`, `numeric_answer`
- `is_flagged`, `time_spent_ms`, `revision_count`, `last_saved_at`
- `auto_score`, `manual_score`, `final_score`

**proctor_events**

- `id`, `attempt_id`, `event_type`, `severity`, `occurred_at`, `duration_ms`, `metadata` (JSON)
- Event types: `FOCUS_LOST`, `FOCUS_REGAINED`, `WINDOW_RESIZED`, `COPY_ATTEMPT`, `PASTE_ATTEMPT`, `IDLE_TIMEOUT`, `RAPID_ANSWER`, `MULTI_DISPLAY_DETECTED`, `FULLSCREEN_EXIT`, `SNAPSHOT_CAPTURED`

**item_statistics**

- `question_id`, `exam_id`, `attempts_count`, `p_value`, `discrimination_index`, `avg_time_ms`, `distractor_distribution` (JSON)
- Materialised table, recomputed after exam close rather than calculated on every view.

### 3.3 Indexes that matter

- `responses(attempt_id, question_id)` UNIQUE. Prevents duplicate saves from autosave races.
- `proctor_events(attempt_id, occurred_at)`. The live monitor polls this constantly.
- `questions(topic_id, difficulty, type, is_active)`. The paper generator hits this on every blueprint rule.
- `exam_attempts(exam_id, student_id)` UNIQUE. One attempt per student unless explicitly reset by faculty.

### 3.4 Transaction boundaries

These are the deliberate JDBC transaction points.

- **Paper generation**: insert the attempt plus all attempt_questions rows atomically. A partially generated paper is worse than no paper.
- **Final submission**: lock the attempt row, flush all pending responses, set status to SUBMITTED, compute integrity score, write audit record. Single commit or full rollback.
- **Schema initialisation**: all DDL inside one transaction, rollback on any failure.
- **Autosave**: deliberately NOT transactional. Single row upsert, non blocking, fire and forget with retry.

---

## 4. Schema in Java

No .sql files exist in the project. All DDL is defined as Java constants and executed through JDBC.

### 4.1 SchemaDefinition

Package: `com.exampro.config.schema`

Uses Java text blocks so SQL stays readable without string concatenation.

```java
public final class SchemaDefinition {

    public static final String CREATE_USERS = """
        CREATE TABLE IF NOT EXISTS users (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            username    VARCHAR(50)  NOT NULL UNIQUE,
            password    VARCHAR(100) NOT NULL,
            full_name   VARCHAR(120) NOT NULL,
            email       VARCHAR(150),
            is_active   BOOLEAN      DEFAULT TRUE,
            created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
        )
        """;

    public static final String CREATE_QUESTIONS = """
        CREATE TABLE IF NOT EXISTS questions (
            id                BIGINT AUTO_INCREMENT PRIMARY KEY,
            topic_id          BIGINT NOT NULL,
            type              VARCHAR(20) NOT NULL,
            stem              CLOB NOT NULL,
            difficulty        VARCHAR(10) NOT NULL,
            marks             DOUBLE NOT NULL,
            negative_marks    DOUBLE DEFAULT 0,
            numeric_answer    DOUBLE,
            numeric_tolerance DOUBLE,
            explanation       CLOB,
            created_by        BIGINT,
            is_active         BOOLEAN DEFAULT TRUE,
            created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (topic_id)   REFERENCES topics(id),
            FOREIGN KEY (created_by) REFERENCES users(id)
        )
        """;

    // one constant per table, plus one per index

    private SchemaDefinition() {}
}
```

### 4.2 SchemaInitializer

- Runs once from `Main` before the JavaFX stage opens.
- Maintains a `schema_version` table so repeat launches skip setup and future migrations are possible.
- DDL list is ordered so foreign key parent tables are created before children.
- Wraps everything in a transaction, rolls back on any failure, throws `SchemaInitializationException`.

```java
public class SchemaInitializer {

    private static final int CURRENT_VERSION = 1;

    public void initialize() throws SchemaInitializationException {
        try (Connection conn = ConnectionPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                createVersionTable(conn);
                int installed = readVersion(conn);

                if (installed == 0) {
                    runFullSchema(conn);
                    writeVersion(conn, CURRENT_VERSION);
                } else if (installed < CURRENT_VERSION) {
                    applyMigrations(conn, installed);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SchemaInitializationException(
                    "Schema setup failed at version " + CURRENT_VERSION, e);
            }
        } catch (SQLException e) {
            throw new ConnectionException("Cannot reach database", e);
        }
    }
}
```

### 4.3 DataSeeder

- Runs only when the users table is empty.
- Builds model objects in Java and inserts them through the normal DAO layer, not raw SQL.
- Seeding therefore doubles as an integration test of every insert path.

Seed contents:

- One admin account with a BCrypt hashed password.
- Two faculty accounts, twenty student accounts.
- Three subjects with a nested topic tree.
- 40 to 60 sample questions spread across all five types and all three difficulty levels.

### 4.4 ResetDatabase utility

- Drops all tables and rebuilds from `SchemaDefinition`, then reseeds.
- Used constantly during development. Also a safety net if something breaks during the live demo.

---

## 5. Package Architecture

```
com.exampro
├── Main.java
├── config
│   ├── AppConfig
│   ├── DatabaseConfig
│   ├── ConnectionPool
│   └── schema
│       ├── SchemaDefinition
│       ├── SchemaInitializer
│       ├── DataSeeder
│       └── ResetDatabase
├── model
│   ├── entity          (User, Exam, Attempt, Response, ProctorEvent)
│   ├── question        (Question, MCQQuestion, MSQQuestion,
│   │                    TrueFalseQuestion, NumericQuestion,
│   │                    ShortAnswerQuestion)
│   └── enums           (Role, Difficulty, QuestionType, EventType,
│                        Severity, AttemptStatus)
├── dao
│   ├── GenericDao<T, ID>            (interface)
│   ├── QuestionDao                  (interface)
│   ├── AttemptDao                   (interface)
│   ├── ProctorEventDao              (interface)
│   └── impl
│       ├── AbstractJdbcDao<T, ID>
│       ├── QuestionDaoImpl
│       ├── AttemptDaoImpl
│       └── ProctorEventDaoImpl
├── service
│   ├── AuthService
│   ├── QuestionBankService
│   ├── PaperGenerationService
│   ├── ExamDeliveryService
│   ├── GradingService
│   ├── ProctoringService
│   └── AnalyticsService
├── generation
│   ├── PaperGenerationStrategy      (interface)
│   ├── BlueprintStrategy
│   ├── RandomPoolStrategy
│   └── AdaptiveDifficultyStrategy
├── grading
│   ├── Grader<T extends Question>   (interface)
│   ├── MCQGrader
│   ├── MSQGrader
│   ├── NumericGrader
│   └── KeywordGrader
├── proctor
│   ├── ProctorRule                  (interface)
│   ├── ProctorEventListener         (interface)
│   ├── rules
│   │   ├── FocusLossRule
│   │   ├── ClipboardRule
│   │   ├── IdleTimeoutRule
│   │   └── AnswerVelocityRule
│   ├── ProctorEngine
│   └── IntegrityScorer
├── analytics
│   ├── ItemAnalyzer
│   ├── DistractorAnalyzer
│   └── PerformanceReporter
├── export
│   ├── Exporter<T>                  (interface)
│   ├── PdfResultExporter
│   ├── CsvQuestionExporter
│   └── JsonBankExporter
├── exception
├── ui
│   ├── controller
│   ├── component
│   ├── animation
│   └── util                         (AlertFactory, SceneRouter, ThemeManager)
└── util
    ├── PasswordHasher
    ├── ValidationUtils
    ├── TimeUtils
    └── ShuffleUtils
```

---

## 6. Interface and Inheritance Design

Four distinct hierarchies, each with a specific justification.

### 6.1 Question hierarchy (abstract class and polymorphism)

- `abstract class Question` holds id, stem, marks, difficulty, topic.
- Forces `abstract boolean isAttempted(Response r)` and `abstract String getAnswerSummary()` on every subclass.
- Five concrete subclasses. `MSQQuestion` adds partial credit rules, `NumericQuestion` adds tolerance, `ShortAnswerQuestion` adds a keyword set.

### 6.2 Grading (generic interface, strategy pattern)

```java
public interface Grader<T extends Question> {
    GradeResult grade(T question, Response response);
    boolean supports(QuestionType type);
}
```

- `GradingService` holds an `EnumMap<QuestionType, Grader<?>>` and dispatches by type.
- Adding a new question type means adding one grader class and one map entry. Nothing else changes. This is the extensibility argument to make in a viva.

### 6.3 Paper generation (strategy pattern)

```java
public interface PaperGenerationStrategy {
    List<Question> generate(Exam exam, Student student, QuestionPool pool)
            throws PaperGenerationException;
    String getName();
}
```

- Three implementations, selectable per exam from a dropdown.
- `AdaptiveDifficultyStrategy` starts at medium and adjusts based on live performance. This is the differentiating feature.

### 6.4 Proctoring (observer pattern and rule chain)

```java
public interface ProctorRule {
    Optional<ProctorEvent> evaluate(ProctorContext context);
    Severity getSeverity();
    boolean isEnabled();
}

public interface ProctorEventListener {
    void onEvent(ProctorEvent event);
}
```

- `ProctorEngine` holds a `List<ProctorRule>`, evaluates them on a scheduled tick, and pushes produced events to all registered listeners.
- Three independent listeners: the database writer, the student side warning banner, and the faculty live monitor.

### 6.5 Data access (generic interface)

```java
public interface GenericDao<T, ID> {
    Optional<T> findById(ID id)      throws DataAccessException;
    List<T>     findAll()            throws DataAccessException;
    ID          insert(T entity)     throws DataAccessException;
    void        update(T entity)     throws DataAccessException;
    void        delete(ID id)        throws DataAccessException;
}
```

- `AbstractJdbcDao<T, ID>` implements connection handling, try with resources, and SQLException translation once.
- Each concrete DAO writes only its SQL constants and its `mapRow(ResultSet)` method. Removes roughly 70 percent of JDBC boilerplate.

---

## 7. Query Conventions

- Every query uses a `PreparedStatement` with bound parameters. No string concatenation into SQL at any point.
- SQL strings are `private static final String` constants at the top of each DAO class.
- All resources opened in try with resources. No manual close calls.

```java
public class QuestionDaoImpl extends AbstractJdbcDao<Question, Long>
                             implements QuestionDao {

    private static final String FIND_BY_BLUEPRINT = """
        SELECT * FROM questions
        WHERE topic_id = ? AND difficulty = ? AND type = ?
          AND is_active = TRUE
        ORDER BY RAND()
        LIMIT ?
        """;

    @Override
    public List<Question> findForRule(BlueprintRule rule)
            throws DataAccessException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(FIND_BY_BLUEPRINT)) {

            ps.setLong(1, rule.getTopicId());
            ps.setString(2, rule.getDifficulty().name());
            ps.setString(3, rule.getType().name());
            ps.setInt(4, rule.getCount());

            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new QueryExecutionException(
                "Blueprint rule fetch failed for topic " + rule.getTopicId(), e);
        }
    }
}
```

### Known tradeoff to document

`ORDER BY RAND() LIMIT n` is fine at this data scale and works in H2. Past a few thousand rows per topic it degrades, since the engine must sort the full candidate set. The alternative is fetching ids only and shuffling in Java with `Collections.shuffle`. Documenting this tradeoff is worth doing, it demonstrates awareness rather than ignorance.

---

## 8. Exception Hierarchy

```
ExamProException (RuntimeException, root)
├── DataAccessException
│   ├── ConnectionException
│   ├── QueryExecutionException
│   ├── EntityNotFoundException
│   ├── DuplicateEntityException
│   └── SchemaInitializationException
├── ValidationException
│   ├── InvalidQuestionException
│   ├── InvalidBlueprintException
│   └── ConstraintViolationException
├── ExamException
│   ├── ExamNotStartedException
│   ├── ExamExpiredException
│   ├── AttemptAlreadySubmittedException
│   ├── InsufficientQuestionsException
│   └── PaperGenerationException
├── ProctorException
│   ├── IntegrityViolationException
│   └── SnapshotCaptureException
├── AuthenticationException
│   ├── InvalidCredentialsException
│   ├── AccountLockedException
│   └── SessionExpiredException
└── ExportException
```

### Handling policy by layer

- **DAO**: catch `SQLException`, inspect the SQL state code, translate to the correct `DataAccessException` subtype. A raw SQLException never escapes this layer.
- **Service**: catch, log, enrich with business context, rethrow.
- **Controller**: catch, show a user friendly alert. A stack trace never reaches the UI.
- **Global**: `Thread.setDefaultUncaughtExceptionHandler` on the JavaFX application thread, so a stray crash produces a dialog instead of a silent freeze.

### Exam specific rule

If any exception is thrown during an active exam, the student must not lose answers. Every handler in the exam view triggers an emergency flush of in memory responses to an encrypted local file before any dialog is shown.

### Design decision worth defending

`InsufficientQuestionsException` fires when a blueprint requests 10 hard questions from a topic holding only 6. This is caught at **blueprint save time**, not at exam start time. Failing early is the correct choice, and this is exactly the kind of decision an examiner probes.

---

## 9. Java Library Coverage

Each rubric requirement mapped to a concrete use.

| Area | Usage |
| --- | --- |
| Collections | `LinkedHashMap` for ordered question palette, `EnumMap` for grader dispatch, `TreeMap` for sorted score distributions, `Deque` for navigation history |
| Streams | Item analysis aggregation, blueprint pool filtering, score distribution computation |
| java.time | `Duration` for exam timer, `Instant` for event timestamps, `LocalDateTime` for scheduling |
| Concurrency | `ScheduledExecutorService` for timer tick, autosave, and proctor evaluation cycle. `AtomicBoolean` as submission guard. `ConcurrentHashMap` for live attempt registry |
| java.nio.file | Emergency response dump, question bank import and export, snapshot storage |
| javax.crypto | AES encryption of the local answer cache so a student cannot edit the recovery file |
| java.awt | `Robot.createScreenCapture` for snapshots, `GraphicsEnvironment.getScreenDevices()` for multi display detection |
| JDBC | Full persistence layer, transactions, batch inserts, prepared statements |

---

## 10. Frontend and Animation Plan

JavaFX 21. FXML for structure, external CSS for theming. All animation is native JavaFX, no third party animation library.

### 10.1 Landing and authentication

- Particle field background: 60 to 80 `Circle` nodes drifting on an `AnimationTimer`, connected to nearby particles by faint lines that fade with distance.
- Mouse tracking: particles within 150px of the cursor are repelled, with velocity delta inversely proportional to distance. Implemented on `setOnMouseMoved`.
- Role cards tilt toward the cursor using `Rotate` on X and Y axes, mapped from pointer offset within card bounds. Snap back on exit with `Interpolator.SPLINE` ease out.
- Login form fields slide up with staggered `TranslateTransition` and `FadeTransition`, 60ms apart.

### 10.2 Faculty dashboard

- Scroll driven reveal: listener on the `ScrollPane` vertical value computes each section position and triggers fade plus translate when it crosses 75 percent of viewport height. Fires once per section, guarded by a boolean flag.
- Stat counters animate zero to final value via `Timeline` bound to an `IntegerProperty`.
- Topic coverage radial chart: arcs grow outward on a `Timeline` over the arc length property.
- Table rows fade and slide in sequentially on load.

### 10.3 Question bank editor

- Type selector morphs the form. Fields cross fade rather than snap, using a `ParallelTransition` of fade out and fade in.
- Live preview pane updates as you type, with a highlight pulse on the changed element.
- Drag and drop option reordering with a ghost node following the cursor and sibling rows sliding to make space.

### 10.4 Exam delivery screen

Deliberately calmer than the rest of the application. Heavy animation here reads as unserious and an examiner will notice.

- Timer ring: `Circle` with animated `strokeDashOffset`. Shifts tone at 5 minutes remaining, pulses via looping `ScaleTransition` at 1 minute.
- Question palette: grid of state styled squares (unvisited, answered, flagged, current), state changes fading over 150ms.
- Question transitions: slide left on next, slide right on previous, 200ms ease out.
- Autosave indicator: small dot pulsing once per successful save. Silent during retry, visible warning only after three consecutive failures.
- Proctor warning banner: slides down from top, holds 4 seconds, slides back. Overlay flash on high severity events.

### 10.5 Live proctor monitor

- Grid of student tiles, each with an integrity meter.
- A tile flashes and reorders to the top of the grid on a high severity event. Reordering uses `TranslateTransition` on affected tiles so the grid visibly rearranges rather than jumping.
- Event feed sliding in from the right, newest first.
- Per student timeline strip: each proctor event is a mark positioned by time. Hover expands the mark with a tooltip that follows the cursor.

### 10.6 Results and analytics

- Scroll driven narrative. Score reveal, then topic breakdown, then question by question review, each triggered on viewport entry.
- Score arc fills with a short delayed start for anticipation.
- Item analysis scatter plot: difficulty on X, discrimination on Y, points fading in staggered. Quadrant shading marks the revise zone.
- Distractor bars grow from zero with per bar stagger.

### 10.7 Reusable animation utilities

Built once in `ui.animation`, reused across every screen. This internal library is itself evidence for the packages and interfaces requirement.

- `FadeSlideIn.play(Node node, Direction dir, double delayMs)`
- `StaggerAnimator.play(List<Node> nodes, double gapMs)`
- `CounterAnimator.animate(Label label, int from, int to, Duration d)`
- `ScrollRevealManager.register(ScrollPane sp, Node target, Runnable onReveal)`
- `TiltEffect.attach(Node node, double maxAngle)`
- `PulseEffect.attach(Node node, Duration period)`

---

## 11. Build Order

Assumes 10 to 12 weeks. Compress proportionally if the timeline is shorter.

### Week 1: Foundation

- Maven project, JavaFX 21, module configuration.
- `SchemaDefinition` with all tables and indexes.
- `SchemaInitializer`, `DataSeeder`, `ResetDatabase`.
- `ConnectionPool`, `AbstractJdbcDao`, `GenericDao`, full exception hierarchy.
- One vertical slice end to end: list subjects from the database into a plain table. Prove the stack works before building anything on it.

### Week 2: Identity and shell

- Users, roles, BCrypt hashing, session handling.
- `SceneRouter` and the application shell with sidebar navigation.
- Login screen with particle background and mouse tracking.
- Write the animation utility library here so every later screen reuses it.

### Week 3: Question bank core

- Question model hierarchy, all five types.
- `QuestionDao` including JSON option handling.
- CRUD UI with morphing type selector and live preview.

### Week 4: Question bank polish

- Tags, topic tree, search and filter.
- Bulk import and export via CSV and JSON.
- Validation layer and `InvalidQuestionException` paths.

### Week 5: Blueprints and generation

- Blueprint builder UI.
- All three `PaperGenerationStrategy` implementations.
- Capacity validation at save time, wiring `InsufficientQuestionsException`.

### Week 6: Exam delivery, functional only

- Attempt creation, frozen paper, navigation, palette, flagging.
- Timer via `ScheduledExecutorService`.
- Autosave and crash recovery with encrypted local cache.
- No animation this week. Correctness first.

### Week 7: Grading

- All four graders, partial credit for MSQ, tolerance for numeric.
- Submission transaction with full commit and rollback handling.
- Manual grading UI with rubrics for short answers.

### Week 8: Proctoring

- `ProctorEngine`, all four rules, event persistence.
- Focus listener on the JavaFX `Stage`, clipboard interception, idle detection, multi display check.
- `IntegrityScorer` with configurable weights.
- Student side warning banner.

### Week 9: Live monitor and analytics

- Faculty live monitor grid with severity based reordering.
- `ItemAnalyzer`: p value, point biserial discrimination, distractor distribution.
- Analytics dashboard with charts.

### Week 10: Export and frontend pass

- PDF result sheets and question papers via PDFBox.
- Full animation pass across every screen.
- CSS theming, consistent spacing and typography.

### Week 11: Hardening

- JUnit tests on graders, generators, and `IntegrityScorer`. These are pure logic classes and the easiest to test meaningfully.
- Edge cases: simultaneous submission, mid exam crash, system clock change, empty blueprint, analytics with zero attempts.
- Load realistic data: 500 questions, 100 students, 3 exams.

### Week 12: Delivery

- Documentation, ER diagram, class diagrams, README.
- Packaged runnable build via `jpackage` or shaded jar with launcher script.
- Demo script. Rehearse the exact click path, with seeded data that surfaces every feature inside 10 minutes.

---

## 12. Scope Control

If the timeline slips, cut in this order. Each cut costs the least at that position.

1. `AdaptiveDifficultyStrategy`. Keep the other two generation strategies.
2. Screen snapshots. Behavioural events alone are a complete proctoring story.
3. Rubric based manual grading. Fall back to a plain marks entry field.
4. Distractor analysis. Keep p value and discrimination index.
5. CSV import. Keep CSV export.

### Never cut

- The submission transaction.
- The exception hierarchy.
- Autosave and crash recovery.
- The animation pass.

These four are what separate this from a standard submission.

---

## 13. Rubric Mapping

| Requirement | Where it is demonstrated |
| --- | --- |
| GUI design | JavaFX across all screens, FXML and CSS, custom components, animation utility library |
| Database design | 20 table normalised schema, indexes, foreign keys, materialised analytics table |
| JDBC | `AbstractJdbcDao`, prepared statements, transactions, batch inserts, connection pooling, SQLException translation |
| Packages and interfaces | 20 package tree, `GenericDao<T, ID>`, `Grader<T>`, `PaperGenerationStrategy`, `ProctorRule`, `ProctorEventListener`, `Exporter<T>` |
| Java library and exception handling | Collections, Streams, java.time, concurrency, NIO, crypto, AWT. 25 class exception hierarchy with layered handling policy |
| Software application development | Three role application, layered architecture, version tracked schema, unit tests, packaged distributable |
