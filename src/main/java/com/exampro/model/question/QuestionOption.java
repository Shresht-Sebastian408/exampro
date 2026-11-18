package com.exampro.model.question;

/**
 * One choice on a question. TRUE_FALSE stores two of these like any other
 * choice type, so grading never special-cases it.
 */
public class QuestionOption {

    private Long id;
    private Long questionId;
    private String text;
    private boolean correct;
    private int displayOrder;

    public QuestionOption() {}

    public QuestionOption(String text, boolean correct, int displayOrder) {
        this.text = text;
        this.correct = correct;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    @Override
    public String toString() {
        return text;
    }
}
