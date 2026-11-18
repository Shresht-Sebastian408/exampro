package com.exampro.proctor.rules;

import com.exampro.config.AppConfig;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorContext;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

/**
 * An answer given faster than anyone could have read the question. Low
 * severity on purpose: a real fast reader trips it too, so it only matters
 * in volume, which the scorer's per-type cap and weights handle.
 */
public class AnswerVelocityRule extends BaseRule {

    private final Duration floor;

    public AnswerVelocityRule() {
        this(AppConfig.RAPID_ANSWER_FLOOR);
    }

    public AnswerVelocityRule(Duration floor) {
        super(Severity.LOW);
        this.floor = floor;
    }

    @Override
    public Optional<ProctorEvent> evaluate(ProctorContext ctx) {
        long rapid = ctx.answerLatenciesMs().stream().filter(ms -> ms < floor.toMillis()).count();
        if (rapid == 0) {
            return Optional.empty();
        }
        long fastest = Collections.min(ctx.answerLatenciesMs());
        return Optional.of(event(ctx, EventType.RAPID_ANSWER, getSeverity(), fastest,
                                 "rapid=" + rapid));
    }
}
