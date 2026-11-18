package com.exampro.grading;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.MSQQuestion;

/** The partial-credit rule lives on {@link MSQQuestion#creditFraction}; this applies the marks. */
public class MSQGrader implements Grader<MSQQuestion> {

    @Override
    public GradeResult grade(MSQQuestion q, Response r) {
        boolean attempted = q.isAttempted(r);
        double fraction = attempted ? q.creditFraction(r.getSelectedOptionIds()) : 0;
        return GradeResult.auto(Grader.awarded(q, attempted, fraction));
    }

    @Override
    public boolean supports(QuestionType type) {
        return type == QuestionType.MSQ;
    }
}
