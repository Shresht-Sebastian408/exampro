# Entity-relationship diagram

Mermaid renders this on GitHub and in most Markdown viewers. Tables come straight from
`SchemaDefinition`; only the columns that matter for the relationships are shown.

```mermaid
erDiagram
    USERS ||--o{ USER_ROLES : has
    ROLES ||--o{ USER_ROLES : grants
    USERS ||--o{ SESSIONS : opens
    USERS ||--o{ AUDIT_LOGS : causes

    SUBJECTS ||--o{ TOPICS : contains
    TOPICS ||--o{ TOPICS : "parent of"
    TOPICS ||--o{ QUESTIONS : holds
    QUESTIONS ||--o{ QUESTION_OPTIONS : offers
    QUESTIONS ||--o{ QUESTION_TAGS : tagged
    TAGS ||--o{ QUESTION_TAGS : labels
    QUESTIONS ||--o| RUBRICS : "marked by"
    RUBRICS ||--o{ RUBRIC_CRITERIA : lists

    SUBJECTS ||--o{ EXAMS : "set for"
    EXAMS ||--o| EXAM_BLUEPRINTS : "built from"
    EXAM_BLUEPRINTS ||--o{ BLUEPRINT_RULES : "made of"
    TOPICS ||--o{ BLUEPRINT_RULES : "drawn from"

    EXAMS ||--o{ EXAM_ATTEMPTS : "sat as"
    USERS ||--o{ EXAM_ATTEMPTS : takes
    EXAM_ATTEMPTS ||--o{ ATTEMPT_QUESTIONS : "frozen paper"
    QUESTIONS ||--o{ ATTEMPT_QUESTIONS : appears
    EXAM_ATTEMPTS ||--o{ RESPONSES : records
    QUESTIONS ||--o{ RESPONSES : answers
    RESPONSES ||--o{ MANUAL_GRADES : "graded by hand"
    RUBRIC_CRITERIA ||--o{ MANUAL_GRADES : scores

    EXAM_ATTEMPTS ||--o{ PROCTOR_EVENTS : flags
    EXAM_ATTEMPTS ||--o| INTEGRITY_SCORES : summarised
    EXAM_ATTEMPTS ||--o{ PROCTOR_SNAPSHOTS : captures
    EXAMS ||--o{ ITEM_STATISTICS : analysed
    QUESTIONS ||--o{ ITEM_STATISTICS : measured

    EXAM_ATTEMPTS {
        long id PK
        long exam_id FK
        long student_id FK
        string status "IN_PROGRESS SUBMITTED AUTO_SUBMITTED GRADED"
        datetime expires_at
        double total_score
        double integrity_score
    }
    ATTEMPT_QUESTIONS {
        long attempt_id FK
        long question_id FK
        int display_order
        string option_order "frozen per student"
    }
    RESPONSES {
        long attempt_id FK
        long question_id FK
        string selected_option_ids
        string text_answer
        double final_score
    }
    PROCTOR_EVENTS {
        long attempt_id FK
        string event_type
        string severity
        datetime occurred_at
    }
```

Design notes worth knowing when reading the schema:

- **The paper is frozen.** `attempt_questions` stores each student's question order and option
  order, so resuming (or auditing) always shows exactly what they saw.
- **Responses are upserted** (one row per attempt and question) by autosave; the final submit
  writes the remaining answers, closes the attempt and stores the scores in one transaction.
- **Soft delete** on subjects, topics, questions and users (`is_active`); exam history is never
  orphaned.
- **Integrity is separate from the submit commit.** A failure computing it can never lose a
  student's answers.
