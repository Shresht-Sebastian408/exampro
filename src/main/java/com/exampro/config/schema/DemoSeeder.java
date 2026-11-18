package com.exampro.config.schema;

import com.exampro.config.ConnectionPool;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.ProctorEventDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.enums.Severity;
import com.exampro.model.question.Question;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.QuestionBankService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional extra data for the ten-minute demo: a published exam that 18 students
 * have already sat (spread of scores, a few proctor flags) and two students
 * still mid-exam, so Analytics, Grading results and the Live monitor all have
 * something to show. Run once: {@code java -cp exampro-1.0.0-shaded.jar
 * com.exampro.config.schema.DemoSeeder}. Safe to re-run, it skips if the exam exists.
 */
public final class DemoSeeder {

    static final String TITLE = "CS201 Midterm (demo)";
    private static final int POOL = 8;

    public static void main(String[] args) {
        try {
            new SchemaInitializer().initialize();
            new DataSeeder().seedIfEmpty();
            System.out.println(seed() ? "Demo exam added: " + TITLE : "Demo exam already there, nothing changed.");
        } finally {
            ConnectionPool.shutdown();
        }
    }

    static boolean seed() {
        ExamDaoImpl exams = new ExamDaoImpl();
        if (exams.findAll().stream().anyMatch(e -> TITLE.equals(e.getTitle()))) {
            return false;
        }
        long subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        Topic topic = new TopicDaoImpl().findBySubject(subjectId).stream()
            .filter(t -> !t.isRoot()).findFirst().orElseThrow();

        // A fixed pool the whole class draws from, ordered easy to hard by stem number.
        QuestionBankService bank = new QuestionBankService();
        String[] stems = {"Which structure gives O(1) average lookup?", "What does a stack's pop remove?",
            "Worst-case time of quicksort?", "Height of a balanced BST with n nodes?",
            "Which traversal visits the root first?", "Complexity of building a heap from n items?",
            "Which structure suits BFS?", "Amortised cost of dynamic array append?"};
        for (int i = 0; i < POOL; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setStem("[Demo " + (i + 1) + "] " + stems[i]);
            q.setDifficulty(i < 3 ? Difficulty.EASY : i < 6 ? Difficulty.MEDIUM : Difficulty.HARD);
            q.getOptions().get(0).setText("The correct answer");
            q.getOptions().get(1).setText("A plausible distractor");
            bank.save(q);
        }

        // One written answer, so the Grading queue has something for a person to mark.
        Question essay = QuestionBankService.blank(QuestionType.SHORT_ANSWER);
        essay.setTopicId(topic.getId());
        essay.setStem("[Demo SA] Why is a hash table lookup O(1) on average? Mention collisions.");
        essay.setDifficulty(Difficulty.MEDIUM);
        essay.setMarks(4);
        ((com.exampro.model.question.ShortAnswerQuestion) essay).getKeywords().addAll(List.of("hash", "collision"));
        bank.save(essay);
        new com.exampro.service.ManualGradingService().saveRubric(essay, List.of(
            new com.exampro.model.entity.Rubric.Criterion(null, "Explains hashing to an index", 2),
            new com.exampro.model.entity.Rubric.Criterion(null, "Discusses collisions", 2)));

        Exam e = new Exam();
        e.setSubjectId(subjectId);
        e.setTitle(TITLE);
        e.setPublished(true);
        e.setOpensAt(LocalDateTime.now().minusDays(1));
        e.setClosesAt(LocalDateTime.now().plusDays(7));
        e.setDurationMinutes(45);
        exams.insert(e);
        e.setPublished(true);   // insert does not write the flag
        exams.update(e);

        // Every difficulty rule asks for exactly what the pool holds, so all papers hold the same questions.
        int pool = new BlueprintService().available(topic.getId(), Difficulty.EASY, QuestionType.MCQ);
        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("Demo blueprint");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.EASY, QuestionType.MCQ, pool));
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM, QuestionType.MCQ,
            new BlueprintService().available(topic.getId(), Difficulty.MEDIUM, QuestionType.MCQ)));
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.HARD, QuestionType.MCQ,
            new BlueprintService().available(topic.getId(), Difficulty.HARD, QuestionType.MCQ)));
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM,
            QuestionType.SHORT_ANSWER, new BlueprintService().available(topic.getId(), Difficulty.MEDIUM,
                QuestionType.SHORT_ANSWER)));
        new BlueprintService().save(b);

        ExamDeliveryService svc = new ExamDeliveryService();
        UserDaoImpl users = new UserDaoImpl();
        ProctorEventDaoImpl events = new ProctorEventDaoImpl();
        for (int s = 1; s <= 20; s++) {
            var sitting = svc.start(e, users.findByUsername(String.format("student%02d", s)).orElseThrow());
            long id = sitting.attempt().getId();
            if (s == 3) {
                events.insert(new ProctorEvent(id, EventType.PASTE_ATTEMPT, Severity.HIGH, null, null));
            }
            if (s == 7 || s == 20) {
                for (int k = 0; k < 3; k++) {
                    events.insert(new ProctorEvent(id, EventType.FOCUS_LOST, Severity.MEDIUM, null, null));
                }
            }
            if (s == 19) {
                events.insert(new ProctorEvent(id, EventType.MULTI_DISPLAY_DETECTED, Severity.HIGH, null, null));
            }
            if (s >= 19) {
                continue;   // these two stay in progress for the live monitor
            }
            List<Question> byDifficulty = new ArrayList<>(sitting.paper().stream()
                .filter(q -> q.getType() == QuestionType.MCQ).toList());
            byDifficulty.sort(java.util.Comparator.comparing(Question::getDifficulty)
                .thenComparing(Question::getId));
            List<Response> answers = new ArrayList<>();
            for (int i = 0; i < byDifficulty.size(); i++) {
                // stronger students (higher s%7) get further; harder questions are missed more often
                if ((s * 3 + i * 5) % 7 < 7 - i * 7 / byDifficulty.size() - (s % 3 == 0 ? 2 : 0)) {
                    Question q = byDifficulty.get(i);
                    Response r = new Response(q.getId());
                    r.setAttemptId(id);
                    r.getSelectedOptionIds().add(q.correctOptions().get(0).getId());
                    answers.add(r);
                }
            }
            if (s <= 12) {
                for (Question q : sitting.paper()) {
                    if (q.getType() == QuestionType.SHORT_ANSWER) {
                        Response r = new Response(q.getId());
                        r.setAttemptId(id);
                        r.setTextAnswer(s % 2 == 0
                            ? "A hash function maps the key to a bucket index, so lookup is one step; collisions are chained."
                            : "It uses hashing.");
                        answers.add(r);
                    }
                }
            }
            svc.submit(sitting.attempt(), answers, false);
        }
        return true;
    }

    private DemoSeeder() {}
}
