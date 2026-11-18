package com.exampro.dao.impl;

import com.exampro.dao.QuestionDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.exception.QueryExecutionException;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.MCQQuestion;
import com.exampro.model.question.MSQQuestion;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.model.question.TrueFalseQuestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one DAO that maps a hierarchy rather than a flat row.
 *
 * The type column decides which subclass is instantiated, so everything
 * above this class works against {@link Question} and never switches on the
 * type again. Options are loaded with one extra query per call, not one per
 * question: a per-question lookup is an N+1 on a bank list of several
 * hundred rows.
 *
 * Short-answer keywords are a JSON array in the keywords CLOB rather than a
 * delimited string, because a keyword can legitimately contain a comma
 * ("subject to contract, not binding") and a delimiter that occurs in the
 * data is a bug waiting for the demo.
 */
public class QuestionDaoImpl extends AbstractJdbcDao<Question, Long> implements QuestionDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT = """
        INSERT INTO questions (topic_id, type, stem, difficulty, marks, negative_marks,
                               numeric_answer, numeric_tolerance, keywords, explanation,
                               created_by, is_active)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE = """
        UPDATE questions
           SET topic_id = ?, type = ?, stem = ?, difficulty = ?, marks = ?,
               negative_marks = ?, numeric_answer = ?, numeric_tolerance = ?,
               keywords = ?, explanation = ?, is_active = ?,
               updated_at = CURRENT_TIMESTAMP
         WHERE id = ?
        """;

    private static final String INSERT_OPTION = """
        INSERT INTO question_options (question_id, option_text, is_correct, display_order)
        VALUES (?, ?, ?, ?)
        """;

    private static final String DELETE_OPTIONS =
        "DELETE FROM question_options WHERE question_id = ?";

    private static final String DEACTIVATE =
        "UPDATE questions SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ?";

    private static final String FIND_ONE = """
        SELECT q.*, t.name AS topic_name
          FROM questions q
          JOIN topics t ON t.id = q.topic_id
         WHERE q.id = ?
        """;

    private static final String FIND_BY_TOPIC = """
        SELECT q.*, t.name AS topic_name
          FROM questions q
          JOIN topics t ON t.id = q.topic_id
         WHERE q.topic_id = ? AND q.is_active = TRUE
         ORDER BY q.id
        """;

    private static final String FIND_BY_SUBJECT = """
        SELECT q.*, t.name AS topic_name
          FROM questions q
          JOIN topics t ON t.id = q.topic_id
         WHERE t.subject_id = ? AND q.is_active = TRUE
         ORDER BY t.display_order, t.name, q.id
        """;

    private static final String OPTIONS_FOR_QUESTION = """
        SELECT * FROM question_options WHERE question_id = ? ORDER BY display_order, id
        """;

    private static final String OPTIONS_FOR_TOPIC = """
        SELECT o.* FROM question_options o
          JOIN questions q ON q.id = o.question_id
         WHERE q.topic_id = ?
         ORDER BY o.question_id, o.display_order, o.id
        """;

    private static final String OPTIONS_FOR_SUBJECT = """
        SELECT o.* FROM question_options o
          JOIN questions q ON q.id = o.question_id
          JOIN topics t    ON t.id = q.topic_id
         WHERE t.subject_id = ?
         ORDER BY o.question_id, o.display_order, o.id
        """;

    private static final String TAG_SELECT = """
        SELECT qt.question_id, t.name
          FROM question_tags qt
          JOIN tags t      ON t.id = qt.tag_id
          JOIN questions q ON q.id = qt.question_id
        """;
    private static final String TAGS_FOR_QUESTION =
        TAG_SELECT + " WHERE qt.question_id = ? ORDER BY t.name";
    private static final String TAGS_FOR_TOPIC =
        TAG_SELECT + " WHERE q.topic_id = ? ORDER BY t.name";
    private static final String TAGS_FOR_SUBJECT =
        TAG_SELECT + " JOIN topics tp ON tp.id = q.topic_id WHERE tp.subject_id = ? ORDER BY t.name";

    private static final String UPSERT_TAG = "MERGE INTO tags (name) KEY(name) VALUES (?)";
    private static final String LINK_TAG = """
        INSERT INTO question_tags (question_id, tag_id)
        SELECT ?, id FROM tags WHERE name = ?
        """;
    private static final String DELETE_TAG_LINKS =
        "DELETE FROM question_tags WHERE question_id = ?";

    @Override
    protected String tableName() {
        return "questions";
    }

    /** The polymorphic part: the type column picks the subclass. */
    @Override
    protected Question mapRow(ResultSet rs) throws SQLException {
        QuestionType type = QuestionType.valueOf(rs.getString("type"));
        Question q = switch (type) {
            case MCQ -> new MCQQuestion();
            case MSQ -> new MSQQuestion();
            case TRUE_FALSE -> new TrueFalseQuestion();
            case NUMERIC -> readNumeric(rs);
            case SHORT_ANSWER -> readShortAnswer(rs);
        };

        q.setId(rs.getLong("id"));
        q.setTopicId(rs.getLong("topic_id"));
        q.setStem(rs.getString("stem"));
        q.setDifficulty(Difficulty.valueOf(rs.getString("difficulty")));
        q.setMarks(rs.getDouble("marks"));
        q.setNegativeMarks(rs.getDouble("negative_marks"));
        q.setExplanation(rs.getString("explanation"));
        long createdBy = rs.getLong("created_by");
        q.setCreatedBy(rs.wasNull() ? null : createdBy);
        q.setActive(rs.getBoolean("is_active"));
        if (rs.getTimestamp("created_at") != null) {
            q.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        }
        return q;
    }

    private Question readNumeric(ResultSet rs) throws SQLException {
        NumericQuestion n = new NumericQuestion();
        double answer = rs.getDouble("numeric_answer");
        n.setAnswer(rs.wasNull() ? null : answer);
        n.setTolerance(rs.getDouble("numeric_tolerance"));
        return n;
    }

    private Question readShortAnswer(ResultSet rs) throws SQLException {
        ShortAnswerQuestion s = new ShortAnswerQuestion();
        s.getKeywords().addAll(parseKeywords(rs.getString("keywords")));
        return s;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Override
    public Optional<Question> findById(Long id) {
        Optional<Question> found = withTopicName(FIND_ONE, ps -> ps.setLong(1, id))
            .stream().findFirst();
        found.ifPresent(q -> attachChildren(List.of(q), OPTIONS_FOR_QUESTION,
                                            TAGS_FOR_QUESTION, ps -> ps.setLong(1, id)));
        return found;
    }

    @Override
    public List<Question> findByTopic(long topicId) {
        List<Question> questions = withTopicName(FIND_BY_TOPIC, ps -> ps.setLong(1, topicId));
        attachChildren(questions, OPTIONS_FOR_TOPIC, TAGS_FOR_TOPIC, ps -> ps.setLong(1, topicId));
        return questions;
    }

    @Override
    public List<Question> findBySubject(long subjectId) {
        List<Question> questions = withTopicName(FIND_BY_SUBJECT, ps -> ps.setLong(1, subjectId));
        attachChildren(questions, OPTIONS_FOR_SUBJECT, TAGS_FOR_SUBJECT,
                       ps -> ps.setLong(1, subjectId));
        return questions;
    }

    private void attachChildren(List<Question> questions, String optionSql, String tagSql,
                                Binder binder) {
        attachOptions(questions, optionSql, binder);
        attachTags(questions, tagSql, binder);
    }

    /** Same shape as attachOptions: one query for the whole list, bucketed in memory. */
    private void attachTags(List<Question> questions, String sql, Binder binder) {
        Map<Long, Question> byId = new LinkedHashMap<>();
        for (Question q : questions) {
            byId.put(q.getId(), q);
            q.getTags().clear();
        }
        for (Map.Entry<Long, String> e : queryList(sql, binder,
                rs -> Map.entry(rs.getLong(1), rs.getString(2)))) {
            Question owner = byId.get(e.getKey());
            if (owner != null) {
                owner.getTags().add(e.getValue());
            }
        }
    }

    private List<Question> withTopicName(String sql, Binder binder) {
        return queryList(sql, binder, rs -> {
            Question q = mapRow(rs);
            q.setTopicName(rs.getString("topic_name"));
            return q;
        });
    }

    /** One options query for the whole page of questions, bucketed in memory. */
    private void attachOptions(List<Question> questions, String sql, Binder binder) {
        if (questions.isEmpty()) {
            return;
        }
        Map<Long, Question> byId = new LinkedHashMap<>();
        for (Question q : questions) {
            byId.put(q.getId(), q);
            q.getOptions().clear();
        }

        List<QuestionOption> options = queryList(sql, binder, rs -> {
            QuestionOption o = new QuestionOption();
            o.setId(rs.getLong("id"));
            o.setQuestionId(rs.getLong("question_id"));
            o.setText(rs.getString("option_text"));
            o.setCorrect(rs.getBoolean("is_correct"));
            o.setDisplayOrder(rs.getInt("display_order"));
            return o;
        });

        for (QuestionOption o : options) {
            Question owner = byId.get(o.getQuestionId());
            if (owner != null) {
                owner.getOptions().add(o);
            }
        }
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /**
     * Question row and option rows in one transaction (plan 3.4): a question
     * holding half its options is silently unanswerable.
     */
    @Override
    public Long insert(Question question) {
        return inTransaction(c -> {
            long id = insertAndReturnKey(c, INSERT, ps -> bindQuestion(ps, question, false));
            question.setId(id);
            writeOptions(c, question);
            writeTags(c, question);
            return id;
        });
    }

    @Override
    public void update(Question question) {
        inTransaction(c -> {
            int rows = executeUpdate(c, UPDATE, ps -> bindQuestion(ps, question, true));
            if (rows == 0) {
                throw new EntityNotFoundException("No question with id " + question.getId());
            }
            // Options are replaced rather than diffed. Nothing references
            // their ids yet; once responses store selected option ids,
            // editing a live question has to become a diff or a new version.
            executeUpdate(c, DELETE_OPTIONS, ps -> ps.setLong(1, question.getId()));
            writeOptions(c, question);
            executeUpdate(c, DELETE_TAG_LINKS, ps -> ps.setLong(1, question.getId()));
            writeTags(c, question);
            return null;
        });
    }

    @Override
    public void deactivate(long id) {
        if (executeUpdate(DEACTIVATE, ps -> ps.setLong(1, id)) == 0) {
            throw new EntityNotFoundException("No question with id " + id);
        }
    }

    private void writeTags(Connection c, Question question) {
        for (String tag : question.getTags()) {
            executeUpdate(c, UPSERT_TAG, ps -> ps.setString(1, tag));
            executeUpdate(c, LINK_TAG, ps -> {
                ps.setLong(1, question.getId());
                ps.setString(2, tag);
            });
        }
    }

    private void writeOptions(Connection c, Question question) {
        int order = 0;
        for (QuestionOption option : question.getOptions()) {
            option.setDisplayOrder(order++);
            option.setQuestionId(question.getId());
            long optionId = insertAndReturnKey(c, INSERT_OPTION, ps -> {
                ps.setLong(1, question.getId());
                ps.setString(2, option.getText());
                ps.setBoolean(3, option.isCorrect());
                ps.setInt(4, option.getDisplayOrder());
            });
            option.setId(optionId);
        }
    }

    private void bindQuestion(PreparedStatement ps, Question q, boolean forUpdate)
            throws SQLException {
        ps.setLong(1, q.getTopicId());
        ps.setString(2, q.getType().name());
        ps.setString(3, q.getStem());
        ps.setString(4, q.getDifficulty().name());
        ps.setDouble(5, q.getMarks());
        ps.setDouble(6, q.getNegativeMarks());

        if (q instanceof NumericQuestion n) {
            if (n.getAnswer() == null) {
                ps.setNull(7, Types.DOUBLE);
            } else {
                ps.setDouble(7, n.getAnswer());
            }
            ps.setDouble(8, n.getTolerance());
        } else {
            ps.setNull(7, Types.DOUBLE);
            ps.setNull(8, Types.DOUBLE);
        }

        ps.setString(9, q instanceof ShortAnswerQuestion s
                        ? writeKeywords(s.getKeywords()) : null);
        ps.setString(10, q.getExplanation());

        if (forUpdate) {
            ps.setBoolean(11, q.isActive());
            ps.setLong(12, q.getId());
        } else {
            if (q.getCreatedBy() == null) {
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setLong(11, q.getCreatedBy());
            }
            ps.setBoolean(12, q.isActive());
        }
    }

    // ------------------------------------------------------------------
    // keywords JSON
    // ------------------------------------------------------------------

    private static List<String> parseKeywords(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // Hand-edited or pre-JSON data: treat the whole cell as a single
            // keyword rather than losing the question's answer key.
            return List.of(json.trim());
        }
    }

    private static String writeKeywords(Iterable<String> keywords) {
        List<String> list = new ArrayList<>();
        keywords.forEach(list::add);
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            throw new QueryExecutionException("Could not serialise keywords", e);
        }
    }
}
