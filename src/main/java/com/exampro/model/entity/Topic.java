package com.exampro.model.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in a subject's topic tree. {@code parentId} is null for a root topic.
 * {@code children} is populated in memory by TopicDao.findTree, not by the schema.
 */
public class Topic {

    private Long id;
    private Long subjectId;
    private Long parentId;
    private String name;
    private int displayOrder;
    private boolean active = true;

    private final List<Topic> children = new ArrayList<>();

    public Topic() {}

    public Topic(Long subjectId, Long parentId, String name, int displayOrder) {
        this.subjectId = subjectId;
        this.parentId = parentId;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public List<Topic> getChildren() { return children; }

    public boolean isRoot() { return parentId == null; }

    @Override
    public String toString() {
        return name;
    }
}
