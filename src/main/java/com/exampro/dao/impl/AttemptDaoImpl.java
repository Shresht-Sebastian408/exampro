package com.exampro.dao.impl;

import com.exampro.dao.AttemptDao;
import com.exampro.dao.QuestionDao;
import com.exampro.exception.AttemptAlreadySubmittedException;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.exception.QueryExecutionException;
import com.exampro.grading.ScoreSheet;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.Response;
import com.exampro.model.enums.AttemptStatus;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AttemptDaoImpl extends AbstractJdbcDao<Attempt, Long> implements AttemptDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT = """
        INSERT INTO exam_attempts (exam_id, student_id, status, started_at, expires_at)
        VALUES (?, ?, ?, ?, ?)
        """;
    private static final String INSERT_AQ = """
        INSERT INTO attempt_questions (attempt_id, question_id, display_order, option_order)
        VALUES (?, ?, ?, ?)
        """;
    private static final String PAPER = """
        SELECT question_id, option_order FROM attempt_questions
         WHERE attempt_id = ? ORDER BY display_order
        """;
    private static final String RESPONSES = "SELECT * FROM responses WHERE attempt_id = ?";
    private static final String UPSERT = """
        MERGE INTO responses (attempt_id, question_id, selected_option_ids, text_answer,
                              numeric_answer, is_flagged, last_saved_at)
        KEY (attempt_id, question_id)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
    // Manual grades are kept: a regrade only replaces final_score where no person has set one.
    private static final String SET_SCORE = """
        UPDATE responses SET auto_score = ?,
               final_score = CASE WHEN manual_score IS NOT NULL THEN final_score ELSE ? END
         WHERE attempt_id = ? AND question_id = ?
        """;
    // GRADED replaces only SUBMITTED: an AUTO_SUBMITTED attempt keeps that status, because
    // "the clock ended it" matters later (integrity review) and is not recoverable otherwise.
    private static final String SET_TOTAL = """
        UPDATE exam_attempts
           SET total_score = (SELECT COALESCE(SUM(final_score), 0) FROM responses WHERE attempt_id = ?),
               status = CASE WHEN status = 'SUBMITTED' THEN COALESCE(?, status) ELSE status END
         WHERE id = ?
        """;
    private static final String CLOSE = """
        UPDATE exam_attempts SET status = ?, submitted_at = ?
         WHERE id = ? AND status = 'IN_PROGRESS'
        """;

    private final QuestionDao questions;

    public AttemptDaoImpl() {
        this(new QuestionDaoImpl());
    }

    public AttemptDaoImpl(QuestionDao questions) {
        this.questions = questions;
    }

    @Override
    protected String tableName() {
        return "exam_attempts";
    }

    @Override
    protected Attempt mapRow(ResultSet rs) throws SQLException {
        Attempt a = new Attempt();
        a.setId(rs.getLong("id"));
        a.setExamId(rs.getLong("exam_id"));
        a.setStudentId(rs.getLong("student_id"));
        a.setStatus(AttemptStatus.valueOf(rs.getString("status")));
        a.setStartedAt(time(rs, "started_at"));
        a.setExpiresAt(time(rs, "expires_at"));
        a.setSubmittedAt(time(rs, "submitted_at"));
        double total = rs.getDouble("total_score");
        a.setTotalScore(rs.wasNull() ? null : total);
        double integrity = rs.getDouble("integrity_score");
        a.setIntegrityScore(rs.wasNull() ? null : integrity);
        return a;
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }

    @Override
    public Optional<Attempt> findByExamAndStudent(long examId, long studentId) {
        return queryOne("SELECT * FROM exam_attempts WHERE exam_id = ? AND student_id = ?", ps -> {
            ps.setLong(1, examId);
            ps.setLong(2, studentId);
        });
    }

    @Override
    public List<Attempt> findByExam(long examId) {
        return queryList("SELECT * FROM exam_attempts WHERE exam_id = ? ORDER BY id",
                         ps -> ps.setLong(1, examId));
    }

    @Override
    public Long insert(Attempt a) {
        long id = insertAndReturnKey(INSERT, ps -> bindAttempt(ps, a));
        a.setId(id);
        return id;
    }

    @Override
    public void update(Attempt a) {
        if (executeUpdate("UPDATE exam_attempts SET status = ?, submitted_at = ? WHERE id = ?", ps -> {
            ps.setString(1, a.getStatus().name());
            ps.setTimestamp(2, a.getSubmittedAt() == null ? null : Timestamp.valueOf(a.getSubmittedAt()));
            ps.setLong(3, a.getId());
        }) == 0) {
            throw new EntityNotFoundException("No attempt with id " + a.getId());
        }
    }

    private void bindAttempt(PreparedStatement ps, Attempt a) throws SQLException {
        ps.setLong(1, a.getExamId());
        ps.setLong(2, a.getStudentId());
        ps.setString(3, a.getStatus().name());
        ps.setTimestamp(4, Timestamp.valueOf(a.getStartedAt()));
        ps.setTimestamp(5, Timestamp.valueOf(a.getExpiresAt()));
    }

    // ------------------------------------------------------------------
    // paper
    // ------------------------------------------------------------------

    @Override
    public long createWithPaper(Attempt attempt, List<Question> paper) {
        return inTransaction(c -> {
            long id = insertAndReturnKey(c, INSERT, ps -> bindAttempt(ps, attempt));
            attempt.setId(id);
            int order = 0;
            for (Question q : paper) {
                final int displayOrder = order++;
                String optionOrder = writeIds(q.getOptions().stream().map(QuestionOption::getId).toList());
                executeUpdate(c, INSERT_AQ, ps -> {
                    ps.setLong(1, id);
                    ps.setLong(2, q.getId());
                    ps.setInt(3, displayOrder);
                    ps.setString(4, optionOrder);
                });
            }
            return id;
        });
    }

    @Override
    public List<Question> loadPaper(long attemptId) {
        record Row(long questionId, List<Long> optionOrder) {}
        List<Row> rows = queryList(PAPER, ps -> ps.setLong(1, attemptId),
            rs -> new Row(rs.getLong("question_id"), readIds(rs.getString("option_order"))));

        // One lookup per question: a paper is tens of rows, and findById
        // already knows how to build the polymorphic object with options.
        List<Question> paper = new ArrayList<>();
        for (Row row : rows) {
            Question q = questions.findById(row.questionId()).orElseThrow(
                () -> new EntityNotFoundException("Question " + row.questionId() + " is gone"));
            reorderOptions(q, row.optionOrder());
            paper.add(q);
        }
        return paper;
    }

    private static void reorderOptions(Question q, List<Long> order) {
        if (order.isEmpty()) {
            return;
        }
        List<QuestionOption> sorted = new ArrayList<>(q.getOptions());
        sorted.sort(java.util.Comparator.comparingInt(o -> {
            int i = order.indexOf(o.getId());
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        q.getOptions().clear();
        q.getOptions().addAll(sorted);
    }

    // ------------------------------------------------------------------
    // responses
    // ------------------------------------------------------------------

    @Override
    public Map<Long, Response> loadResponses(long attemptId) {
        Map<Long, Response> out = new LinkedHashMap<>();
        for (Response r : queryList(RESPONSES, ps -> ps.setLong(1, attemptId), rs -> {
            Response r = new Response(rs.getLong("question_id"));
            r.setId(rs.getLong("id"));
            r.setAttemptId(rs.getLong("attempt_id"));
            r.getSelectedOptionIds().addAll(readIds(rs.getString("selected_option_ids")));
            r.setTextAnswer(rs.getString("text_answer"));
            double n = rs.getDouble("numeric_answer");
            r.setNumericAnswer(rs.wasNull() ? null : n);
            r.setFlagged(rs.getBoolean("is_flagged"));
            return r;
        })) {
            out.put(r.getQuestionId(), r);
        }
        return out;
    }

    @Override
    public void upsertResponse(Response r) {
        executeUpdate(UPSERT, ps -> bindResponse(ps, r));
    }

    private void bindResponse(PreparedStatement ps, Response r) throws SQLException {
        ps.setLong(1, r.getAttemptId());
        ps.setLong(2, r.getQuestionId());
        ps.setString(3, writeIds(r.getSelectedOptionIds()));
        ps.setString(4, r.getTextAnswer());
        if (r.getNumericAnswer() == null) {
            ps.setNull(5, Types.DOUBLE);
        } else {
            ps.setDouble(5, r.getNumericAnswer());
        }
        ps.setBoolean(6, r.isFlagged());
    }

    @Override
    public void submit(long attemptId, Collection<Response> pending, AttemptStatus finalStatus,
                       ScoreSheet scores) {
        inTransaction(c -> {
            // Close first: if someone else already did, nothing below is written.
            int closed = executeUpdate(c, CLOSE, ps -> {
                ps.setString(1, finalStatus.name());
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                ps.setLong(3, attemptId);
            });
            if (closed == 0) {
                throw new AttemptAlreadySubmittedException("This attempt is already submitted.");
            }
            for (Response r : pending) {
                executeUpdate(c, UPSERT, ps -> bindResponse(ps, r));
            }
            if (scores != null) {
                writeScores(c, attemptId, scores);
            }
            return null;
        });
    }

    @Override
    public void saveScores(long attemptId, ScoreSheet scores) {
        inTransaction(c -> {
            writeScores(c, attemptId, scores);
            return null;
        });
    }

    private void writeScores(Connection c, long attemptId, ScoreSheet sheet) {
        for (Map.Entry<Long, ScoreSheet.Entry> e : sheet.byQuestion().entrySet()) {
            // A question the student never touched has no response row, and scores nothing.
            executeUpdate(c, SET_SCORE, ps -> {
                ps.setDouble(1, e.getValue().autoScore());
                if (e.getValue().finalScore() == null) {
                    ps.setNull(2, Types.DOUBLE);
                } else {
                    ps.setDouble(2, e.getValue().finalScore());
                }
                ps.setLong(3, attemptId);
                ps.setLong(4, e.getKey());
            });
        }
        executeUpdate(c, SET_TOTAL, ps -> {
            ps.setLong(1, attemptId);
            ps.setString(2, sheet.needsManual() ? null : AttemptStatus.GRADED.name());
            ps.setLong(3, attemptId);
        });
    }

    // ------------------------------------------------------------------

    private static String writeIds(List<Long> ids) {
        try {
            return JSON.writeValueAsString(ids);
        } catch (Exception e) {
            throw new QueryExecutionException("Could not serialise ids", e);
        }
    }

    private static List<Long> readIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            throw new QueryExecutionException("Unreadable id list: " + json, e);
        }
    }
}
