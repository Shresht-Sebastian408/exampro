package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.entity.Topic;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.enums.Severity;
import com.exampro.model.question.Question;
import com.exampro.proctor.IntegrityScore;
import com.exampro.proctor.IntegrityScorer;
import com.exampro.proctor.ProctorContext;
import com.exampro.proctor.ProctorEngine;
import com.exampro.proctor.ProctorSignals;
import com.exampro.proctor.rules.AnswerVelocityRule;
import com.exampro.proctor.rules.ClipboardRule;
import com.exampro.proctor.rules.FocusLossRule;
import com.exampro.proctor.rules.IdleTimeoutRule;
import com.exampro.proctor.rules.MultiDisplayRule;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.PaperGenerationService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.RecoveryCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week eight: rules, signals, engine, scorer, and what lands in the database. */
class ProctorTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_proctor_test;DB_CLOSE_DELAY=-1");
    }

    private static Topic topic;

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
            q.setStem("proctor test " + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private static ProctorContext ctx(boolean focused, Instant lost, Instant regained, Instant lastActivity,
                                      int copies, int pastes, List<Long> latencies, int screens) {
        return new ProctorContext(1L, Instant.now(), focused, lost, regained, lastActivity,
                                  copies, pastes, latencies, screens);
    }

    private static ProctorContext quiet() {
        return ctx(true, null, null, Instant.now(), 0, 0, List.of(), 1);
    }

    // ---- rules ----------------------------------------------------------

    @Test
    void focusLossReportsOnceThenReportsTheReturnWithItsDuration() {
        FocusLossRule rule = new FocusLossRule(Duration.ofSeconds(15));
        Instant lost = Instant.now().minusSeconds(30);

        var away = rule.evaluate(ctx(false, lost, null, Instant.now(), 0, 0, List.of(), 1));
        assertEquals(EventType.FOCUS_LOST, away.orElseThrow().getType());
        assertTrue(rule.evaluate(ctx(false, lost, null, Instant.now(), 0, 0, List.of(), 1)).isEmpty(),
                   "still away: not reported twice");

        var back = rule.evaluate(ctx(true, lost, lost.plusSeconds(20), Instant.now(), 0, 0, List.of(), 1)).orElseThrow();
        assertEquals(EventType.FOCUS_REGAINED, back.getType());
        assertEquals(20_000L, back.getDurationMs());
        assertEquals(Severity.HIGH, back.getSeverity(), "20s away is past the long-absence line");
        assertTrue(rule.evaluate(quiet()).isEmpty());
    }

    @Test
    void aShortAbsenceReturnsAsInfoOnly() {
        FocusLossRule rule = new FocusLossRule(Duration.ofSeconds(15));
        Instant lost = Instant.now().minusSeconds(5);
        rule.evaluate(ctx(false, lost, null, Instant.now(), 0, 0, List.of(), 1));
        var back = rule.evaluate(ctx(true, lost, lost.plusSeconds(2), Instant.now(), 0, 0, List.of(), 1)).orElseThrow();
        assertEquals(Severity.INFO, back.getSeverity());
    }

    @Test
    void clipboardRulesAreIndependentPerKind() {
        var both = ctx(true, null, null, Instant.now(), 2, 1, List.of(), 1);
        assertEquals(EventType.COPY_ATTEMPT, ClipboardRule.forCopy().evaluate(both).orElseThrow().getType());
        var paste = ClipboardRule.forPaste().evaluate(both).orElseThrow();
        assertEquals(EventType.PASTE_ATTEMPT, paste.getType());
        assertEquals(Severity.HIGH, paste.getSeverity());
        assertTrue(ClipboardRule.forPaste().evaluate(quiet()).isEmpty());
    }

    @Test
    void idleFiresOncePerSpellAndRearmsOnActivity() {
        IdleTimeoutRule rule = new IdleTimeoutRule(Duration.ofMinutes(2));
        Instant old = Instant.now().minusSeconds(200);
        assertTrue(rule.evaluate(ctx(true, null, null, old, 0, 0, List.of(), 1)).isPresent());
        assertTrue(rule.evaluate(ctx(true, null, null, old, 0, 0, List.of(), 1)).isEmpty(), "same spell");
        assertTrue(rule.evaluate(quiet()).isEmpty(), "activity resumed");
        assertTrue(rule.evaluate(ctx(true, null, null, old, 0, 0, List.of(), 1)).isPresent(), "a new spell");
    }

    @Test
    void rapidAnswersOnlyBelowTheFloor() {
        AnswerVelocityRule rule = new AnswerVelocityRule(Duration.ofSeconds(3));
        assertTrue(rule.evaluate(ctx(true, null, null, Instant.now(), 0, 0, List.of(8000L, 12000L), 1)).isEmpty());
        var hit = rule.evaluate(ctx(true, null, null, Instant.now(), 0, 0, List.of(8000L, 900L), 1)).orElseThrow();
        assertEquals(EventType.RAPID_ANSWER, hit.getType());
        assertEquals(900L, hit.getDurationMs(), "carries the fastest latency");
    }

    @Test
    void secondScreenRaisesOnceUntilItGrows() {
        MultiDisplayRule rule = new MultiDisplayRule();
        assertTrue(rule.evaluate(ctx(true, null, null, Instant.now(), 0, 0, List.of(), 2)).isPresent());
        assertTrue(rule.evaluate(ctx(true, null, null, Instant.now(), 0, 0, List.of(), 2)).isEmpty());
        assertTrue(rule.evaluate(ctx(true, null, null, Instant.now(), 0, 0, List.of(), 3)).isPresent());
    }

    // ---- signals ---------------------------------------------------------

    @Test
    void aFocusBlipBetweenTicksIsStillSeen() {
        ProctorSignals signals = new ProctorSignals();
        signals.focusChanged(false);
        signals.focusChanged(true);            // back before the tick fires
        ProctorContext first = signals.snapshot(1L, Instant.now());
        assertFalse(first.focused(), "the tick must see that focus was lost");
        assertTrue(signals.snapshot(1L, Instant.now()).focused(), "and only once");
    }

    @Test
    void countersClearAfterEachSnapshot() {
        ProctorSignals signals = new ProctorSignals();
        signals.pasteAttempt();
        signals.copyAttempt();
        signals.copyAttempt();
        signals.answerLatency(500);
        ProctorContext c = signals.snapshot(1L, Instant.now());
        assertEquals(1, c.pastes());
        assertEquals(2, c.copies());
        assertEquals(List.of(500L), c.answerLatenciesMs());
        ProctorContext again = signals.snapshot(1L, Instant.now());
        assertEquals(0, again.pastes() + again.copies());
        assertTrue(again.answerLatenciesMs().isEmpty());
    }

    // ---- engine ------------------------------------------------------------

    @Test
    void aBrokenListenerDoesNotStopTheOthersAndADisabledRuleStaysQuiet() {
        var paste = ClipboardRule.forPaste();
        ProctorEngine engine = new ProctorEngine(List.of(paste, ClipboardRule.forCopy()));
        List<ProctorEvent> heard = new ArrayList<>();
        engine.addListener(e -> { throw new IllegalStateException("db down"); });
        engine.addListener(heard::add);

        var both = ctx(true, null, null, Instant.now(), 1, 1, List.of(), 1);
        assertEquals(2, engine.tick(both).size());
        assertEquals(2, heard.size());

        paste.setEnabled(false);
        assertEquals(1, engine.tick(both).size(), "disabled rule raises nothing");
    }

    // ---- scorer ------------------------------------------------------------

    private static ProctorEvent ev(EventType t, Severity s) {
        return new ProctorEvent(1L, t, s, null, null);
    }

    @Test
    void scorerSubtractsByWeightCapsPerTypeAndIgnoresInfo() {
        IntegrityScorer scorer = new IntegrityScorer();
        IntegrityScore s = scorer.score(List.of(
            ev(EventType.PASTE_ATTEMPT, Severity.HIGH),          // 7
            ev(EventType.FOCUS_LOST, Severity.MEDIUM),           // 3
            ev(EventType.FOCUS_REGAINED, Severity.INFO)));       // 0
        assertEquals(90.0, s.score());
        assertEquals(10.0, s.penalty());

        List<ProctorEvent> spam = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            spam.add(ev(EventType.RAPID_ANSWER, Severity.LOW));  // 100 x 1, capped at 40
        }
        assertEquals(60.0, scorer.score(spam).score(), "one kind of event cannot cost more than the cap");
        assertEquals(100, scorer.score(spam).counts().get(EventType.RAPID_ANSWER));
    }

    @Test
    void weightsAreConfigurableAndTheScoreNeverGoesBelowZero() {
        var harsh = new IntegrityScorer(Map.of(Severity.HIGH, 60), 1000);
        assertEquals(40.0, harsh.score(List.of(ev(EventType.PASTE_ATTEMPT, Severity.HIGH))).score());
        assertEquals(0.0, harsh.score(List.of(ev(EventType.PASTE_ATTEMPT, Severity.HIGH),
                                              ev(EventType.MULTI_DISPLAY_DETECTED, Severity.HIGH))).score());
    }

    // ---- persistence and submit ------------------------------------------

    @Test
    void submitStoresTheIntegrityScoreAndAnAuditRecord(@TempDir Path dir) throws Exception {
        ExamDeliveryService svc = new ExamDeliveryService(new ExamDaoImpl(), new AttemptDaoImpl(),
            new PaperGenerationService(), new RecoveryCache(dir));
        Exam e = new Exam();
        e.setSubjectId(topic.getSubjectId());
        e.setTitle("proctored");
        e.setPublished(true);
        e.setOpensAt(LocalDateTime.now().minusHours(1));
        e.setClosesAt(LocalDateTime.now().plusHours(2));
        new ExamDaoImpl().insert(e);
        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM, QuestionType.MCQ, 2));
        new BlueprintService().save(b);

        User student = new UserDaoImpl().findByUsername("student07").orElseThrow();
        var sitting = svc.start(e, student);

        // The engine writes what it sees to the database through its listener.
        ProctorEngine engine = svc.proctoring().engineFor();
        engine.tick(ctx2(sitting.attempt().getId(), 0, 1));   // one paste
        engine.tick(ctx2(sitting.attempt().getId(), 1, 0));   // one copy

        svc.submit(sitting.attempt(), List.of(), false);

        try (var c = ConnectionPool.getConnection();
             var ps = c.prepareStatement("SELECT score FROM integrity_scores WHERE attempt_id = ?")) {
            ps.setLong(1, sitting.attempt().getId());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "integrity row written at submit");
                assertEquals(90.0, rs.getDouble(1));      // 100 - 7 (paste HIGH) - 3 (copy MEDIUM)
            }
        }
        try (var c = ConnectionPool.getConnection();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'ATTEMPT_SUBMITTED'")) {
            ps.setLong(1, sitting.attempt().getId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private static ProctorContext ctx2(long attemptId, int copies, int pastes) {
        return new ProctorContext(attemptId, Instant.now(), true, null, null, Instant.now(),
                                  copies, pastes, List.of(), 1);
    }
}
