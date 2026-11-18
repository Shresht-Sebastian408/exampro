package com.exampro.config.schema;

import com.exampro.config.ConnectionPool;
import com.exampro.exception.SchemaInitializationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates or upgrades the schema. Runs from {@code Main} before the JavaFX
 * stage opens, so the UI never starts against a half-built database.
 *
 * A {@code schema_version} row records what has been applied, which makes a
 * second launch a no-op and leaves room for real migrations later.
 *
 * ponytail: H2 issues an implicit commit on DDL, so the explicit transaction
 * below cannot actually roll a failed CREATE back. It is kept because every
 * statement is IF NOT EXISTS and the version row is written last, which gives
 * the property that matters: a failed run leaves no version marker, so the
 * next launch retries the whole thing and finishes the job. Move to a real
 * migration tool if versioned upgrades ever get complicated.
 */
public class SchemaInitializer {

    public static final int CURRENT_VERSION = 1;

    private static final String READ_VERSION = "SELECT MAX(version) FROM schema_version";
    private static final String WRITE_VERSION = "INSERT INTO schema_version (version) VALUES (?)";

    public void initialize() {
        try (Connection conn = ConnectionPool.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                execute(conn, SchemaDefinition.CREATE_SCHEMA_VERSION);
                int installed = readVersion(conn);

                if (installed == 0) {
                    runFullSchema(conn);
                    writeVersion(conn, CURRENT_VERSION);
                } else if (installed < CURRENT_VERSION) {
                    applyMigrations(conn, installed);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SchemaInitializationException(
                    "Schema setup failed while targeting version " + CURRENT_VERSION, e);
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new SchemaInitializationException("Cannot reach the database to set up the schema", e);
        }
    }

    /** Tables first in parent-before-child order, then every index. */
    void runFullSchema(Connection conn) throws SQLException {
        for (String ddl : SchemaDefinition.ALL_TABLES) {
            execute(conn, ddl);
        }
        for (String ddl : SchemaDefinition.ALL_INDEXES) {
            execute(conn, ddl);
        }
    }

    /**
     * Nothing to migrate at version 1. When version 2 arrives, this becomes a
     * switch on {@code installed} that applies each step in order.
     */
    private void applyMigrations(Connection conn, int installed) throws SQLException {
        writeVersion(conn, CURRENT_VERSION);
    }

    private int readVersion(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(READ_VERSION);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void writeVersion(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(WRITE_VERSION)) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
