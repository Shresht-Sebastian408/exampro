package com.exampro.dao;

import com.exampro.model.entity.Exam;

import java.util.List;

public interface ExamDao extends GenericDao<Exam, Long> {
    List<Exam> findBySubject(long subjectId);
}
