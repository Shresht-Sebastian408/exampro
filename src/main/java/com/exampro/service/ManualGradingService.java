package com.exampro.service;

import com.exampro.dao.GradingDao;
import com.exampro.dao.impl.GradingDaoImpl;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.Rubric;
import com.exampro.model.question.Question;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The human half of grading: rubrics for short answers, and the marks a
 * person gives against them. With no rubric, a single mark is entered
 * instead, so grading never waits on rubric authoring.
 */
public class ManualGradingService {

    private static final double EPS = 1e-9;

    private final GradingDao dao;

    public ManualGradingService() {
        this(new GradingDaoImpl());
    }

    public ManualGradingService(GradingDao dao) {
        this.dao = dao;
    }

    public List<GradingDao.PendingItem> pending() {
        return dao.findPending();
    }

    public Optional<Rubric> rubricFor(Question q) {
        return dao.findByQuestion(q.getId());
    }

    /** Creates or replaces the question's rubric. Criteria must add up to the question's marks. */
    public void saveRubric(Question q, List<Rubric.Criterion> criteria) {
        if (criteria.isEmpty()) {
            throw new ValidationException("Add at least one criterion.");
        }
        for (Rubric.Criterion k : criteria) {
            if (k.description() == null || k.description().isBlank() || k.maxMarks() <= 0) {
                throw new ValidationException("Every criterion needs a description and marks above zero.");
            }
        }
        double sum = criteria.stream().mapToDouble(Rubric.Criterion::maxMarks).sum();
        if (Math.abs(sum - q.getMarks()) > EPS) {
            throw new ValidationException("Criteria add up to " + sum
                + " but the question is worth " + q.getMarks() + ".");
        }

        Optional<Rubric> existing = dao.findByQuestion(q.getId());
        Rubric rubric = existing.orElseGet(Rubric::new);
        rubric.setQuestionId(q.getId());
        rubric.setName("Rubric for question " + q.getId());
        rubric.getCriteria().clear();
        rubric.getCriteria().addAll(criteria);
        if (existing.isPresent()) {
            dao.update(rubric);
        } else {
            dao.insert(rubric);
        }
    }

    /**
     * @param marks criterion id to marks if the question has a rubric; otherwise
     *              a single entry with key {@code null}
     * @return the total awarded
     */
    public double grade(long responseId, long gradedBy, Question q,
                        Map<Long, Double> marks, String comment) {
        Optional<Rubric> rubric = dao.findByQuestion(q.getId());
        Map<Long, Double> clean = new LinkedHashMap<>();

        if (rubric.isPresent()) {
            for (Rubric.Criterion k : rubric.get().getCriteria()) {
                clean.put(k.id(), checked(marks.get(k.id()), k.maxMarks(), k.description()));
            }
        } else {
            clean.put(null, checked(marks.get(null), q.getMarks(), "the answer"));
        }
        return dao.recordManualGrade(responseId, gradedBy, clean, comment);
    }

    private static double checked(Double mark, double max, String what) {
        if (mark == null) {
            throw new ValidationException("Enter marks for " + what + ".");
        }
        if (mark < 0 || mark > max + EPS) {
            throw new ValidationException("Marks for " + what + " must be between 0 and " + max + ".");
        }
        return mark;
    }
}
