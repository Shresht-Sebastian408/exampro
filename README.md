# ExamPro

Exam proctoring and question bank desktop app. Pure Java: JavaFX 21 UI, embedded H2,
all SQL written in Java and run over JDBC (no ORM). `plan.md` is the original spec.

Faculty build a question bank (five question types), compose exams from blueprints, watch
students live, grade, and read item analytics. Students sit timed exams with autosave and
crash recovery while a rule engine scores their integrity.

## Quick start (Windows)

```
mvnw.cmd package       # tests + shaded jar: target\exampro-1.0.0-shaded.jar
run.bat                # launch
run.bat demo           # optional: add a demo exam with finished and in-progress sittings
```

Needs JDK 21 or newer on `PATH`/`JAVA_HOME`; Maven is not required, the wrapper handles it.
The jar runs with plain `java -jar` (no module path): `Main` deliberately does not extend
`Application`.

Other commands:

```
mvnw.cmd exec:java                                                       # run from source
mvnw.cmd test                                                            # 82 tests
mvnw.cmd exec:java -Dexec.mainClass=com.exampro.config.schema.ResetDatabase   # wipe and reseed
```

## Logins

| Username | Password | Role |
| --- | --- | --- |
| `admin` | `admin123` | Admin |
| `faculty1`, `faculty2` | `faculty123` | Faculty |
| `student01` ... `student20` | `student123` | Student |

Demo credentials only. Nothing here is intended for a real deployment.

## What is where

| Screen | Role | What it does |
| --- | --- | --- |
| Subjects | all | Subjects with topic and question counts |
| Question bank | faculty | Topic tree, search, filters, tags, editor with live preview, CSV/JSON import and export |
| Exams | faculty | Blueprints (topic, difficulty, type, count), three generation strategies, paper preview, paper PDF with optional key |
| Grading | faculty | Auto-scored results, rubric-based manual marking, integrity score beside each attempt |
| Live monitor | faculty | One tile per sitting, ordered by risk; event feed; per-student timeline |
| Analytics | faculty | Score histogram, item analysis (difficulty, discrimination, distractors), result sheet PDF |
| My exams | student | Start or resume, timer ring, palette, flag for review, autosave, submit |

## Architecture

`ui` -> `service` -> `dao` -> H2, with pure-logic packages the services call:
`grading` (one `Grader` per question type), `generation` (paper strategies),
`proctor` (rules, engine, `IntegrityScorer`), `analytics` (`ItemAnalyzer`), `export` (CSV, JSON, PDF).

Things that were decided on purpose and are easy to miss:

- **Frozen paper.** Each attempt stores its own question and option order, so resume and audit
  show exactly what the student saw. Papers are seeded by exam and student, so regenerating one
  gives the same result.
- **No lost answers.** Autosave upserts one row every 5 seconds; every change also goes to an
  AES-GCM recovery cache on disk. Submit writes answers, status and scores in one transaction;
  a grading failure still submits (unscored) rather than losing the answers.
- **Submit is race-safe.** The close is `UPDATE ... WHERE status = 'IN_PROGRESS'`, so the Submit
  button and the expiry timer firing together cannot both win (tested with 8 threads).
- **Clock safe.** The on-screen countdown runs on the monotonic clock, so changing the system
  clock mid-exam neither adds nor removes time.
- **Integrity is separate.** It is computed just after submit, outside the submit transaction,
  and each event type is capped so one noisy rule cannot zero a student.

Diagrams: [ER](docs/ER.md), [classes](docs/CLASSES.md). Rehearsed ten-minute click path:
[docs/DEMO.md](docs/DEMO.md).

## Data

File-based H2 at `./data/exampro.mv.db`, created on first launch. Point the app at another
database with `-Dexampro.jdbcUrl=...` (the tests use in-memory ones).

## Known gaps

- No faculty dashboard page and no drag-and-drop option reordering (up/down buttons instead).
- Colours are written as literals in `app.css`, not shared variables.
- PDFs use the built-in Helvetica font, so characters outside Latin-1 print as `?`.
- Proctoring watches the app window only (focus, clipboard, idle, display count); it does not
  record camera or screen.
- Verified in the GUI by driving it with scripted clicks and screenshots; animation timing
  has not been judged by a human eye.
