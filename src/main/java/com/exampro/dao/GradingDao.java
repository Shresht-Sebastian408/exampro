package com.exampro.dao;

import com.exampro.model.entity.Rubric;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Rubrics (the entity) and manual grades (written against responses). */
public interface GradingDao extends GenericDao<Rubric, Long> {

    /** An answer waiting for a person. */
    record PendingItem(long responseId, long attemptId, long questionId,
                       String student, String exam, String answer) {}

    Optional<Rubric> findByQuestion(long questionId);

    /** Submitted short answers with no final mark yet, oldest first. */
    List<PendingItem> findPending();

    /**
     * Stores the marks, sets the response's final score to their sum, and
     * recomputes the attempt total (marking it GRADED once nothing else is
     * outstanding), all in one commit.
     *
     * @param marksByCriterion criterion id to marks; the single key {@code null}
     *                         means a plain mark with no rubric
     * @return the total awarded to this response
     */
    double recordManualGrade(long responseId, long gradedBy,
                             Map<Long, Double> marksByCriterion, String comment);
}
