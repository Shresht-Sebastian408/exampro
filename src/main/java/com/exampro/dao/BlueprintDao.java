package com.exampro.dao;

import com.exampro.model.entity.Blueprint;

import java.util.Optional;

public interface BlueprintDao extends GenericDao<Blueprint, Long> {
    /** The exam's blueprint with its rules, or empty if none is saved yet. */
    Optional<Blueprint> findByExam(long examId);
}
