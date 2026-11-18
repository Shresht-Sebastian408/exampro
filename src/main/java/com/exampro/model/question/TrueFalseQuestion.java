package com.exampro.model.question;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;

/**
 * True or false, stored as two option rows so the grading path is identical
 * to MCQ. The convenience accessors below are the only concession to the
 * fact that the two options are known in advance.
 */
public class TrueFalseQuestion extends Question {

    public static final String TRUE_TEXT = "True";
    public static final String FALSE_TEXT = "False";

    @Override
    public QuestionType getType() {
        return QuestionType.TRUE_FALSE;
    }

    @Override
    public boolean isAttempted(Response response) {
        return response != null && !response.getSelectedOptionIds().isEmpty();
    }

    @Override
    public String getAnswerSummary() {
        return correctOptions().stream()
                               .findFirst()
                               .map(QuestionOption::getText)
                               .orElse("no correct option set");
    }

    /** Builds the two option rows. Used by the editor and the seeder. */
    public void setAnswer(boolean answerIsTrue) {
        getOptions().clear();
        getOptions().add(new QuestionOption(TRUE_TEXT, answerIsTrue, 0));
        getOptions().add(new QuestionOption(FALSE_TEXT, !answerIsTrue, 1));
    }

    public boolean isAnswerTrue() {
        return correctOptions().stream()
                               .findFirst()
                               .map(o -> TRUE_TEXT.equalsIgnoreCase(o.getText()))
                               .orElse(false);
    }
}
