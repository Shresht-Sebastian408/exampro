# Ten-minute demo

Setup, once, before the audience arrives:

```
mvnw.cmd package          # builds target\exampro-1.0.0-shaded.jar
run.bat demo              # adds "CS201 Midterm (demo)": 18 finished sittings, 2 still in progress
```

Start fresh any time with `mvnw.cmd exec:java -Dexec.mainClass=com.exampro.config.schema.ResetDatabase`
(then `run.bat demo` again). Launch the app with `run.bat`.

| Min | Log in as | Click path | What it shows |
| --- | --- | --- | --- |
| 0:00 | `faculty1` / `faculty123` | Login screen, then **Subjects** | Particle login, role-based sidebar, seeded subjects |
| 1:00 | | **Question bank**, pick CS201 | Topic tree, search and filters; open a question, switch its type in the editor, watch the live preview; move an option up or down; Export, then Import the CSV |
| 3:00 | | **Exams**, open *CS201 Midterm (demo)* | Blueprint rules with live availability; **Preview a paper**; **Export paper (PDF)** with the answer key |
| 4:30 | | **Live monitor**, choose the demo exam | Tiles for the two students still sitting (one flagged HIGH for a second display), event feed, per-student timeline |
| 5:30 | | **Analytics**, choose the demo exam | Score histogram, item analysis scatter (red = worth revising), distractor table; **Export result sheet (PDF)** |
| 7:00 | `student19` / `student123` | **My exams**, then **Start** on the demo exam | Resumes the unfinished sitting: timer ring, palette, flag a question, slide transition, autosave blip; alt-tab away for the proctor banner; **Submit** |
| 9:00 | back to `faculty1` | **Grading** | Twelve written answers wait for marks; pick one: keyword hint, integrity line, rubric boxes; enter marks and **Save grade** (the queue drops to 11) |
| 9:45 | | Wrap up | Point at the tests: `mvnw.cmd test` (82 tests, includes 100 students x 3 exams in about 3 seconds) |

Notes for the presenter:

- The demo exam is already sat by `student01` to `student18`; `student19` and `student20` are
  mid-exam, so you can log in as either to show **resume**. `student03` pasted (HIGH), `student07`
  and `student20` left the window three times (MEDIUM), `student19` plugged in a second display.
- To demonstrate crash recovery live: start an exam as a student, answer a question, kill the
  window from Task Manager, run `run.bat`, log in again, press **Start**. The answer is back.
- Autosave runs every few seconds, so wait for the "saved" dot before pulling the plug if you
  want to show the database path rather than the encrypted recovery cache.
