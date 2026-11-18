package com.exampro.model.question;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.QuestionType;

/**
 * A number within a tolerance. The tolerance is absolute, not a percentage:
 * a percentage tolerance behaves badly around zero, and an examiner setting
 * "2.5 plus or minus 0.1" means exactly that.
 */
public class NumericQuestion extends Question {

    private Double answer;
    private double tolerance = 0;

    @Override
    public QuestionType getType() {
        return QuestionType.NUMERIC;
    }

    @Override
    public boolean isAttempted(Response response) {
        return response != null && response.getNumericAnswer() != null;
    }

    @Override
    public String getAnswerSummary() {
        if (answer == null) {
            return "no answer set";
        }
        return tolerance == 0 ? String.valueOf(answer) : answer + " ± " + tolerance;
    }

    public boolean accepts(Double submitted) {
        return submitted != null && answer != null
            && Math.abs(submitted - answer) <= tolerance;
    }

    public Double getAnswer() { return answer; }
    public void setAnswer(Double answer) { this.answer = answer; }

    public double getTolerance() { return tolerance; }
    public void setTolerance(double tolerance) { this.tolerance = tolerance; }
}
