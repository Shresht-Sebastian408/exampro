package com.exampro.proctor.rules;

import com.exampro.config.AppConfig;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorContext;

import java.time.Duration;
import java.util.Optional;

/** No key or mouse activity for the threshold. Raised once per idle spell, re-armed by activity. */
public class IdleTimeoutRule extends BaseRule {

    private final Duration threshold;
    private boolean reported;

    public IdleTimeoutRule() {
        this(AppConfig.IDLE_THRESHOLD);
    }

    public IdleTimeoutRule(Duration threshold) {
        super(Severity.MEDIUM);
        this.threshold = threshold;
    }

    @Override
    public Optional<ProctorEvent> evaluate(ProctorContext ctx) {
        Duration idle = Duration.between(ctx.lastActivityAt(), ctx.now());
        if (idle.compareTo(threshold) < 0) {
            reported = false;
            return Optional.empty();
        }
        if (reported) {
            return Optional.empty();
        }
        reported = true;
        return Optional.of(event(ctx, EventType.IDLE_TIMEOUT, getSeverity(), idle.toMillis(), null));
    }
}
