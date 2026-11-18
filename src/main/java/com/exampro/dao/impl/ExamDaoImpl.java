package com.exampro.dao.impl;

import com.exampro.dao.ExamDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.model.entity.Exam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

public class ExamDaoImpl extends AbstractJdbcDao<Exam, Long> implements ExamDao {

    private static final String INSERT = """
        INSERT INTO exams (subject_id, title, duration_minutes, opens_at, closes_at,
                           generation_strategy, shuffle_questions, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE = """
        UPDATE exams SET title = ?, duration_minutes = ?, opens_at = ?, closes_at = ?,
                         generation_strategy = ?, shuffle_questions = ?, is_published = ?
         WHERE id = ?
        """;

    @Override
    protected String tableName() {
        return "exams";
    }

    @Override
    protected Exam mapRow(ResultSet rs) throws SQLException {
        Exam e = new Exam();
        e.setId(rs.getLong("id"));
        e.setSubjectId(rs.getLong("subject_id"));
        e.setTitle(rs.getString("title"));
        e.setDurationMinutes(rs.getInt("duration_minutes"));
        e.setOpensAt(rs.getTimestamp("opens_at").toLocalDateTime());
        e.setClosesAt(rs.getTimestamp("closes_at").toLocalDateTime());
        e.setGenerationStrategy(rs.getString("generation_strategy"));
        e.setShuffleQuestions(rs.getBoolean("shuffle_questions"));
        e.setPublished(rs.getBoolean("is_published"));
        long createdBy = rs.getLong("created_by");
        e.setCreatedBy(rs.wasNull() ? null : createdBy);
        return e;
    }

    @Override
    public List<Exam> findBySubject(long subjectId) {
        return queryList("SELECT * FROM exams WHERE subject_id = ? ORDER BY id DESC",
                         ps -> ps.setLong(1, subjectId));
    }

    @Override
    public Long insert(Exam e) {
        long id = insertAndReturnKey(INSERT, ps -> {
            ps.setLong(1, e.getSubjectId());
            ps.setString(2, e.getTitle());
            ps.setInt(3, e.getDurationMinutes());
            ps.setTimestamp(4, Timestamp.valueOf(e.getOpensAt()));
            ps.setTimestamp(5, Timestamp.valueOf(e.getClosesAt()));
            ps.setString(6, e.getGenerationStrategy());
            ps.setBoolean(7, e.isShuffleQuestions());
            if (e.getCreatedBy() == null) {
                ps.setNull(8, Types.BIGINT);
            } else {
                ps.setLong(8, e.getCreatedBy());
            }
        });
        e.setId(id);
        return id;
    }

    @Override
    public void update(Exam e) {
        int rows = executeUpdate(UPDATE, ps -> {
            ps.setString(1, e.getTitle());
            ps.setInt(2, e.getDurationMinutes());
            ps.setTimestamp(3, Timestamp.valueOf(e.getOpensAt()));
            ps.setTimestamp(4, Timestamp.valueOf(e.getClosesAt()));
            ps.setString(5, e.getGenerationStrategy());
            ps.setBoolean(6, e.isShuffleQuestions());
            ps.setBoolean(7, e.isPublished());
            ps.setLong(8, e.getId());
        });
        if (rows == 0) {
            throw new EntityNotFoundException("No exam with id " + e.getId());
        }
    }
}
