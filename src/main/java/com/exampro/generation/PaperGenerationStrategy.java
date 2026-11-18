package com.exampro.generation;

import com.exampro.exception.PaperGenerationException;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.User;
import com.exampro.model.question.Question;

import java.util.List;
import java.util.Objects;
import java.util.Random;

public interface PaperGenerationStrategy {

    /**
     * @param student the examinee, or null for a preview
     * @return the paper in presentation order; never contains a question twice
     */
    List<Question> generate(Exam exam, User student, QuestionPool pool)
            throws PaperGenerationException;

    /** The value stored in exams.generation_strategy. */
    String getName();

    /**
     * Same exam and student always shuffle the same way, so regenerating a
     * paper (a crash, a retry) cannot hand a student different questions.
     */
    static Random seeded(Exam exam, User student) {
        return new Random(Objects.hash(exam.getId(), student == null ? 0L : student.getId()));
    }
}
