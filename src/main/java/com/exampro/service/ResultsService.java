package com.exampro.service;

import com.exampro.dao.AttemptDao;
import com.exampro.dao.UserDao;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.export.ResultRow;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.User;
import com.exampro.model.question.Question;

import java.util.Comparator;
import java.util.List;

/** Turns an exam's attempts into result-sheet rows, best score first. */
public class ResultsService {

    private final AttemptDao attempts;
    private final UserDao users;

    public ResultsService() {
        this(new AttemptDaoImpl(), new UserDaoImpl());
    }

    public ResultsService(AttemptDao attempts, UserDao users) {
        this.attempts = attempts;
        this.users = users;
    }

    public List<ResultRow> rows(long examId) {
        return attempts.findByExam(examId).stream()
            .filter(Attempt::isFinished)
            .map(this::toRow)
            .sorted(Comparator.comparing((ResultRow r) -> r.score() == null ? -1 : r.score()).reversed()
                              .thenComparing(ResultRow::student))
            .toList();
    }

    private ResultRow toRow(Attempt a) {
        String name = users.findById(a.getStudentId()).map(User::getFullName).orElse("Student " + a.getStudentId());
        // The paper is frozen per attempt, so the maximum is what that student's paper was worth.
        double max = attempts.loadPaper(a.getId()).stream().mapToDouble(Question::getMarks).sum();
        return new ResultRow(name, a.getStatus().name(), a.getTotalScore(), max, a.getIntegrityScore());
    }
}
