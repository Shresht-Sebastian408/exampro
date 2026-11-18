package com.exampro.dao;

import com.exampro.model.entity.Topic;

import java.util.List;

public interface TopicDao extends GenericDao<Topic, Long> {

    List<Topic> findBySubject(long subjectId);

    /**
     * The subject's topics as a tree: roots only, each with its children
     * wired up. Built from a single flat query, not a recursive one.
     */
    List<Topic> findTree(long subjectId);
}
