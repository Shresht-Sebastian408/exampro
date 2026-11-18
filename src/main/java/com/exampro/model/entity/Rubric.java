package com.exampro.model.entity;

import java.util.ArrayList;
import java.util.List;

/** A marking scheme for one subjective question: criteria whose maxima add up to the question's marks. */
public class Rubric {

    /** @param id null until stored */
    public record Criterion(Long id, String description, double maxMarks) {}

    private Long id;
    private Long questionId;
    private String name;
    private final List<Criterion> criteria = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Criterion> getCriteria() { return criteria; }

    public double totalMarks() {
        return criteria.stream().mapToDouble(Criterion::maxMarks).sum();
    }
}
