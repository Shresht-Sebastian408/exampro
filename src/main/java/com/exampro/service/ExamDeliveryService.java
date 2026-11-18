package com.exampro.service;

import com.exampro.dao.AttemptDao;
import com.exampro.dao.ExamDao;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.exception.AttemptAlreadySubmittedException;
import com.exampro.exception.ExamExpiredException;
import com.exampro.exception.ExamNotStartedException;
import com.exampro.grading.ScoreSheet;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.User;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Starting, resuming, saving and submitting an attempt.
 *
 * The paper is generated once, on first start, and frozen into
 * attempt_questions; every later start (a refresh, a crash recovery) reads
 * that frozen paper back and never calls the generator again.
 */
public class ExamDeliveryService {

    /** What the exam screen needs: the attempt, its paper, and the answers so far. */
    public record Sitting(Attempt attempt, Exam exam, List<Question> paper,
                          Map<Long, Response> responses) {}

    private final ExamDao examDao;
    private final AttemptDao attemptDao;
    private final PaperGenerationService generator;
    private final RecoveryCache cache;
    private final GradingService grading;
    private final ProctoringService proctoring = new ProctoringService();

    public ExamDeliveryService() {
        this(new ExamDaoImpl(), new AttemptDaoImpl(), new PaperGenerationService(), new RecoveryCache());
    }

    public ExamDeliveryService(ExamDao examDao, AttemptDao attemptDao,
                               PaperGenerationService generator, RecoveryCache cache) {
        this.examDao = examDao;
        this.attemptDao = attemptDao;
        this.generator = generator;
        this.cache = cache;
        this.grading = new GradingService(attemptDao);
    }

    public ProctoringService proctoring() {
        return proctoring;
    }

    public RecoveryCache cache() {
        return cache;
    }

    /** Published exams whose window is open now. */
    public List<Exam> openExams() {
        LocalDateTime now = LocalDateTime.now();
        return examDao.findAll().stream().filter(e -> e.isOpenAt(now)).toList();
    }

    public Optional<Attempt> attemptOf(Exam exam, User student) {
        return attemptDao.findByExamAndStudent(exam.getId(), student.getId());
    }

    public Sitting start(Exam exam, User student) {
        LocalDateTime now = LocalDateTime.now();
        Optional<Attempt> existing = attemptOf(exam, student);

        if (existing.isPresent()) {
            Attempt a = existing.get();
            if (a.isFinished()) {
                throw new AttemptAlreadySubmittedException("You have already submitted this exam.");
            }
            if (!now.isBefore(a.getExpiresAt())) {
                // Time ran out while the student was away: close it with what was saved.
                autoSubmitExpired(a);
                throw new ExamExpiredException("Your time for this exam ran out. "
                    + "Your saved answers were submitted.");
            }
            return resume(a, exam);
        }

        if (!exam.isPublished() || now.isBefore(exam.getOpensAt())) {
            throw new ExamNotStartedException("This exam is not open yet.");
        }
        if (!now.isBefore(exam.getClosesAt())) {
            throw new ExamExpiredException("This exam has closed.");
        }

        List<Question> paper = new ArrayList<>(generator.generate(exam, student));
        shuffleChoiceOptions(exam, student, paper);

        Attempt a = new Attempt();
        a.setExamId(exam.getId());
        a.setStudentId(student.getId());
        a.setStartedAt(now);
        // The clock stops at the earlier of the allowed duration and the window closing.
        LocalDateTime byDuration = now.plusMinutes(exam.getDurationMinutes());
        a.setExpiresAt(byDuration.isBefore(exam.getClosesAt()) ? byDuration : exam.getClosesAt());
        attemptDao.createWithPaper(a, paper);
        return new Sitting(a, exam, paper, new java.util.LinkedHashMap<>());
    }

    private Sitting resume(Attempt a, Exam exam) {
        List<Question> paper = attemptDao.loadPaper(a.getId());
        Map<Long, Response> responses = attemptDao.loadResponses(a.getId());

        // The cache is written on every change, the database only on autosave,
        // so wherever the cache has an answer it is the newer one.
        Map<Long, Response> cached = cache.load(a.getId());
        for (Response r : cached.values()) {
            r.setAttemptId(a.getId());
            attemptDao.upsertResponse(r);
            responses.put(r.getQuestionId(), r);
        }
        return new Sitting(a, exam, paper, responses);
    }

    /** Autosave: one row, no transaction. */
    public void saveResponse(Response response) {
        attemptDao.upsertResponse(response);
    }

    /**
     * Final submission: flush every answer and close the attempt in one commit.
     * @param auto true when the clock ended it rather than the student
     */
    public void submit(Attempt attempt, Collection<Response> responses, boolean auto) {
        attemptDao.submit(attempt.getId(), responses,
                          auto ? AttemptStatus.AUTO_SUBMITTED : AttemptStatus.SUBMITTED,
                          scoreOrNull(attempt, responses));
        attempt.setStatus(auto ? AttemptStatus.AUTO_SUBMITTED : AttemptStatus.SUBMITTED);
        cache.delete(attempt.getId());
        try {
            proctoring.finalizeAttempt(attempt);
        } catch (RuntimeException e) {
            System.err.println("Integrity score not stored for attempt " + attempt.getId() + ": " + e);
        }
    }

    /**
     * Scores go into the same commit as the submission. If grading itself
     * fails, the submission still goes through unscored: losing a student's
     * answers to a grading bug is the worse outcome, and an unscored attempt
     * can be re-scored later with {@link GradingService#regrade}.
     */
    private ScoreSheet scoreOrNull(Attempt attempt, Collection<Response> responses) {
        try {
            Map<Long, Response> byQuestion = new java.util.HashMap<>();
            responses.forEach(r -> byQuestion.put(r.getQuestionId(), r));
            return grading.grade(attemptDao.loadPaper(attempt.getId()), byQuestion);
        } catch (RuntimeException e) {
            System.err.println("Grading failed for attempt " + attempt.getId() + ": " + e);
            return null;
        }
    }

    private void autoSubmitExpired(Attempt a) {
        Map<Long, Response> saved = attemptDao.loadResponses(a.getId());
        saved.putAll(cache.load(a.getId()));
        saved.values().forEach(r -> r.setAttemptId(a.getId()));
        submit(a, saved.values(), true);
    }

    /** Shuffles MCQ and MSQ options per student, stably. True/false keeps True first. */
    private static void shuffleChoiceOptions(Exam exam, User student, List<Question> paper) {
        Random rnd = new Random(java.util.Objects.hash(exam.getId(), student.getId(), "options"));
        for (Question q : paper) {
            if (q.getType() == QuestionType.MCQ || q.getType() == QuestionType.MSQ) {
                Collections.shuffle(q.getOptions(), rnd);
            }
        }
    }
}
