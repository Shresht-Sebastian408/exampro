package com.exampro.model.entity;

import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;

/** One instruction: pull {count} {difficulty} {type} questions from {topic}. */
public class BlueprintRule {

    private Long id;
    private long topicId;
    private String topicName;
    private Difficulty difficulty = Difficulty.MEDIUM;
    private QuestionType type = QuestionType.MCQ;
    private int count = 1;

    public BlueprintRule() {}

    public BlueprintRule(long topicId, String topicName, Difficulty difficulty,
                         QuestionType type, int count) {
        this.topicId = topicId;
        this.topicName = topicName;
        this.difficulty = difficulty;
        this.type = type;
        this.count = count;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getTopicId() { return topicId; }
    public void setTopicId(long topicId) { this.topicId = topicId; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public Difficulty getDifficulty() { return difficulty; }
    public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }
    public QuestionType getType() { return type; }
    public void setType(QuestionType type) { this.type = type; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
