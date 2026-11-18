package com.exampro.proctor;

import com.exampro.model.entity.ProctorEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs every enabled rule against a snapshot and pushes what they produce to
 * each listener. Listeners are independent: a failure in one (say the
 * database writer) must not stop the banner or the monitor from hearing the
 * event, so each call is isolated.
 */
public class ProctorEngine {

    private final List<ProctorRule> rules;
    private final List<ProctorEventListener> listeners = new CopyOnWriteArrayList<>();

    public ProctorEngine(List<ProctorRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public void addListener(ProctorEventListener listener) {
        listeners.add(listener);
    }

    /** @return the events raised on this tick, after listeners have been told */
    public List<ProctorEvent> tick(ProctorContext context) {
        List<ProctorEvent> raised = new ArrayList<>();
        for (ProctorRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            try {
                rule.evaluate(context).ifPresent(raised::add);
            } catch (RuntimeException e) {
                System.err.println("Proctor rule failed: " + rule.getClass().getSimpleName() + ": " + e);
            }
        }
        for (ProctorEvent event : raised) {
            for (ProctorEventListener l : listeners) {
                try {
                    l.onEvent(event);
                } catch (RuntimeException e) {
                    System.err.println("Proctor listener failed: " + e);
                }
            }
        }
        return raised;
    }
}
