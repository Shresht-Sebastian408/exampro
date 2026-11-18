package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.GradingDao;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.exception.ValidationException;
import com.exampro.grading.ScoreSheet;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.Rubric;
import com.exampro.model.entity.Topic;
import com.exampro.model.entity.User;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.MSQQuestion;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.GradingService;
import com.exampro.service.ManualGradingService;
import com.exampro.service.PaperGenerationService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.RecoveryCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week seven: the four graders, scoring on submit, rubric-based manual grading. */
class GradingTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_grading_test;DB_CLOSE_DELAY=-1");
    }

    private static final GradingService grading = new GradingService();
    private static Topic topic;
    private static Topic plainTopic;   // its short answer never gets a rubric
    private static User teacher;
    private static int counter;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        long subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        topic = new TopicDaoImpl().findBySubject(subjectId).stream().filter(t -> !t.isRoot()).findFirst().orElseThrow();
        plainTopic = new TopicDaoImpl().findBySubject(subjectId).stream()
            .filter(t -> !t.isRoot() && !t.getId().equals(topic.getId())).findFirst().orElseThrow();
        teacher = new UserDaoImpl().findByUsername("faculty1").orElseThrow();

        QuestionBankService bank = new QuestionBankService();
        for (int i = 0; i < 4; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setStem("grading mcq " + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
        Question plainMcq = QuestionBankService.blank(QuestionType.MCQ);
        plainMcq.setTopicId(plainTopic.getId());
        plainMcq.setStem("plain mcq");
        plainMcq.getOptions().get(0).setText("right");
        plainMcq.getOptions().get(1).setText("wrong");
        bank.save(plainMcq);
        ShortAnswerQuestion plainShort = (ShortAnswerQuestion) QuestionBankService.blank(QuestionType.SHORT_ANSWER);
        plainShort.setTopicId(plainTopic.getId());
        plainShort.setStem("plain short");
        plainShort.setMarks(4);
        plainShort.getKeywords().add("alpha");
        bank.save(plainShort);
        for (int i = 0; i < 3; i++) {
            ShortAnswerQuestion q = (ShortAnswerQuestion) QuestionBankService.blank(QuestionType.SHORT_ANSWER);
            q.setTopicId(topic.getId());
            q.setStem("grading short " + i);
            q.setMarks(4);
            q.getKeywords().add("alpha");
            q.getKeywords().add("beta");
            bank.save(q);
        }
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    // ---- pure grader behaviour ----------------------------------------

    private static QuestionOption opt(long id, boolean correct) {
        QuestionOption o = new QuestionOption("o" + id, correct, 0);
        o.setId(id);
        return o;
    }

    private static Response answer(long questionId, Long... optionIds) {
        Response r = new Response(questionId);
        r.getSelectedOptionIds().addAll(List.of(optionIds));
        return r;
    }

    private double score(Question q, Response r) {
        q.setId(1L);
        return grading.grade(List.of(q), r == null ? Map.of() : Map.of(1L, r)).total();
    }

    @Test
    void mcqRightWrongBlankAndNegativeMarking() {
        Question q = QuestionBankService.blank(QuestionType.MCQ);
        q.getOptions().clear();
        q.getOptions().add(opt(10, true));
        q.getOptions().add(opt(11, false));
        q.setMarks(2);
        q.setNegativeMarks(0.5);

        assertEquals(2.0, score(q, answer(1, 10L)));
        assertEquals(-0.5, score(q, answer(1, 11L)));
        assertEquals(0.0, score(q, null), "blank costs nothing");
        assertEquals(0.0, score(q, answer(1)), "empty selection is blank");
    }

    @Test
    void msqGivesPartialCreditButAnyWrongTickScoresNothing() {
        MSQQuestion q = (MSQQuestion) QuestionBankService.blank(QuestionType.MSQ);
        q.getOptions().clear();
        q.getOptions().add(opt(20, true));
        q.getOptions().add(opt(21, true));
        q.getOptions().add(opt(22, false));
        q.setMarks(4);
        q.setNegativeMarks(1);

        assertEquals(4.0, score(q, answer(1, 20L, 21L)));
        assertEquals(2.0, score(q, answer(1, 20L)), "half the correct options, half the marks");
        assertEquals(-1.0, score(q, answer(1, 20L, 22L)), "a wrong tick voids the credit and costs the penalty");
    }

    @Test
    void numericHonoursAbsoluteTolerance() {
        NumericQuestion q = (NumericQuestion) QuestionBankService.blank(QuestionType.NUMERIC);
        q.setAnswer(2.5);
        q.setTolerance(0.1);
        q.setMarks(3);

        Response near = new Response(1L);
        near.setNumericAnswer(2.59);
        Response far = new Response(1L);
        far.setNumericAnswer(2.7);
        assertEquals(3.0, score(q, near));
        assertEquals(0.0, score(q, far));
    }

    @Test
    void shortAnswerIsAdvisoryAndLeavesTheFinalMarkOpen() {
        ShortAnswerQuestion q = (ShortAnswerQuestion) QuestionBankService.blank(QuestionType.SHORT_ANSWER);
        q.getKeywords().add("alpha");
        q.getKeywords().add("beta");
        q.setMarks(4);
        q.setId(1L);
        Response r = new Response(1L);
        r.setTextAnswer("only ALPHA here");

        ScoreSheet sheet = grading.grade(List.of(q), Map.of(1L, r));
        assertTrue(sheet.needsManual());
        assertEquals(2.0, sheet.byQuestion().get(1L).autoScore());
        assertEquals(null, sheet.byQuestion().get(1L).finalScore());
        assertEquals(0.0, sheet.total(), "an open question adds nothing to the total yet");

        Response blank = new Response(1L);
        blank.setTextAnswer("   ");
        assertEquals(false, grading.grade(List.of(q), Map.of(1L, blank)).needsManual(), "a blank answer needs no marker");
    }

    // ---- scoring on submit, then manual grading -----------------------

    private Exam exam(int mcq, int shortAnswers) {
        return exam(mcq, shortAnswers, topic);
    }

    private Exam exam(int mcq, int shortAnswers, Topic topic) {
        Exam e = new Exam();
        e.setSubjectId(topic.getSubjectId());
        e.setTitle("grading-" + counter++);
        e.setPublished(true);
        e.setOpensAt(LocalDateTime.now().minusHours(1));
        e.setClosesAt(LocalDateTime.now().plusHours(2));
        e.setShuffleQuestions(false);
        new ExamDaoImpl().insert(e);

        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM, QuestionType.MCQ, mcq));
        if (shortAnswers > 0) {
            b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM,
                                               QuestionType.SHORT_ANSWER, shortAnswers));
        }
        new BlueprintService().save(b);
        return e;
    }

    private ExamDeliveryService delivery(Path dir) {
        return new ExamDeliveryService(new ExamDaoImpl(), new AttemptDaoImpl(),
                                       new PaperGenerationService(), new RecoveryCache(dir));
    }

    /** Right answer for every MCQ, a keyword-bearing text for every short answer. */
    private List<Response> answerEverything(ExamDeliveryService.Sitting s) {
        return s.paper().stream().map(q -> {
            Response r = new Response(q.getId());
            r.setAttemptId(s.attempt().getId());
            if (q.getType() == QuestionType.MCQ) {
                r.getSelectedOptionIds().add(q.correctOptions().get(0).getId());
            } else {
                r.setTextAnswer("alpha and beta");
            }
            return r;
        }).toList();
    }

    @Test
    void anObjectiveOnlyExamIsScoredAndGradedInTheSubmitCommit(@TempDir Path dir) {
        ExamDeliveryService svc = delivery(dir);
        Exam e = exam(3, 0);
        User student = new UserDaoImpl().findByUsername("student03").orElseThrow();
        var s = svc.start(e, student);

        svc.submit(s.attempt(), answerEverything(s), false);

        Attempt stored = svc.attemptOf(e, student).orElseThrow();
        assertEquals(AttemptStatus.GRADED, stored.getStatus());
        assertEquals(3.0, stored.getTotalScore());
    }

    @Test
    void shortAnswersWaitForAPersonThenRubricGradingFinishesTheAttempt(@TempDir Path dir) {
        ExamDeliveryService svc = delivery(dir);
        ManualGradingService manual = new ManualGradingService();
        Exam e = exam(2, 1);
        User student = new UserDaoImpl().findByUsername("student04").orElseThrow();
        var s = svc.start(e, student);
        svc.submit(s.attempt(), answerEverything(s), false);

        Attempt waiting = svc.attemptOf(e, student).orElseThrow();
        assertEquals(AttemptStatus.SUBMITTED, waiting.getStatus(), "not graded while a marker is outstanding");
        assertEquals(2.0, waiting.getTotalScore(), "objective marks are already counted");

        GradingDao.PendingItem item = manual.pending().stream()
            .filter(p -> p.attemptId() == waiting.getId()).findFirst().orElseThrow();
        Question q = s.paper().stream().filter(x -> x.getId() == item.questionId()).findFirst().orElseThrow();

        // a rubric must add up to the question's marks (4)
        assertThrows(ValidationException.class, () -> manual.saveRubric(q,
            List.of(new Rubric.Criterion(null, "Definition", 1))));
        manual.saveRubric(q, List.of(new Rubric.Criterion(null, "Definition", 2),
                                     new Rubric.Criterion(null, "Example", 2)));
        Rubric rubric = manual.rubricFor(q).orElseThrow();

        Map<Long, Double> tooMuch = Map.of(rubric.getCriteria().get(0).id(), 3.0,
                                           rubric.getCriteria().get(1).id(), 1.0);
        assertThrows(ValidationException.class,
                     () -> manual.grade(item.responseId(), teacher.getId(), q, tooMuch, ""));

        double awarded = manual.grade(item.responseId(), teacher.getId(), q,
            Map.of(rubric.getCriteria().get(0).id(), 1.5, rubric.getCriteria().get(1).id(), 2.0), "good");
        assertEquals(3.5, awarded);

        Attempt done = svc.attemptOf(e, student).orElseThrow();
        assertEquals(AttemptStatus.GRADED, done.getStatus());
        assertEquals(5.5, done.getTotalScore());
        assertTrue(manual.pending().stream().noneMatch(p -> p.attemptId() == done.getId()));
    }

    @Test
    void withoutARubricAPlainMarkIsAccepted(@TempDir Path dir) {
        ExamDeliveryService svc = delivery(dir);
        ManualGradingService manual = new ManualGradingService();
        Exam e = exam(1, 1, plainTopic);
        User student = new UserDaoImpl().findByUsername("student05").orElseThrow();
        var s = svc.start(e, student);
        svc.submit(s.attempt(), answerEverything(s), false);

        GradingDao.PendingItem item = manual.pending().stream()
            .filter(p -> p.attemptId() == s.attempt().getId()).findFirst().orElseThrow();
        Question q = s.paper().stream().filter(x -> x.getId() == item.questionId()).findFirst().orElseThrow();
        assertThrows(ValidationException.class, () -> manual.grade(
            item.responseId(), teacher.getId(), q, java.util.Collections.singletonMap(null, 9.0), ""));
        manual.grade(item.responseId(), teacher.getId(), q, java.util.Collections.singletonMap(null, 3.0), "");
        Attempt done = svc.attemptOf(e, student).orElseThrow();
        assertEquals(AttemptStatus.GRADED, done.getStatus());
        assertEquals(4.0, done.getTotalScore());
    }

    @Test
    void regradeRecomputesFromTheFrozenPaper(@TempDir Path dir) {
        ExamDeliveryService svc = delivery(dir);
        Exam e = exam(2, 0);
        User student = new UserDaoImpl().findByUsername("student06").orElseThrow();
        var s = svc.start(e, student);
        svc.submit(s.attempt(), answerEverything(s), false);

        ScoreSheet again = grading.regrade(s.attempt().getId());
        assertEquals(2.0, again.total());
        assertEquals(2.0, svc.attemptOf(e, student).orElseThrow().getTotalScore());
    }
}
