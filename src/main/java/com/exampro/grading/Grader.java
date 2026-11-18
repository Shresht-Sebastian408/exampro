package com.exampro.grading;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;

public interface Grader<T extends Question> {

    GradeResult grade(T question, Response response);

    boolean supports(QuestionType type);

    /**
     * The marking rule shared by every objective type: full marks times the
     * fraction earned, a deduction for an attempted answer that earned
     * nothing, zero for a blank. Negative marks therefore never punish
     * leaving a question alone.
     */
    static double awarded(Question q, boolean attempted, double fraction) {
        if (!attempted) {
            return 0;
        }
        return fraction > 0 ? q.getMarks() * fraction : -q.getNegativeMarks();
    }
}
