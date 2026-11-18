package com.exampro.config;

import com.exampro.exception.ConnectionException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Single HikariCP pool for the whole application.
 *
 * Every connection in the codebase comes from here and is closed by a
 * try-with-resources block, which returns it to the pool rather than
 * actually closing a socket.
 */
public final class ConnectionPool {

    private static volatile HikariDataSource dataSource;

    private ConnectionPool() {}

    public static DataSource getDataSource() {
        HikariDataSource local = dataSource;
        if (local == null) {
            synchronized (ConnectionPool.class) {
                local = dataSource;
                if (local == null) {
                    local = dataSource = build();
                }
            }
        }
        return local;
    }

    public static Connection getConnection() {
        try {
            return getDataSource().getConnection();
        } catch (SQLException e) {
            throw new ConnectionException(
                "Cannot obtain a connection from the pool for " + DatabaseConfig.JDBC_URL, e);
        }
    }

    /** Called from the JavaFX stop() hook so the H2 file closes cleanly. */
    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        dataSource = null;
    }

    private static HikariDataSource build() {
        try {
            Files.createDirectories(Path.of(AppConfig.DATA_DIR));
        } catch (IOException e) {
            throw new ConnectionException(
                "Cannot create the data directory " + AppConfig.DATA_DIR, e);
        }

        HikariConfig config = new HikariConfig();
        config.setDriverClassName(DatabaseConfig.DRIVER);
        config.setJdbcUrl(DatabaseConfig.JDBC_URL);
        config.setUsername(DatabaseConfig.USERNAME);
        config.setPassword(DatabaseConfig.PASSWORD);
        config.setMaximumPoolSize(DatabaseConfig.POOL_MAX_SIZE);
        config.setMinimumIdle(DatabaseConfig.POOL_MIN_IDLE);
        config.setConnectionTimeout(DatabaseConfig.CONNECTION_TIMEOUT_MS);
        config.setPoolName("ExamProPool");
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }
}
