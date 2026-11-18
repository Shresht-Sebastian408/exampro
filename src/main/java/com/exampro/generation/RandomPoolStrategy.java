package com.exampro.generation;

import com.exampro.exception.PaperGenerationException;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.User;
import com.exampro.model.question.Question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ignores the blueprint's slices and draws as many questions as the
 * blueprint totals from the whole subject. For quick quizzes where the
 * topic and difficulty mix does not matter.
 */
public class RandomPoolStrategy implements PaperGenerationStrategy {

    public static final String NAME = "RANDOM_POOL";

    @Override
    public List<Question> generate(Exam exam, User student, QuestionPool pool) {
        int size = pool.blueprint().totalCount();
        if (size <= 0) {
            throw new PaperGenerationException("The blueprint asks for no questions.");
        }
        if (pool.questions().size() < size) {
            throw new PaperGenerationException("Need " + size + " questions but the subject has "
                + pool.questions().size() + ".");
        }
        List<Question> shuffled = new ArrayList<>(pool.questions());
        Collections.shuffle(shuffled, PaperGenerationStrategy.seeded(exam, student));
        return new ArrayList<>(shuffled.subList(0, size));
    }

    @Override
    public String getName() {
        return NAME;
    }
}
