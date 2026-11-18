package com.exampro.proctor.rules;

import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorContext;

import java.util.Optional;

/** More than one screen attached. Raised when the count rises above what was last reported. */
public class MultiDisplayRule extends BaseRule {

    private int reportedCount = 1;

    public MultiDisplayRule() {
        super(Severity.HIGH);
    }

    @Override
    public Optional<ProctorEvent> evaluate(ProctorContext ctx) {
        int screens = ctx.screenCount();
        if (screens > reportedCount) {
            reportedCount = screens;
            return Optional.of(event(ctx, EventType.MULTI_DISPLAY_DETECTED, getSeverity(),
                                     null, "screens=" + screens));
        }
        if (screens <= 1) {
            reportedCount = 1;
        }
        return Optional.empty();
    }
}
