package com.exampro.dao.impl;

import com.exampro.analytics.ItemObservation;
import com.exampro.analytics.ItemStat;
import com.exampro.config.ConnectionPool;
import com.exampro.dao.AnalyticsDao;
import com.exampro.exception.QueryExecutionException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Not an entity DAO: it reads across the whole exam and writes one derived table. */
public class AnalyticsDaoImpl extends AbstractJdbcDao<ItemStat, Long> implements AnalyticsDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    // LEFT JOIN so a question the student left alone still appears, as a zero.
    private static final String OBSERVATIONS = """
        SELECT aq.attempt_id, aq.question_id, q.marks, r.id AS response_id, r.final_score, r.selected_option_ids
          FROM attempt_questions aq
          JOIN exam_attempts a ON a.id = aq.attempt_id
          JOIN questions q     ON q.id = aq.question_id
          LEFT JOIN responses r ON r.attempt_id = aq.attempt_id AND r.question_id = aq.question_id
         WHERE a.exam_id = ? AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED', 'GRADED')
         ORDER BY aq.question_id, aq.attempt_id
        """;
    private static final String DELETE = "DELETE FROM item_statistics WHERE exam_id = ?";
    private static final String INSERT = """
        INSERT INTO item_statistics (question_id, exam_id, attempts_count, p_value,
                                     discrimination_index, distractor_distribution)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

    @Override
    protected String tableName() {
        return "item_statistics";
    }

    @Override
    protected ItemStat mapRow(ResultSet rs) throws SQLException {
        throw new UnsupportedOperationException("item_statistics is write-only for now");
    }

    // The generic CRUD entry points do not apply to this table.
    @Override public Long insert(ItemStat s) { throw new UnsupportedOperationException(); }
    @Override public void update(ItemStat s) { throw new UnsupportedOperationException(); }

    @Override
    public List<ItemObservation> observations(long examId) {
        return queryList(OBSERVATIONS, ps -> ps.setLong(1, examId), rs -> {
            rs.getLong("response_id");
            boolean hasRow = !rs.wasNull();
            double fs = rs.getDouble("final_score");
            boolean settled = !rs.wasNull();
            // No response row means the question was left alone: it settles at zero.
            // A row whose final_score is still null is awaiting a marker: not settled.
            Double score = settled ? Double.valueOf(fs) : (hasRow ? null : Double.valueOf(0));
            return new ItemObservation(rs.getLong("attempt_id"), rs.getLong("question_id"),
                rs.getDouble("marks"), score, readIds(rs.getString("selected_option_ids")));
        });
    }

    @Override
    public void saveStats(long examId, List<ItemStat> stats) {
        try (Connection c = ConnectionPool.getConnection()) {
            c.setAutoCommit(false);
            try {
                executeUpdate(c, DELETE, ps -> ps.setLong(1, examId));
                for (ItemStat s : stats) {
                    executeUpdate(c, INSERT, ps -> bind(ps, examId, s));
                }
                c.commit();
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw translate(e, "save item statistics");
        }
    }

    private void bind(PreparedStatement ps, long examId, ItemStat s) throws SQLException {
        ps.setLong(1, s.questionId());
        ps.setLong(2, examId);
        ps.setInt(3, s.attempts());
        nullable(ps, 4, s.pValue());
        nullable(ps, 5, s.discrimination());
        try {
            ps.setString(6, JSON.writeValueAsString(Map.of("options", s.distractors(), "blanks", s.blanks())));
        } catch (Exception e) {
            throw new QueryExecutionException("Could not serialise distractors", e);
        }
    }

    private static void nullable(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }

    private static List<Long> readIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
