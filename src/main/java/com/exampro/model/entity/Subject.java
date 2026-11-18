package com.exampro.model.entity;

import java.time.LocalDateTime;

public class Subject {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Long createdBy;
    private boolean active = true;
    private LocalDateTime createdAt;

    /** Not persisted on this table: filled by a join for the subjects table view. */
    private int topicCount;
    private int questionCount;

    public Subject() {}

    public Subject(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getTopicCount() { return topicCount; }
    public void setTopicCount(int topicCount) { this.topicCount = topicCount; }

    public int getQuestionCount() { return questionCount; }
    public void setQuestionCount(int questionCount) { this.questionCount = questionCount; }

    @Override
    public String toString() {
        return code + " - " + name;
    }
}
