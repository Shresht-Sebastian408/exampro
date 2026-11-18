package com.exampro.service;

import com.exampro.dao.AttemptDao;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.grading.GradeResult;
import com.exampro.grading.Grader;
import com.exampro.grading.KeywordGrader;
import com.exampro.grading.MCQGrader;
import com.exampro.grading.MSQGrader;
import com.exampro.grading.NumericGrader;
import com.exampro.grading.ScoreSheet;
import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches each question to the grader for its type. A new question type
 * means one grader class and one {@link #register} line here.
 */
public class GradingService {

    private final Map<QuestionType, Grader<? extends Question>> graders = new EnumMap<>(QuestionType.class);
    private final AttemptDao attempts;

    public GradingService() {
        this(new AttemptDaoImpl());
    }

    public GradingService(AttemptDao attempts) {
        this.attempts = attempts;
        register(new MCQGrader());
        register(new MSQGrader());
        register(new NumericGrader());
        register(new KeywordGrader());
    }

    private void register(Grader<? extends Question> grader) {
        for (QuestionType t : QuestionType.values()) {
            if (grader.supports(t)) {
                graders.put(t, grader);
            }
        }
    }

    /** Pure scoring of a paper against its responses (question id to response; missing means blank). */
    public ScoreSheet grade(List<Question> paper, Map<Long, Response> responses) {
        Map<Long, ScoreSheet.Entry> entries = new LinkedHashMap<>();
        double total = 0;
        boolean needsManual = false;

        for (Question q : paper) {
            GradeResult result = gradeOne(q, responses.get(q.getId()));
            if (result.needsManual()) {
                needsManual = true;
                entries.put(q.getId(), new ScoreSheet.Entry(result.score(), null));
            } else {
                total += result.score();
                entries.put(q.getId(), new ScoreSheet.Entry(result.score(), result.score()));
            }
        }
        return new ScoreSheet(entries, total, needsManual);
    }

    @SuppressWarnings("unchecked")
    private GradeResult gradeOne(Question q, Response r) {
        Grader<Question> grader = (Grader<Question>) graders.get(q.getType());
        if (grader == null) {
            throw new IllegalStateException("No grader registered for " + q.getType());
        }
        return grader.grade(q, r);
    }

    /** Re-scores a stored attempt from its frozen paper, e.g. after an answer-key correction. */
    public ScoreSheet regrade(long attemptId) {
        if (attempts.findById(attemptId).isEmpty()) {
            throw new EntityNotFoundException("No attempt with id " + attemptId);
        }
        ScoreSheet sheet = grade(attempts.loadPaper(attemptId), attempts.loadResponses(attemptId));
        attempts.saveScores(attemptId, sheet);
        return sheet;
    }
}
