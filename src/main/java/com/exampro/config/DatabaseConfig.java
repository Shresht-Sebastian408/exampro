package com.exampro.config;

/**
 * Everything engine-specific about persistence, in one place.
 *
 * H2 is embedded and written in Java, so there is no server to install.
 * AUTO_SERVER=TRUE lets a second process (the H2 console, or a second app
 * instance during a demo) attach to the same file instead of failing with
 * a lock error. Swapping to HSQLDB or Derby later means editing JDBC_URL
 * and DRIVER here and nothing else.
 */
public final class DatabaseConfig {

    public static final String DRIVER = "org.h2.Driver";

    public static final String DEFAULT_JDBC_URL = "jdbc:h2:./data/exampro;AUTO_SERVER=TRUE";

    /**
     * Overridable with {@code -Dexampro.jdbcUrl=...} so tests can run the
     * real schema and the real DAOs against an in-memory database instead
     * of the developer's working data file.
     */
    public static final String JDBC_URL =
        System.getProperty("exampro.jdbcUrl", DEFAULT_JDBC_URL);
    public static final String USERNAME = "sa";
    public static final String PASSWORD = "";

    /** Hikari sizing. Small on purpose: a desktop app has a handful of threads. */
    public static final int POOL_MAX_SIZE = 10;
    public static final int POOL_MIN_IDLE = 2;
    public static final long CONNECTION_TIMEOUT_MS = 10_000L;

    private DatabaseConfig() {}
}
