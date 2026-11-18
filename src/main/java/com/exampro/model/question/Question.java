package com.exampro.model.question;

import com.exampro.model.entity.Response;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Base of the five question types.
 *
 * Two abstract methods are the whole point of the hierarchy: every caller
 * that needs to know whether an answer was given, or how to describe the
 * correct answer, gets it polymorphically and never switches on the type.
 *
 * The options list lives here rather than on a choice-only subclass, which
 * matches the schema: question_options is uniform across types and the
 * numeric and short-answer types simply leave it empty. One fewer layer in
 * the hierarchy for no loss of safety, since the DAO and the validation in
 * QuestionBankService both enforce the per-type option rules.
 */
public abstract class Question {

    private Long id;
    private Long topicId;
    private String stem;
    private Difficulty difficulty = Difficulty.MEDIUM;
    private double marks = 1;
    private double negativeMarks = 0;
    private String explanation;
    private Long createdBy;
    private boolean active = true;
    private LocalDateTime createdAt;

    private final List<QuestionOption> options = new ArrayList<>();

    /** Lower-case labels; normalised by QuestionBankService.validate. */
    private final Set<String> tags = new LinkedHashSet<>();

    /** Carried for the UI, which lists questions across a whole subject. */
    private String topicName;

    public abstract QuestionType getType();

    /** Did the student actually answer this, as opposed to leaving it blank? */
    public abstract boolean isAttempted(Response response);

    /** The correct answer as one line, for review screens and exports. */
    public abstract String getAnswerSummary();

    public List<QuestionOption> getOptions() { return options; }

    public Set<String> getTags() { return tags; }

    public List<QuestionOption> correctOptions() {
        return options.stream().filter(QuestionOption::isCorrect).toList();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }

    public Difficulty getDifficulty() { return difficulty; }
    public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }

    public double getMarks() { return marks; }
    public void setMarks(double marks) { this.marks = marks; }

    public double getNegativeMarks() { return negativeMarks; }
    public void setNegativeMarks(double negativeMarks) { this.negativeMarks = negativeMarks; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    /** First line of the stem, for table cells and dropdowns. */
    public String shortStem() {
        if (stem == null) {
            return "";
        }
        String oneLine = stem.replaceAll("\s+", " ").trim();
        return oneLine.length() <= 90 ? oneLine : oneLine.substring(0, 87) + "...";
    }

    @Override
    public String toString() {
        return getType() + ": " + shortStem();
    }
}
