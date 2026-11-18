package com.exampro.model.entity;

import java.util.ArrayList;
import java.util.List;

public class Blueprint {

    private Long id;
    private Long examId;
    private String name;
    private final List<BlueprintRule> rules = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<BlueprintRule> getRules() { return rules; }

    public int totalCount() {
        return rules.stream().mapToInt(BlueprintRule::getCount).sum();
    }
}
