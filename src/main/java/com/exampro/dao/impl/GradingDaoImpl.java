package com.exampro.dao.impl;

import com.exampro.dao.GradingDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.model.entity.Rubric;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GradingDaoImpl extends AbstractJdbcDao<Rubric, Long> implements GradingDao {

    private static final String INSERT_RUBRIC = "INSERT INTO rubrics (question_id, name) VALUES (?, ?)";
    private static final String INSERT_CRITERION = """
        INSERT INTO rubric_criteria (rubric_id, description, max_marks, display_order)
        VALUES (?, ?, ?, ?)
        """;
    // Grades already given keep their marks but lose the link to a criterion that no longer exists.
    private static final String UNLINK_GRADES = """
        UPDATE manual_grades SET criterion_id = NULL
         WHERE criterion_id IN (SELECT id FROM rubric_criteria WHERE rubric_id = ?)
        """;
    private static final String DELETE_CRITERIA = "DELETE FROM rubric_criteria WHERE rubric_id = ?";
    private static final String CRITERIA =
        "SELECT * FROM rubric_criteria WHERE rubric_id = ? ORDER BY display_order, id";

    private static final String PENDING = """
        SELECT r.id AS response_id, r.attempt_id, r.question_id, r.text_answer,
               u.full_name, e.title
          FROM responses r
          JOIN questions q      ON q.id = r.question_id
          JOIN exam_attempts a  ON a.id = r.attempt_id
          JOIN users u          ON u.id = a.student_id
          JOIN exams e          ON e.id = a.exam_id
         WHERE q.type = 'SHORT_ANSWER' AND r.final_score IS NULL
           AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
         ORDER BY r.id
        """;

    private static final String DELETE_MANUAL = "DELETE FROM manual_grades WHERE response_id = ?";
    private static final String INSERT_MANUAL = """
        INSERT INTO manual_grades (response_id, criterion_id, graded_by, marks, comment)
        VALUES (?, ?, ?, ?, ?)
        """;
    private static final String SET_RESPONSE = """
        UPDATE responses SET manual_score = ?, final_score = ? WHERE id = ?
        """;
    private static final String ATTEMPT_OF = "SELECT attempt_id FROM responses WHERE id = ?";
    private static final String RECOMPUTE = """
        UPDATE exam_attempts
           SET total_score = (SELECT COALESCE(SUM(final_score), 0) FROM responses WHERE attempt_id = ?),
               status = CASE WHEN EXISTS (
                            SELECT 1 FROM responses r JOIN questions q ON q.id = r.question_id
                             WHERE r.attempt_id = ? AND q.type = 'SHORT_ANSWER'
                               AND r.final_score IS NULL)
                        THEN status
                        WHEN status = 'SUBMITTED' THEN 'GRADED' ELSE status END
         WHERE id = ?
        """;

    @Override
    protected String tableName() {
        return "rubrics";
    }

    @Override
    protected Rubric mapRow(ResultSet rs) throws SQLException {
        Rubric r = new Rubric();
        r.setId(rs.getLong("id"));
        r.setQuestionId(rs.getLong("question_id"));
        r.setName(rs.getString("name"));
        return r;
    }

    @Override
    public Optional<Rubric> findByQuestion(long questionId) {
        Optional<Rubric> found = queryOne("SELECT * FROM rubrics WHERE question_id = ?",
                                          ps -> ps.setLong(1, questionId));
        found.ifPresent(r -> queryList(CRITERIA, ps -> ps.setLong(1, r.getId()),
            rs -> new Rubric.Criterion(rs.getLong("id"), rs.getString("description"),
                                       rs.getDouble("max_marks")))
            .forEach(r.getCriteria()::add));
        return found;
    }

    @Override
    public Optional<Rubric> findById(Long id) {
        return queryOne("SELECT * FROM rubrics WHERE id = ?", ps -> ps.setLong(1, id))
            .flatMap(r -> findByQuestion(r.getQuestionId()));
    }

    @Override
    public Long insert(Rubric rubric) {
        return inTransaction(c -> {
            long id = insertAndReturnKey(c, INSERT_RUBRIC, ps -> {
                ps.setLong(1, rubric.getQuestionId());
                ps.setString(2, rubric.getName());
            });
            rubric.setId(id);
            writeCriteria(c, rubric);
            return id;
        });
    }

    @Override
    public void update(Rubric rubric) {
        inTransaction(c -> {
            if (executeUpdate(c, "UPDATE rubrics SET name = ? WHERE id = ?", ps -> {
                ps.setString(1, rubric.getName());
                ps.setLong(2, rubric.getId());
            }) == 0) {
                throw new EntityNotFoundException("No rubric with id " + rubric.getId());
            }
            executeUpdate(c, UNLINK_GRADES, ps -> ps.setLong(1, rubric.getId()));
            executeUpdate(c, DELETE_CRITERIA, ps -> ps.setLong(1, rubric.getId()));
            writeCriteria(c, rubric);
            return null;
        });
    }

    private void writeCriteria(Connection c, Rubric rubric) {
        int order = 0;
        for (Rubric.Criterion k : rubric.getCriteria()) {
            final int displayOrder = order++;
            executeUpdate(c, INSERT_CRITERION, ps -> {
                ps.setLong(1, rubric.getId());
                ps.setString(2, k.description());
                ps.setDouble(3, k.maxMarks());
                ps.setInt(4, displayOrder);
            });
        }
    }

    @Override
    public List<PendingItem> findPending() {
        return queryList(PENDING, ps -> { }, rs -> new PendingItem(
            rs.getLong("response_id"), rs.getLong("attempt_id"), rs.getLong("question_id"),
            rs.getString("full_name"), rs.getString("title"), rs.getString("text_answer")));
    }

    @Override
    public double recordManualGrade(long responseId, long gradedBy,
                                    Map<Long, Double> marksByCriterion, String comment) {
        double total = marksByCriterion.values().stream().mapToDouble(Double::doubleValue).sum();
        return inTransaction(c -> {
            long attemptId = queryLong(c, responseId);

            executeUpdate(c, DELETE_MANUAL, ps -> ps.setLong(1, responseId));
            for (Map.Entry<Long, Double> e : marksByCriterion.entrySet()) {
                executeUpdate(c, INSERT_MANUAL, ps -> {
                    ps.setLong(1, responseId);
                    if (e.getKey() == null) {
                        ps.setNull(2, Types.BIGINT);
                    } else {
                        ps.setLong(2, e.getKey());
                    }
                    ps.setLong(3, gradedBy);
                    ps.setDouble(4, e.getValue());
                    ps.setString(5, comment);
                });
            }
            executeUpdate(c, SET_RESPONSE, ps -> {
                ps.setDouble(1, total);
                ps.setDouble(2, total);
                ps.setLong(3, responseId);
            });
            executeUpdate(c, RECOMPUTE, ps -> {
                ps.setLong(1, attemptId);
                ps.setLong(2, attemptId);
                ps.setLong(3, attemptId);
            });
            return total;
        });
    }

    private long queryLong(Connection c, long responseId) {
        try (var ps = c.prepareStatement(ATTEMPT_OF)) {
            ps.setLong(1, responseId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new EntityNotFoundException("No response with id " + responseId);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate(e, ATTEMPT_OF);
        }
    }
}
