package com.exampro.model.entity;

import com.exampro.model.enums.AttemptStatus;

import java.time.LocalDateTime;

/** One student's sitting of one exam. The paper itself lives in attempt_questions. */
public class Attempt {

    private Long id;
    private Long examId;
    private Long studentId;
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;
    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime submittedAt;
    private Double totalScore;
    private Double integrityScore;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public AttemptStatus getStatus() { return status; }
    public void setStatus(AttemptStatus status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    /** Null until the attempt has been scored. */
    public Double getTotalScore() { return totalScore; }
    public void setTotalScore(Double totalScore) { this.totalScore = totalScore; }

    /** Null until the attempt was submitted and its integrity computed. */
    public Double getIntegrityScore() { return integrityScore; }
    public void setIntegrityScore(Double integrityScore) { this.integrityScore = integrityScore; }

    public boolean isFinished() {
        return status != AttemptStatus.IN_PROGRESS && status != AttemptStatus.NOT_STARTED;
    }
}
