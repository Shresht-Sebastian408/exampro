package com.exampro.dao;

import com.exampro.model.entity.ProctorEvent;
import com.exampro.proctor.IntegrityScore;

import java.util.List;

public interface ProctorEventDao extends GenericDao<ProctorEvent, Long> {

    /** Oldest first. */
    List<ProctorEvent> findByAttempt(long attemptId);

    /** Every event of every attempt on an exam, oldest first. What the live monitor polls. */
    List<ProctorEvent> findByExam(long examId);

    /**
     * Stores the final integrity score (table row and attempt column) and an
     * audit record, in one commit. Replaces any earlier score for the attempt.
     */
    void saveIntegrity(long attemptId, IntegrityScore score, Long userId, String auditDetail);
}
