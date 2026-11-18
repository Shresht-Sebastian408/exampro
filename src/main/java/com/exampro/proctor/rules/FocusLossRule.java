package com.exampro.proctor.rules;

import com.exampro.config.AppConfig;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorContext;

import java.time.Duration;
import java.util.Optional;

/**
 * Reports leaving the exam window, and again when the student comes back.
 * The return event carries how long they were away, and is raised to HIGH
 * when that was long enough to look something up.
 */
public class FocusLossRule extends BaseRule {

    private final Duration longAbsence;
    private boolean reported;

    public FocusLossRule() {
        this(AppConfig.LONG_ABSENCE);
    }

    public FocusLossRule(Duration longAbsence) {
        super(Severity.MEDIUM);
        this.longAbsence = longAbsence;
    }

    @Override
    public Optional<ProctorEvent> evaluate(ProctorContext ctx) {
        if (!ctx.focused() && !reported) {
            reported = true;
            return Optional.of(event(ctx, EventType.FOCUS_LOST, getSeverity(), null, null));
        }
        if (ctx.focused() && reported) {
            reported = false;
            var from = ctx.focusLostAt() == null ? ctx.now() : ctx.focusLostAt();
            var to = ctx.focusRegainedAt() == null ? ctx.now() : ctx.focusRegainedAt();
            long ms = Math.max(0, Duration.between(from, to).toMillis());
            Severity sev = ms >= longAbsence.toMillis() ? Severity.HIGH : Severity.INFO;
            return Optional.of(event(ctx, EventType.FOCUS_REGAINED, sev, ms, null));
        }
        return Optional.empty();
    }
}
