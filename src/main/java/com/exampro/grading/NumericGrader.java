package com.exampro.grading;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.NumericQuestion;

public class NumericGrader implements Grader<NumericQuestion> {

    @Override
    public GradeResult grade(NumericQuestion q, Response r) {
        boolean attempted = q.isAttempted(r);
        boolean right = attempted && q.accepts(r.getNumericAnswer());
        return GradeResult.auto(Grader.awarded(q, attempted, right ? 1 : 0));
    }

    @Override
    public boolean supports(QuestionType type) {
        return type == QuestionType.NUMERIC;
    }
}
