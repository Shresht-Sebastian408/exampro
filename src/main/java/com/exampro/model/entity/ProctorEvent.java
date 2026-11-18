package com.exampro.model.entity;

import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;

import java.time.LocalDateTime;

/** One observed behaviour during an attempt. */
public class ProctorEvent {

    private Long id;
    private long attemptId;
    private EventType type;
    private Severity severity;
    private LocalDateTime occurredAt = LocalDateTime.now();
    private Long durationMs;
    private String metadata;

    public ProctorEvent() {}

    public ProctorEvent(long attemptId, EventType type, Severity severity, Long durationMs, String metadata) {
        this.attemptId = attemptId;
        this.type = type;
        this.severity = severity;
        this.durationMs = durationMs;
        this.metadata = metadata;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getAttemptId() { return attemptId; }
    public void setAttemptId(long attemptId) { this.attemptId = attemptId; }
    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
