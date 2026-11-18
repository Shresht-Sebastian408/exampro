package com.exampro.grading;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;

/** Single-answer choice: MCQ and true/false, which are stored identically. */
public class MCQGrader implements Grader<Question> {

    @Override
    public GradeResult grade(Question q, Response r) {
        boolean attempted = q.isAttempted(r);
        boolean right = attempted && r.getSelectedOptionIds().size() == 1
            && q.getOptions().stream()
                .anyMatch(o -> o.isCorrect() && o.getId().equals(r.getSelectedOptionIds().get(0)));
        return GradeResult.auto(Grader.awarded(q, attempted, right ? 1 : 0));
    }

    @Override
    public boolean supports(QuestionType type) {
        return type == QuestionType.MCQ || type == QuestionType.TRUE_FALSE;
    }
}
