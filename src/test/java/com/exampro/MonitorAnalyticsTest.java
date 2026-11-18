package com.exampro;

import com.exampro.analytics.ItemStat;
import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.AttemptDaoImpl;
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
import com.exampro.model.entity.User;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.enums.Severity;
import com.exampro.model.question.Question;
import com.exampro.service.AnalyticsService;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.MonitorService;
import com.exampro.service.PaperGenerationService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.RecoveryCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week nine on a real database: the monitor's ordering and the analytics round trip. */
class MonitorAnalyticsTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_monitor_test;DB_CLOSE_DELAY=-1");
    }

    private static Topic topic;
    private static int poolSize;
    private static int counter;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        long subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        topic = new TopicDaoImpl().findBySubject(subjectId).stream().filter(t -> !t.isRoot()).findFirst().orElseThrow();
        QuestionBankService bank = new QuestionBankService();
        for (int i = 0; i < 3; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setStem("monitor test " + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
        poolSize = new BlueprintService().available(topic.getId(), Difficulty.MEDIUM, QuestionType.MCQ);
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    /** Every student gets the whole pool, so papers are identical apart from order. */
    private Exam newExam() {
        Exam e = new Exam();
        e.setSubjectId(topic.getSubjectId());
        e.setTitle("monitor-" + counter++);
        e.setPublished(true);
        e.setOpensAt(LocalDateTime.now().minusHours(1));
        e.setClosesAt(LocalDateTime.now().plusHours(2));
        new ExamDaoImpl().insert(e);
        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM,
                                           QuestionType.MCQ, poolSize));
        new BlueprintService().save(b);
        return e;
    }

    private ExamDeliveryService delivery(Path dir) {
        return new ExamDeliveryService(new ExamDaoImpl(), new AttemptDaoImpl(),
                                       new PaperGenerationService(), new RecoveryCache(dir));
    }

    private User user(String name) {
        return new UserDaoImpl().findByUsername(name).orElseThrow();
    }

    private void event(long attemptId, EventType type, Severity sev) {
        new ProctorEventDaoImpl().insert(new ProctorEvent(attemptId, type, sev, null, null));
    }

    @Test
    void tilesPutRecentSeriousEventsFirstAndSubmittedAttemptsLast(@TempDir Path dir) throws Exception {
        ExamDeliveryService svc = delivery(dir);
        Exam e = newExam();
        var calm = svc.start(e, user("student11"));
        var nervous = svc.start(e, user("student12"));
        var cheating = svc.start(e, user("student13"));
        var done = svc.start(e, user("student14"));

        event(nervous.attempt().getId(), EventType.FOCUS_LOST, Severity.MEDIUM);
        event(cheating.attempt().getId(), EventType.PASTE_ATTEMPT, Severity.HIGH);
        event(done.attempt().getId(), EventType.PASTE_ATTEMPT, Severity.HIGH);
        svc.submit(done.attempt(), List.of(), false);

        MonitorService.Snapshot snap = new MonitorService().snapshot(e.getId(), 10);
        List<Long> order = snap.tiles().stream().map(t -> t.attempt().getId()).toList();
        assertEquals(List.of(cheating.attempt().getId(), nervous.attempt().getId(),
                             calm.attempt().getId(), done.attempt().getId()), order,
                     "live+recent HIGH, then live by lowest integrity, then finished");
        assertEquals(93.0, snap.tiles().get(0).integrity().score());

        assertEquals(EventType.PASTE_ATTEMPT, snap.feed().get(0).event().getType(), "newest first");
        assertTrue(snap.feed().size() <= 10);
    }

    @Test
    void analyticsComputeFromRealSubmissionsAndAreStored(@TempDir Path dir) {
        ExamDeliveryService svc = delivery(dir);
        Exam e = newExam();
        int n = poolSize;

        // k correct answers each: n, n-1, n-2 (the lowest-id questions are answered right first)
        int[] correct = {n, n - 1, n - 2};
        String[] students = {"student15", "student16", "student17"};
        double[] percents = new double[3];
        for (int s = 0; s < 3; s++) {
            var sitting = svc.start(e, user(students[s]));
            List<Question> byId = sitting.paper().stream().sorted(Comparator.comparing(Question::getId)).toList();
            List<Response> answers = new java.util.ArrayList<>();
            for (int i = 0; i < correct[s]; i++) {
                Question q = byId.get(i);
                Response r = new Response(q.getId());
                r.setAttemptId(sitting.attempt().getId());
                r.getSelectedOptionIds().add(q.correctOptions().get(0).getId());
                answers.add(r);
            }
            svc.submit(sitting.attempt(), answers, false);
            percents[s] = 100.0 * correct[s] / n;
        }

        var report = new AnalyticsService().compute(e.getId());

        assertEquals(3, report.summary().attempts());
        assertEquals((percents[0] + percents[1] + percents[2]) / 3, report.summary().mean(), 1e-9);
        assertEquals(n, report.items().size());

        List<Long> ids = report.items().stream().map(ItemStat::questionId).sorted().toList();
        ItemStat hardest = report.items().stream().filter(i -> i.questionId() == ids.get(n - 1)).findFirst().orElseThrow();
        assertEquals(1.0 / 3, hardest.pValue(), 1e-9, "only the top student got the last question");
        assertEquals(Math.sqrt(3) / 2, hardest.discrimination(), 1e-9);
        assertEquals(2, hardest.blanks(), "the two who skipped it are counted as blank");

        ItemStat easiest = report.items().stream().filter(i -> i.questionId() == ids.get(0)).findFirst().orElseThrow();
        assertEquals(1.0, easiest.pValue());
        assertTrue(easiest.needsRevision(), "everyone right: too easy to tell anyone apart");

        // materialised into item_statistics, and recomputing replaces rather than duplicates
        new AnalyticsService().compute(e.getId());
        try (var c = ConnectionPool.getConnection();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM item_statistics WHERE exam_id = ?")) {
            ps.setLong(1, e.getId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(n, rs.getInt(1));
            }
        } catch (java.sql.SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void anExamNobodySatGivesAnEmptyReport() {
        var report = new AnalyticsService().compute(newExam().getId());
        assertEquals(0, report.summary().attempts());
        assertTrue(report.items().isEmpty());
    }
}
