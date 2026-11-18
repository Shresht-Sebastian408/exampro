package com.exampro.dao.impl;

import com.exampro.dao.SubjectDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.model.entity.Subject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

public class SubjectDaoImpl extends AbstractJdbcDao<Subject, Long> implements SubjectDao {

    private static final String INSERT = """
        INSERT INTO subjects (code, name, description, created_by, is_active)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String UPDATE = """
        UPDATE subjects
           SET code = ?, name = ?, description = ?, is_active = ?
         WHERE id = ?
        """;

    private static final String FIND_BY_CODE = "SELECT * FROM subjects WHERE code = ?";

    /**
     * Correlated subqueries rather than two LEFT JOINs with GROUP BY: joining
     * topics and questions in one pass multiplies the rows and inflates both
     * counts, which is the classic fan-out bug in this shape of query.
     */
    private static final String FIND_ALL_WITH_COUNTS = """
        SELECT s.*,
               (SELECT COUNT(*) FROM topics t
                 WHERE t.subject_id = s.id AND t.is_active = TRUE) AS topic_count,
               (SELECT COUNT(*) FROM questions q
                  JOIN topics t2 ON t2.id = q.topic_id
                 WHERE t2.subject_id = s.id AND q.is_active = TRUE) AS question_count
          FROM subjects s
         ORDER BY s.code
        """;

    @Override
    protected String tableName() {
        return "subjects";
    }

    @Override
    protected Subject mapRow(ResultSet rs) throws SQLException {
        Subject s = new Subject();
        s.setId(rs.getLong("id"));
        s.setCode(rs.getString("code"));
        s.setName(rs.getString("name"));
        s.setDescription(rs.getString("description"));
        long createdBy = rs.getLong("created_by");
        s.setCreatedBy(rs.wasNull() ? null : createdBy);
        s.setActive(rs.getBoolean("is_active"));
        if (rs.getTimestamp("created_at") != null) {
            s.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        }
        return s;
    }

    @Override
    public Optional<Subject> findByCode(String code) {
        return queryOne(FIND_BY_CODE, ps -> ps.setString(1, code));
    }

    @Override
    public List<Subject> findAllWithCounts() {
        return queryList(FIND_ALL_WITH_COUNTS, ps -> { }, rs -> {
            Subject s = mapRow(rs);
            s.setTopicCount(rs.getInt("topic_count"));
            s.setQuestionCount(rs.getInt("question_count"));
            return s;
        });
    }

    @Override
    public Long insert(Subject subject) {
        long id = insertAndReturnKey(INSERT, ps -> {
            ps.setString(1, subject.getCode());
            ps.setString(2, subject.getName());
            ps.setString(3, subject.getDescription());
            if (subject.getCreatedBy() == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, subject.getCreatedBy());
            }
            ps.setBoolean(5, subject.isActive());
        });
        subject.setId(id);
        return id;
    }

    @Override
    public void update(Subject subject) {
        int rows = executeUpdate(UPDATE, ps -> {
            ps.setString(1, subject.getCode());
            ps.setString(2, subject.getName());
            ps.setString(3, subject.getDescription());
            ps.setBoolean(4, subject.isActive());
            ps.setLong(5, subject.getId());
        });
        if (rows == 0) {
            throw new EntityNotFoundException("No subject with id " + subject.getId());
        }
    }
}
