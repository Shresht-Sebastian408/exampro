package com.exampro.proctor.rules;

import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorContext;
import com.exampro.proctor.ProctorRule;

/** Shared bits: the enabled flag, the default severity, and event construction. */
abstract class BaseRule implements ProctorRule {

    private final Severity severity;
    private boolean enabled = true;

    BaseRule(Severity severity) {
        this.severity = severity;
    }

    @Override
    public Severity getSeverity() {
        return severity;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    protected ProctorEvent event(ProctorContext ctx, EventType type, Severity sev,
                                 Long durationMs, String metadata) {
        return new ProctorEvent(ctx.attemptId(), type, sev, durationMs, metadata);
    }
}
