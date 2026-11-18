package com.exampro.config;

import java.time.Duration;

/**
 * Tunable application constants.
 *
 * These are knobs, not facts. Autosave cadence, idle thresholds and the
 * answer-velocity floor all need adjusting against a real machine and a
 * real cohort: a slow laptop makes a 2 second autosave feel laggy, and a
 * genuinely fast student trips a naive RAPID_ANSWER threshold. Keeping
 * them here means tuning never requires touching logic.
 */
public final class AppConfig {

    public static final String APP_NAME = "ExamPro";
    public static final String APP_VERSION = "1.0.0";

    // --- exam delivery -------------------------------------------------
    public static final Duration AUTOSAVE_INTERVAL = Duration.ofSeconds(5);
    public static final Duration TIMER_TICK = Duration.ofSeconds(1);
    public static final int AUTOSAVE_FAILURES_BEFORE_WARNING = 3;

    // --- proctoring ----------------------------------------------------
    public static final Duration PROCTOR_TICK = Duration.ofSeconds(2);
    public static final Duration IDLE_THRESHOLD = Duration.ofMinutes(2);
    /** Below this, an answer looks like a paste or a guess rather than reading. */
    public static final Duration RAPID_ANSWER_FLOOR = Duration.ofSeconds(3);
    /** An absence at least this long is a serious event, not a blip. */
    public static final Duration LONG_ABSENCE = Duration.ofSeconds(15);
    public static final double INTEGRITY_START = 100;
    /** One kind of event can cost at most this much, so a single nervous habit cannot zero a score. */
    public static final double INTEGRITY_TYPE_CAP = 40;
    public static final Duration SNAPSHOT_INTERVAL = Duration.ofMinutes(5);
    public static final boolean SNAPSHOTS_ENABLED = false;

    // --- filesystem ----------------------------------------------------
    public static final String DATA_DIR = "data";
    public static final String SNAPSHOT_DIR = "data/snapshots";
    public static final String RECOVERY_DIR = "data/recovery";
    public static final String EXPORT_DIR = "data/exports";

    // --- auth ----------------------------------------------------------
    public static final int BCRYPT_ROUNDS = 10;
    public static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    public static final int MAX_LOGIN_ATTEMPTS = 5;

    private AppConfig() {}
}
