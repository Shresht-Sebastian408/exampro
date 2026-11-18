package com.exampro.proctor;

import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.Severity;

import java.util.Optional;

public interface ProctorRule {

    /** At most one event per tick. Rules may keep state, so use one instance per attempt. */
    Optional<ProctorEvent> evaluate(ProctorContext context);

    /** The usual severity; a rule may raise it for a particular event. */
    Severity getSeverity();

    boolean isEnabled();
}
