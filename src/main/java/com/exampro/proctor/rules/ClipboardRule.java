package com.exampro.proctor.rules;

import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorContext;

import java.util.Optional;

/**
 * One instance watches one kind of clipboard use, so a tick that saw both a
 * copy and a paste raises both (a rule yields at most one event per tick).
 */
public class ClipboardRule extends BaseRule {

    private final EventType type;

    private ClipboardRule(EventType type, Severity severity) {
        super(severity);
        this.type = type;
    }

    public static ClipboardRule forCopy() {
        return new ClipboardRule(EventType.COPY_ATTEMPT, Severity.MEDIUM);
    }

    /** Pasting is worse than copying: it is how outside text gets in. */
    public static ClipboardRule forPaste() {
        return new ClipboardRule(EventType.PASTE_ATTEMPT, Severity.HIGH);
    }

    @Override
    public Optional<ProctorEvent> evaluate(ProctorContext ctx) {
        int count = type == EventType.COPY_ATTEMPT ? ctx.copies() : ctx.pastes();
        return count > 0
            ? Optional.of(event(ctx, type, getSeverity(), null, "count=" + count))
            : Optional.empty();
    }
}
