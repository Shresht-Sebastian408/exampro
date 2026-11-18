package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.exception.AttemptAlreadySubmittedException;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.Topic;
import com.exampro.model.entity.User;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.enums.Role;
import com.exampro.model.question.Question;
import com.exampro.service.AnalyticsService;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.PaperGenerationService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.RecoveryCache;
import com.exampro.service.ResultsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week eleven: races, and a realistic amount of data. */
class HardeningTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_hardening_test;DB_CLOSE_DELAY=-1");
    }

    private static Topic topic;
    private static int counter;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        long subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        topic = new TopicDaoImpl().findBySubject(subjectId).stream().filter(t -> !t.isRoot()).findFirst().orElseThrow();
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private static void addQuestions(int n) {
        QuestionBankService bank = new QuestionBankService();
        for (int i = 0; i < n; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setStem("hardening " + counter + "-" + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
    }

    private Exam exam(int questions) {
        Exam e = new Exam();
        e.setSubjectId(topic.getSubjectId());
        e.setTitle("hardening-" + counter++);
        e.setPublished(true);
        e.setOpensAt(LocalDateTime.now().minusHours(1));
        e.setClosesAt(LocalDateTime.now().plusHours(2));
        e.setDurationMinutes(30);
        new ExamDaoImpl().insert(e);
        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM,
                                           QuestionType.MCQ, questions));
        new BlueprintService().save(b);
        return e;
    }

    private ExamDeliveryService delivery(Path dir) {
        return new ExamDeliveryService(new ExamDaoImpl(), new AttemptDaoImpl(),
                                       new PaperGenerationService(), new RecoveryCache(dir));
    }

    private static User student(String name) {
        return new UserDaoImpl().findByUsername(name).orElseThrow();
    }

    @Test
    void theSubmitButtonAndTheTimerRacingEachOtherCloseTheAttemptExactlyOnce(@TempDir Path dir) throws Exception {
        addQuestions(6);
        ExamDeliveryService svc = delivery(dir);
        var sitting = svc.start(exam(4), student("student01"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            boolean auto = i % 2 == 0;
            all.add(pool.submit(() -> {
                go.await();
                try {
                    svc.submit(sitting.attempt(), List.of(), auto);
                    won.incrementAndGet();
                } catch (AttemptAlreadySubmittedException e) {
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : all) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, won.get(), "exactly one submission wins");
        assertEquals(threads - 1, refused.get(), "everyone else is told it is already closed");
        AttemptStatus status = new AttemptDaoImpl().findByExamAndStudent(
            sitting.exam().getId(), student("student01").getId()).orElseThrow().getStatus();
        assertTrue(status == AttemptStatus.SUBMITTED || status == AttemptStatus.AUTO_SUBMITTED
                       || status == AttemptStatus.GRADED);
    }

    @Test
    void aDoubleClickOnStartLeavesOneAttemptNotTwo(@TempDir Path dir) throws Exception {
        addQuestions(6);
        ExamDeliveryService svc = delivery(dir);
        Exam e = exam(4);
        User u = student("student02");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            all.add(pool.submit(() -> {
                go.await();
                try {
                    return svc.start(e, u);
                } catch (RuntimeException ex) {
                    return ex;   // a refused duplicate is acceptable; a second attempt row is not
                }
            }));
        }
        go.countDown();
        for (Future<Object> f : all) {
            f.get();
        }
        pool.shutdown();

        try (var c = ConnectionPool.getConnection();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM exam_attempts WHERE exam_id = ? AND student_id = ?")) {
            ps.setLong(1, e.getId());
            ps.setLong(2, u.getId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void fiveHundredQuestionsHundredStudentsThreeExams(@TempDir Path dir) {
        long t0 = System.nanoTime();
        addQuestions(500);

        UserDaoImpl users = new UserDaoImpl();
        String hash = com.exampro.util.PasswordHasher.hash("student123");
        for (int i = 21; i <= 100; i++) {
            String name = String.format("student%02d", i);
            if (users.findByUsername(name).isEmpty()) {
                users.insert(new User(name, hash, "Student " + i, name + "@exampro.local", Role.STUDENT));
            }
        }

        ExamDeliveryService svc = delivery(dir);
        List<Exam> exams = List.of(exam(25), exam(25), exam(25));
        for (int s = 1; s <= 100; s++) {
            User u = student(String.format("student%02d", s));
            for (Exam e : exams) {
                var sitting = svc.start(e, u);
                List<Response> answers = new ArrayList<>();
                // student s answers the first (s % 26) questions correctly, the rest are left blank
                for (int i = 0; i < Math.min(s % 26, sitting.paper().size()); i++) {
                    Question q = sitting.paper().get(i);
                    Response r = new Response(q.getId());
                    r.setAttemptId(sitting.attempt().getId());
                    r.getSelectedOptionIds().add(q.correctOptions().get(0).getId());
                    answers.add(r);
                }
                svc.submit(sitting.attempt(), answers, false);
            }
        }
        long sat = System.nanoTime();

        AnalyticsService analytics = new AnalyticsService();
        for (Exam e : exams) {
            var report = analytics.compute(e.getId());
            assertEquals(100, report.summary().attempts());
            // papers differ per student, so items cover every question anyone was given
            assertTrue(report.items().size() >= 25 && report.items().size() <= 500);
            assertEquals(100, new ResultsService().rows(e.getId()).size());
        }
        long done = System.nanoTime();

        System.out.printf("load: 300 sittings in %d ms, analytics for 3 exams in %d ms, total %d ms%n",
                          (sat - t0) / 1_000_000, (done - sat) / 1_000_000, (done - t0) / 1_000_000);
        assertTrue((done - t0) / 1_000_000 < 120_000, "whole load scenario finishes inside two minutes");
    }
}
