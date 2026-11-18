package com.exampro.dao;

import com.exampro.model.entity.Subject;

import java.util.List;
import java.util.Optional;

public interface SubjectDao extends GenericDao<Subject, Long> {

    Optional<Subject> findByCode(String code);

    /**
     * Subjects with their topic and question counts attached. This is the
     * query behind the subjects table view.
     */
    List<Subject> findAllWithCounts();
}
