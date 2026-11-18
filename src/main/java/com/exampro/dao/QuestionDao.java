package com.exampro.dao;

import com.exampro.model.question.Question;

import java.util.List;

public interface QuestionDao extends GenericDao<Question, Long> {

    /** Active questions on one topic, options attached. */
    List<Question> findByTopic(long topicId);

    /** Active questions across every topic of a subject, options attached. */
    List<Question> findBySubject(long subjectId);

    /**
     * Flips is_active to false. This is what the UI calls, not
     * {@link GenericDao#delete}: a question that has already appeared on a
     * paper is referenced by attempt_questions and responses, and removing
     * the row would take the answers with it.
     */
    void deactivate(long id);
}
