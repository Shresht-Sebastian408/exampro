package com.exampro.dao.impl;

import com.exampro.dao.ProctorEventDao;
import com.exampro.exception.QueryExecutionException;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.IntegrityScore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;

public class ProctorEventDaoImpl extends AbstractJdbcDao<ProctorEvent, Long> implements ProctorEventDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT = """
        INSERT INTO proctor_events (attempt_id, event_type, severity, occurred_at, duration_ms, metadata)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    private static final String UPSERT_SCORE = """
        MERGE INTO integrity_scores (attempt_id, score, penalty_total, breakdown, computed_at)
        KEY (attempt_id) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
    private static final String SET_ATTEMPT_SCORE =
        "UPDATE exam_attempts SET integrity_score = ? WHERE id = ?";
    private static final String AUDIT = """
        INSERT INTO audit_logs (user_id, action, entity_type, entity_id, detail)
        VALUES (?, 'ATTEMPT_SUBMITTED', 'exam_attempt', ?, ?)
        """;

    @Override
    protected String tableName() {
        return "proctor_events";
    }

    @Override
    protected ProctorEvent mapRow(ResultSet rs) throws SQLException {
        ProctorEvent e = new ProctorEvent();
        e.setId(rs.getLong("id"));
        e.setAttemptId(rs.getLong("attempt_id"));
        e.setType(EventType.valueOf(rs.getString("event_type")));
        e.setSeverity(Severity.valueOf(rs.getString("severity")));
        e.setOccurredAt(rs.getTimestamp("occurred_at").toLocalDateTime());
        long d = rs.getLong("duration_ms");
        e.setDurationMs(rs.wasNull() ? null : d);
        e.setMetadata(rs.getString("metadata"));
        return e;
    }

    @Override
    public Long insert(ProctorEvent e) {
        long id = insertAndReturnKey(INSERT, ps -> {
            ps.setLong(1, e.getAttemptId());
            ps.setString(2, e.getType().name());
            ps.setString(3, e.getSeverity().name());
            ps.setTimestamp(4, Timestamp.valueOf(e.getOccurredAt()));
            if (e.getDurationMs() == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, e.getDurationMs());
            }
            ps.setString(6, e.getMetadata());
        });
        e.setId(id);
        return id;
    }

    /** Events are an append-only log; there is nothing to update. */
    @Override
    public void update(ProctorEvent e) {
        throw new UnsupportedOperationException("Proctor events are never edited.");
    }

    @Override
    public List<ProctorEvent> findByAttempt(long attemptId) {
        return queryList("SELECT * FROM proctor_events WHERE attempt_id = ? ORDER BY occurred_at, id",
                         ps -> ps.setLong(1, attemptId));
    }

    @Override
    public List<ProctorEvent> findByExam(long examId) {
        return queryList("""
            SELECT e.* FROM proctor_events e
              JOIN exam_attempts a ON a.id = e.attempt_id
             WHERE a.exam_id = ? ORDER BY e.occurred_at, e.id
            """, ps -> ps.setLong(1, examId));
    }

    @Override
    public void saveIntegrity(long attemptId, IntegrityScore score, Long userId, String auditDetail) {
        String breakdown;
        try {
            breakdown = JSON.writeValueAsString(Map.of("counts", score.counts(), "penalties", score.penalties()));
        } catch (Exception e) {
            throw new QueryExecutionException("Could not serialise the integrity breakdown", e);
        }
        inTransaction(c -> {
            executeUpdate(c, UPSERT_SCORE, ps -> {
                ps.setLong(1, attemptId);
                ps.setDouble(2, score.score());
                ps.setDouble(3, score.penalty());
                ps.setString(4, breakdown);
            });
            executeUpdate(c, SET_ATTEMPT_SCORE, ps -> {
                ps.setDouble(1, score.score());
                ps.setLong(2, attemptId);
            });
            executeUpdate(c, AUDIT, ps -> {
                if (userId == null) {
                    ps.setNull(1, Types.BIGINT);
                } else {
                    ps.setLong(1, userId);
                }
                ps.setLong(2, attemptId);
                ps.setString(3, auditDetail);
            });
            return null;
        });
    }
}
