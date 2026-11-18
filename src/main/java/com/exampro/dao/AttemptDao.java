package com.exampro.dao;

import com.exampro.grading.ScoreSheet;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.Response;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.question.Question;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AttemptDao extends GenericDao<Attempt, Long> {

    Optional<Attempt> findByExamAndStudent(long examId, long studentId);

    /** Every attempt on an exam, in the order they were created. */
    List<Attempt> findByExam(long examId);

    /**
     * Attempt row and every attempt_questions row in one transaction: a
     * partly written paper is worse than none. Option order is whatever
     * order each question's options list is in when this is called.
     */
    long createWithPaper(Attempt attempt, List<Question> paper);

    /** The frozen paper in display order, options in the order the student saw them. */
    List<Question> loadPaper(long attemptId);

    /** Saved responses keyed by question id. */
    Map<Long, Response> loadResponses(long attemptId);

    /** Single-row upsert. Deliberately not transactional: autosave is fire and retry. */
    void upsertResponse(Response response);

    /**
     * Flushes {@code pending}, moves IN_PROGRESS to {@code finalStatus}, and, when
     * {@code scores} is given, writes the scores too, all in one commit.
     * @throws com.exampro.exception.AttemptAlreadySubmittedException if the
     * attempt was no longer IN_PROGRESS, in which case nothing is written.
     */
    void submit(long attemptId, Collection<Response> pending, AttemptStatus finalStatus,
                ScoreSheet scores);

    /**
     * Writes per-response scores and the attempt total; marks the attempt GRADED
     * when nothing awaits a person. One commit.
     */
    void saveScores(long attemptId, ScoreSheet scores);
}
