package com.exampro.proctor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The inbox between the UI and the engine. The exam screen reports raw
 * signals here from the FX thread; the engine's tick reads and clears them
 * from another. Everything is synchronized because both sides are short.
 */
public class ProctorSignals {

    private boolean focused = true;
    private boolean lostSinceTick;
    private Instant focusLostAt;
    private Instant focusRegainedAt;
    private Instant lastActivityAt = Instant.now();
    private int copies;
    private int pastes;
    private final List<Long> latencies = new ArrayList<>();
    private int screenCount = 1;

    public synchronized void focusChanged(boolean nowFocused) {
        if (nowFocused == focused) {
            return;
        }
        focused = nowFocused;
        if (nowFocused) {
            focusRegainedAt = Instant.now();
        } else {
            lostSinceTick = true;
            focusLostAt = Instant.now();
            focusRegainedAt = null;
        }
    }

    public synchronized void activity() {
        lastActivityAt = Instant.now();
    }

    public synchronized void copyAttempt() {
        copies++;
    }

    public synchronized void pasteAttempt() {
        pastes++;
    }

    public synchronized void answerLatency(long millis) {
        latencies.add(millis);
    }

    public synchronized void screens(int count) {
        screenCount = count;
    }

    /** Reads the current state and clears the per-tick counters. */
    public synchronized ProctorContext snapshot(long attemptId, Instant now) {
        ProctorContext ctx = new ProctorContext(attemptId, now, focused && !lostSinceTick,
            focusLostAt, focusRegainedAt, lastActivityAt, copies, pastes,
            List.copyOf(latencies), screenCount);
        lostSinceTick = false;
        copies = 0;
        pastes = 0;
        latencies.clear();
        return ctx;
    }
}
