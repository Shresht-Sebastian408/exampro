package com.exampro.proctor;

import java.time.Instant;
import java.util.List;

/**
 * What the rules see on one tick: an immutable snapshot of the signals the
 * exam screen has reported since the previous tick.
 *
 * @param focused         false if the window was away at any point since the last tick,
 *                        so a blip shorter than a tick is still seen
 * @param focusLostAt     start of the latest absence, or null if none yet
 * @param focusRegainedAt when it ended, or null while still away
 * @param lastActivityAt  last key press or mouse movement
 * @param copies          copy or cut attempts since the last tick
 * @param pastes          paste attempts since the last tick
 * @param answerLatenciesMs milliseconds from a question appearing to its first answer,
 *                        for answers given since the last tick
 */
public record ProctorContext(long attemptId, Instant now, boolean focused,
                             Instant focusLostAt, Instant focusRegainedAt,
                             Instant lastActivityAt, int copies, int pastes,
                             List<Long> answerLatenciesMs, int screenCount) {}
