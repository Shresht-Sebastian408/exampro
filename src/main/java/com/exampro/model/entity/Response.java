package com.exampro.model.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * One student's answer to one question.
 *
 * Only the answer-carrying fields exist so far, which is all the question
 * hierarchy needs to decide whether a question was attempted. The scoring
 * columns on the responses table (auto_score, manual_score, final_score)
 * arrive with grading in week seven, together with ResponseDao.
 */
public class Response {

    private Long id;
    private Long attemptId;
    private Long questionId;

    private final List<Long> selectedOptionIds = new ArrayList<>();
    private String textAnswer;
    private Double numericAnswer;
    private boolean flagged;

    public Response() {}

    public Response(Long questionId) {
        this.questionId = questionId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public List<Long> getSelectedOptionIds() { return selectedOptionIds; }

    public String getTextAnswer() { return textAnswer; }
    public void setTextAnswer(String textAnswer) { this.textAnswer = textAnswer; }

    public Double getNumericAnswer() { return numericAnswer; }
    public void setNumericAnswer(Double numericAnswer) { this.numericAnswer = numericAnswer; }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }
}
