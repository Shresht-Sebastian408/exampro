package com.exampro.grading;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.ShortAnswerQuestion;

/**
 * Advisory only. The suggestion is marks times the share of keywords found;
 * it is never the final mark, because keyword presence is not understanding
 * and a person must look. A blank answer needs no looking and scores zero.
 */
public class KeywordGrader implements Grader<ShortAnswerQuestion> {

    @Override
    public GradeResult grade(ShortAnswerQuestion q, Response r) {
        if (!q.isAttempted(r)) {
            return GradeResult.auto(0);
        }
        long hits = q.keywordHits(r.getTextAnswer());
        int total = q.getKeywords().size();
        double suggestion = total == 0 ? 0 : q.getMarks() * hits / total;
        return new GradeResult(suggestion, true, hits + " of " + total + " keywords found");
    }

    @Override
    public boolean supports(QuestionType type) {
        return type == QuestionType.SHORT_ANSWER;
    }
}
