package com.exampro.model.question;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;

/** Single correct option out of several. */
public class MCQQuestion extends Question {

    @Override
    public QuestionType getType() {
        return QuestionType.MCQ;
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
}
