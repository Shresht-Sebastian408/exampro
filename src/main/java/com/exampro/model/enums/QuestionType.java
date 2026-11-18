package com.exampro.model.enums;

public enum QuestionType {
    MCQ, MSQ, TRUE_FALSE, NUMERIC, SHORT_ANSWER;

    /** Objective types grade without a human. */
    public boolean isAutoGradable() {
        return this != SHORT_ANSWER;
    }
}
