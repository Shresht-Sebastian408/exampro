package com.exampro.service;

import com.exampro.dao.ProctorEventDao;
import com.exampro.dao.impl.ProctorEventDaoImpl;
import com.exampro.model.entity.Attempt;
import com.exampro.proctor.IntegrityScore;
import com.exampro.proctor.IntegrityScorer;
import com.exampro.proctor.ProctorEngine;
import com.exampro.proctor.ProctorRule;
import com.exampro.proctor.rules.AnswerVelocityRule;
import com.exampro.proctor.rules.ClipboardRule;
import com.exampro.proctor.rules.FocusLossRule;
import com.exampro.proctor.rules.IdleTimeoutRule;
import com.exampro.proctor.rules.MultiDisplayRule;

import java.util.List;

/** Builds a per-attempt engine that persists what it sees, and computes the integrity score. */
public class ProctoringService {

    private final ProctorEventDao events;
    private final IntegrityScorer scorer;

    public ProctoringService() {
        this(new ProctorEventDaoImpl(), new IntegrityScorer());
    }

    public ProctoringService(ProctorEventDao events, IntegrityScorer scorer) {
        this.events = events;
        this.scorer = scorer;
    }

    /**
     * Rules keep state (has this absence been reported yet?), so every attempt
     * gets its own instances. The database writer is just one listener; the
     * caller adds the banner.
     */
    public ProctorEngine engineFor() {
        List<ProctorRule> rules = List.of(
            new FocusLossRule(), ClipboardRule.forCopy(), ClipboardRule.forPaste(),
            new IdleTimeoutRule(), new AnswerVelocityRule(), new MultiDisplayRule());
        ProctorEngine engine = new ProctorEngine(rules);
        engine.addListener(events::insert);
        return engine;
    }

    /** Computed from the stored events, so it is always consistent with them. */
    public IntegrityScore integrityOf(long attemptId) {
        return scorer.score(events.findByAttempt(attemptId));
    }

    /**
     * Called after an attempt is submitted. Separate from the submission
     * commit on purpose: the score is derived from events and can always be
     * recomputed, whereas answers cannot, so a failure here must never be
     * able to undo or block a submission.
     */
    public void finalizeAttempt(Attempt attempt) {
        IntegrityScore score = integrityOf(attempt.getId());
        events.saveIntegrity(attempt.getId(), score, attempt.getStudentId(),
            "status=" + attempt.getStatus() + ", integrity=" + Math.round(score.score()));
    }
}
