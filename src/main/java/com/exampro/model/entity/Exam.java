package com.exampro.model.entity;

import java.time.LocalDateTime;

/**
 * The exam definition. Scheduling, publishing and proctoring flags arrive
 * with exam delivery in week six; for now an exam is a title, a length, and
 * the strategy that builds its paper.
 */
public class Exam {

    private Long id;
    private Long subjectId;
    private String title;
    private int durationMinutes = 60;
    private LocalDateTime opensAt = LocalDateTime.now();
    private LocalDateTime closesAt = LocalDateTime.now().plusDays(7);
    private String generationStrategy = "BLUEPRINT";
    private boolean shuffleQuestions = true;
    private Long createdBy;
    private boolean published;

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    /** Open to students right now: published and inside its window. */
    public boolean isOpenAt(LocalDateTime now) {
        return published && !now.isBefore(opensAt) && now.isBefore(closesAt);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public LocalDateTime getOpensAt() { return opensAt; }
    public void setOpensAt(LocalDateTime opensAt) { this.opensAt = opensAt; }
    public LocalDateTime getClosesAt() { return closesAt; }
    public void setClosesAt(LocalDateTime closesAt) { this.closesAt = closesAt; }
    public String getGenerationStrategy() { return generationStrategy; }
    public void setGenerationStrategy(String s) { this.generationStrategy = s; }
    public boolean isShuffleQuestions() { return shuffleQuestions; }
    public void setShuffleQuestions(boolean b) { this.shuffleQuestions = b; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    @Override
    public String toString() { return title; }
}
