package com.exampro.dao.impl;

import com.exampro.dao.BlueprintDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** A blueprint and its rules are written together or not at all. */
public class BlueprintDaoImpl extends AbstractJdbcDao<Blueprint, Long> implements BlueprintDao {

    private static final String INSERT = "INSERT INTO exam_blueprints (exam_id, name) VALUES (?, ?)";
    private static final String RENAME = "UPDATE exam_blueprints SET name = ? WHERE id = ?";
    private static final String INSERT_RULE = """
        INSERT INTO blueprint_rules (blueprint_id, topic_id, difficulty, question_type, question_count)
        VALUES (?, ?, ?, ?, ?)
        """;
    private static final String DELETE_RULES = "DELETE FROM blueprint_rules WHERE blueprint_id = ?";
    private static final String RULES = """
        SELECT r.*, t.name AS topic_name
          FROM blueprint_rules r JOIN topics t ON t.id = r.topic_id
         WHERE r.blueprint_id = ? ORDER BY r.id
        """;

    @Override
    protected String tableName() {
        return "exam_blueprints";
    }

    @Override
    protected Blueprint mapRow(ResultSet rs) throws SQLException {
        Blueprint b = new Blueprint();
        b.setId(rs.getLong("id"));
        b.setExamId(rs.getLong("exam_id"));
        b.setName(rs.getString("name"));
        return b;
    }

    @Override
    public Optional<Blueprint> findByExam(long examId) {
        Optional<Blueprint> found = queryOne("SELECT * FROM exam_blueprints WHERE exam_id = ?",
                                             ps -> ps.setLong(1, examId));
        found.ifPresent(b -> b.getRules().addAll(queryList(RULES, ps -> ps.setLong(1, b.getId()), rs -> {
            BlueprintRule r = new BlueprintRule(rs.getLong("topic_id"), rs.getString("topic_name"),
                Difficulty.valueOf(rs.getString("difficulty")),
                QuestionType.valueOf(rs.getString("question_type")), rs.getInt("question_count"));
            r.setId(rs.getLong("id"));
            return r;
        })));
        return found;
    }

    @Override
    public Optional<Blueprint> findById(Long id) {
        return queryOne("SELECT * FROM exam_blueprints WHERE id = ?", ps -> ps.setLong(1, id))
            .flatMap(b -> findByExam(b.getExamId()));
    }

    @Override
    public Long insert(Blueprint b) {
        return inTransaction(c -> {
            long id = insertAndReturnKey(c, INSERT, ps -> {
                ps.setLong(1, b.getExamId());
                ps.setString(2, b.getName());
            });
            b.setId(id);
            writeRules(c, b);
            return id;
        });
    }

    @Override
    public void update(Blueprint b) {
        inTransaction(c -> {
            if (executeUpdate(c, RENAME, ps -> {
                ps.setString(1, b.getName());
                ps.setLong(2, b.getId());
            }) == 0) {
                throw new EntityNotFoundException("No blueprint with id " + b.getId());
            }
            executeUpdate(c, DELETE_RULES, ps -> ps.setLong(1, b.getId()));
            writeRules(c, b);
            return null;
        });
    }

    private void writeRules(Connection c, Blueprint b) {
        for (BlueprintRule r : b.getRules()) {
            executeUpdate(c, INSERT_RULE, ps -> {
                ps.setLong(1, b.getId());
                ps.setLong(2, r.getTopicId());
                ps.setString(3, r.getDifficulty().name());
                ps.setString(4, r.getType().name());
                ps.setInt(5, r.getCount());
            });
        }
    }
}
