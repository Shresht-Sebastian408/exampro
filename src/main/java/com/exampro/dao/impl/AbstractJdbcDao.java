package com.exampro.dao.impl;

import com.exampro.config.ConnectionPool;
import com.exampro.dao.GenericDao;
import com.exampro.exception.ConnectionException;
import com.exampro.exception.DataAccessException;
import com.exampro.exception.DuplicateEntityException;
import com.exampro.exception.QueryExecutionException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * All the JDBC plumbing, written once.
 *
 * A concrete DAO supplies its table name, its row mapper, and its own SQL
 * constants; connection handling, try-with-resources, generated-key
 * retrieval and SQLException translation all live here. No SQLException
 * ever escapes this class or its subclasses.
 *
 * Transaction-aware overloads take an explicit {@link Connection} so a
 * service can enlist several DAO calls in one transaction (paper
 * generation and final submission both need this).
 */
public abstract class AbstractJdbcDao<T, ID> implements GenericDao<T, ID> {

    /** Lets a caller bind parameters without declaring SQLException itself. */
    @FunctionalInterface
    protected interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** For projections and joined rows that are not the DAO's own entity type. */
    @FunctionalInterface
    protected interface RowMapper<R> {
        R map(ResultSet rs) throws SQLException;
    }

    private static final Binder NO_PARAMS = ps -> { };

    protected abstract String tableName();

    protected abstract T mapRow(ResultSet rs) throws SQLException;

    // ------------------------------------------------------------------
    // GenericDao defaults that only need the table name and the mapper
    // ------------------------------------------------------------------

    @Override
    public Optional<T> findById(ID id) {
        return queryOne("SELECT * FROM " + tableName() + " WHERE id = ?",
                        ps -> ps.setObject(1, id));
    }

    @Override
    public List<T> findAll() {
        return queryList("SELECT * FROM " + tableName() + " ORDER BY id", NO_PARAMS);
    }

    @Override
    public void delete(ID id) {
        executeUpdate("DELETE FROM " + tableName() + " WHERE id = ?",
                      ps -> ps.setObject(1, id));
    }

    @Override
    public long count() {
        try (Connection c = ConnectionPool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + tableName());
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw translate(e, "count on " + tableName());
        }
    }

    // ------------------------------------------------------------------
    // query helpers
    // ------------------------------------------------------------------

    protected Optional<T> queryOne(String sql, Binder binder) {
        try (Connection c = ConnectionPool.getConnection()) {
            return queryOne(c, sql, binder);
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected Optional<T> queryOne(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected List<T> queryList(String sql, Binder binder) {
        try (Connection c = ConnectionPool.getConnection()) {
            return queryList(c, sql, binder);
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected List<T> queryList(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected <R> List<R> queryList(String sql, Binder binder, RowMapper<R> mapper) {
        try (Connection c = ConnectionPool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<R> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected List<T> mapAll(ResultSet rs) throws SQLException {
        List<T> out = new ArrayList<>();
        while (rs.next()) {
            out.add(mapRow(rs));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // write helpers
    // ------------------------------------------------------------------

    protected long insertAndReturnKey(String sql, Binder binder) {
        try (Connection c = ConnectionPool.getConnection()) {
            return insertAndReturnKey(c, sql, binder);
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected long insertAndReturnKey(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.bind(ps);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new QueryExecutionException("Insert produced no generated key: " + sql);
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected int executeUpdate(String sql, Binder binder) {
        try (Connection c = ConnectionPool.getConnection()) {
            return executeUpdate(c, sql, binder);
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    protected int executeUpdate(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    /**
     * Several statements, one commit, rollback on any failure. The boundaries
     * that need this are listed in plan.md section 3.4; a question and its
     * options are the first of them, since a question with half its options
     * is worse than no question.
     *
     * Autocommit is restored before the connection goes back to the pool,
     * because the next borrower assumes the Hikari default.
     */
    protected <R> R inTransaction(Function<Connection, R> work) {
        try (Connection c = ConnectionPool.getConnection()) {
            c.setAutoCommit(false);
            try {
                R result = work.apply(c);
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw translate(e, "transaction on " + tableName());
        }
    }

    // ------------------------------------------------------------------
    // exception translation
    // ------------------------------------------------------------------

    /**
     * Turns a driver-level SQLException into the right domain exception by
     * SQL state class. 23505 is a unique-constraint hit, which almost always
     * means the caller tried to create something that already exists; class
     * 08 is a connection failure; everything else is a query failure.
     */
    protected DataAccessException translate(SQLException e, String context) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("23505")) {
            return new DuplicateEntityException(
                "Unique constraint violated on " + tableName() + " [" + context + "]", e);
        }
        if (state.startsWith("08")) {
            return new ConnectionException("Lost the database connection [" + context + "]", e);
        }
        return new QueryExecutionException(
            "Query failed on " + tableName() + " (SQLState " + state + ") [" + context + "]", e);
    }
}
