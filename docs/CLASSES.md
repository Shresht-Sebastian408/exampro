# Class diagrams

Layers, top to bottom: `ui` -> `service` -> `dao` -> H2. Everything else is a pure-logic
helper the services call (`grading`, `generation`, `proctor`, `analytics`, `export`).

## Layering

```mermaid
flowchart TD
    UI["ui.controller: views (JavaFX)"] --> SVC[service]
    SVC --> DAO["dao / dao.impl (JDBC)"]
    SVC --> LOGIC["grading, generation, proctor, analytics, export"]
    DAO --> DB[(H2)]
```

## Questions and grading (Strategy, polymorphic mapping)

```mermaid
classDiagram
    class Question {
        <<abstract>>
        +stem
        +difficulty
        +marks
        +correctOptions()
        +getAnswerSummary()
    }
    Question <|-- MCQQuestion
    Question <|-- MSQQuestion
    Question <|-- TrueFalseQuestion
    Question <|-- NumericQuestion
    Question <|-- ShortAnswerQuestion

    class Grader {
        <<interface>>
        +grade(T question, Response) GradeResult
        +supports(QuestionType) boolean
    }
    Grader <|.. MCQGrader
    Grader <|.. MSQGrader
    Grader <|.. NumericGrader
    Grader <|.. KeywordGrader
    class GradingService {
        EnumMap~QuestionType, Grader~
        +grade(paper, responses) ScoreSheet
    }
    GradingService o-- Grader
```

## Paper generation (Strategy)

```mermaid
classDiagram
    class PaperGenerationStrategy {
        <<interface>>
        +generate(Exam, User, QuestionPool)
    }
    PaperGenerationStrategy <|.. BlueprintStrategy
    PaperGenerationStrategy <|.. RandomPoolStrategy
    PaperGenerationStrategy <|.. AdaptiveDifficultyStrategy
    class PaperGenerationService
    PaperGenerationService --> PaperGenerationStrategy : picks by Exam.generation
```

Papers are stable per student: the random seed is derived from exam id and student id.

## Proctoring (Observer / rule chain)

```mermaid
classDiagram
    class ProctorEngine {
        +tick(ProctorContext)
        +addListener(ProctorEventListener)
    }
    class ProctorRule {
        <<interface>>
        +evaluate(ProctorContext) Optional~ProctorEvent~
    }
    ProctorRule <|.. FocusLossRule
    ProctorRule <|.. ClipboardRule
    ProctorRule <|.. IdleTimeoutRule
    ProctorRule <|.. AnswerVelocityRule
    ProctorRule <|.. MultiDisplayRule
    class ProctorEventListener {
        <<interface>>
        +onEvent(ProctorEvent)
    }
    ProctorEngine o-- ProctorRule
    ProctorEngine o-- ProctorEventListener
    class IntegrityScorer {
        +score(events) IntegrityScore
    }
```

UI signals (focus, clipboard, idle) feed a `ProctorContext` snapshot each second; rules turn
snapshots into events; listeners (the database writer in `ProctoringService`, the banner in `ExamTakingView`) persist them and show the banner. `IntegrityScorer` caps each
event type so one noisy rule cannot zero a student on its own.

## Delivery

```mermaid
classDiagram
    class ExamDeliveryService {
        +start(Exam, User) Sitting
        +saveResponse(Response)
        +submit(Attempt, responses, auto)
    }
    ExamDeliveryService --> PaperGenerationService
    ExamDeliveryService --> GradingService
    ExamDeliveryService --> ProctoringService
    ExamDeliveryService --> RecoveryCache : AES-GCM crash cache
    ExamDeliveryService --> AttemptDao
```
