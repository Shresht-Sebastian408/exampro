package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.exception.AttemptAlreadySubmittedException;
import com.exampro.exception.ExamExpiredException;
import com.exampro.exception.ExamNotStartedException;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.Topic;
import com.exampro.model.entity.User;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.RecoveryCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week six: frozen paper, resume, autosave, recovery cache, submission guard. */
class DeliveryTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_delivery_test;DB_CLOSE_DELAY=-1");
    }

    private static Topic topic;
    private static User student;
    private static User other;
    private static int counter;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        long subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        topic = new TopicDaoImpl().findBySubject(subjectId).stream().filter(t -> !t.isRoot()).findFirst().orElseThrow();
        student = new UserDaoImpl().findByUsername("student01").orElseThrow();
        other = new UserDaoImpl().findByUsername("student02").orElseThrow();

        QuestionBankService bank = new QuestionBankService();
        for (int i = 0; i < 6; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setStem("delivery test " + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private Exam exam(boolean published, LocalDateTime opens, LocalDateTime closes) {
        Exam e = new Exam();
        e.setSubjectId(topic.getSubjectId());
        e.setTitle("delivery-" + counter++);
        e.setPublished(published);
        e.setOpensAt(opens);
        e.setClosesAt(closes);
        e.setDurationMinutes(30);
        new ExamDaoImpl().insert(e);

        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM, QuestionType.MCQ, 4));
        new BlueprintService().save(b);
        return e;
    }

    private Exam openExam() {
        return exam(true, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(2));
    }

    private ExamDeliveryService service(Path dir) {
        return new ExamDeliveryService(new ExamDaoImpl(), new com.exampro.dao.impl.AttemptDaoImpl(),
                                       new com.exampro.service.PaperGenerationService(), new RecoveryCache(dir));
    }

    @Test
    void paperIsFrozenAndResumeReturnsTheSameQuestionsInTheSameOrder(@TempDir Path dir) {
        ExamDeliveryService svc = service(dir);
        Exam e = openExam();

        var first = svc.start(e, student);
        assertEquals(4, first.paper().size());
        assertEquals(AttemptStatus.IN_PROGRESS, first.attempt().getStatus());

        var again = svc.start(e, student);
        assertEquals(first.attempt().getId(), again.attempt().getId());
        assertEquals(first.paper().stream().map(Question::getId).toList(),
                     again.paper().stream().map(Question::getId).toList());
        assertEquals(first.paper().get(0).getOptions().stream().map(o -> o.getId()).toList(),
                     again.paper().get(0).getOptions().stream().map(o -> o.getId()).toList(),
                     "option order is frozen too");
    }

    @Test
    void unpublishedAndClosedExamsRefuseToStart(@TempDir Path dir) {
        ExamDeliveryService svc = service(dir);
        assertThrows(ExamNotStartedException.class, () -> svc.start(
            exam(false, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)), student));
        assertThrows(ExamNotStartedException.class, () -> svc.start(
            exam(true, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)), student));
        assertThrows(ExamExpiredException.class, () -> svc.start(
            exam(true, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)), student));
    }

    @Test
    void autosavedAnswersComeBackAndUpsertDoesNotDuplicate(@TempDir Path dir) {
        ExamDeliveryService svc = service(dir);
        var s = svc.start(openExam(), student);
        Question q = s.paper().get(0);

        Response r = new Response(q.getId());
        r.setAttemptId(s.attempt().getId());
        r.getSelectedOptionIds().add(q.getOptions().get(0).getId());
        svc.saveResponse(r);
        r.setFlagged(true);
        svc.saveResponse(r); // same row again

        var back = svc.start(s.exam(), student);
        assertEquals(1, back.responses().size());
        Response saved = back.responses().get(q.getId());
        assertEquals(List.of(q.getOptions().get(0).getId()), saved.getSelectedOptionIds());
        assertTrue(saved.isFlagged());
    }

    @Test
    void recoveryCacheFillsInWhatAutosaveMissed(@TempDir Path dir) throws Exception {
        ExamDeliveryService svc = service(dir);
        var s = svc.start(openExam(), student);
        Question q = s.paper().get(1);

        Response r = new Response(q.getId());
        r.setAttemptId(s.attempt().getId());
        r.setTextAnswer("typed but never autosaved");
        svc.cache().save(s.attempt().getId(), List.of(r)); // the "crash" happens here

        var recovered = svc.start(s.exam(), student);
        assertEquals("typed but never autosaved", recovered.responses().get(q.getId()).getTextAnswer());

        byte[] raw = Files.readAllBytes(dir.resolve("attempt-" + s.attempt().getId() + ".bin"));
        assertFalse(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).contains("typed but never"),
                    "cache must not be plain text");
    }

    @Test
    void submitClosesOnceAndLocksTheAttempt(@TempDir Path dir) {
        ExamDeliveryService svc = service(dir);
        Exam e = openExam();
        var s = svc.start(e, student);
        Question q = s.paper().get(0);

        Response r = new Response(q.getId());
        r.setAttemptId(s.attempt().getId());
        r.getSelectedOptionIds().add(q.getOptions().get(0).getId());
        svc.submit(s.attempt(), List.of(r), false);

        assertEquals(AttemptStatus.SUBMITTED, s.attempt().getStatus());
        assertThrows(AttemptAlreadySubmittedException.class, () -> svc.submit(s.attempt(), List.of(), false));
        assertThrows(AttemptAlreadySubmittedException.class, () -> svc.start(e, student));
        assertEquals(1, new com.exampro.dao.impl.AttemptDaoImpl().loadResponses(s.attempt().getId()).size());
    }

    @Test
    void anExpiredInProgressAttemptIsAutoSubmittedWithItsSavedAnswers(@TempDir Path dir) {
        ExamDeliveryService svc = service(dir);
        Exam e = openExam();
        var s = svc.start(e, other);

        // Wind the attempt's clock back past its expiry.
        s.attempt().setExpiresAt(LocalDateTime.now().minusMinutes(1));
        try (var c = ConnectionPool.getConnection();
             var ps = c.prepareStatement("UPDATE exam_attempts SET expires_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.valueOf(s.attempt().getExpiresAt()));
            ps.setLong(2, s.attempt().getId());
            ps.executeUpdate();
        } catch (java.sql.SQLException ex) {
            throw new RuntimeException(ex);
        }

        assertThrows(ExamExpiredException.class, () -> svc.start(e, other));
        assertEquals(AttemptStatus.AUTO_SUBMITTED, svc.attemptOf(e, other).orElseThrow().getStatus());
    }
}
