package com.exampro.generation;

import com.exampro.exception.PaperGenerationException;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.question.Question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Starts at MEDIUM and moves one step up after a correct answer, one step
 * down after a wrong one.
 *
 * {@link #generate} fixes the paper's size and its first question (MEDIUM)
 * and returns a provisional order. The live adjustment is {@link #next},
 * which exam delivery (week six) calls after each answer to choose from the
 * questions not yet shown. Only the size is frozen up front; the order is
 * what adapts.
 */
public class AdaptiveDifficultyStrategy implements PaperGenerationStrategy {

    public static final String NAME = "ADAPTIVE";

    @Override
    public List<Question> generate(Exam exam, User student, QuestionPool pool) {
        int size = pool.blueprint().totalCount();
        if (size <= 0 || pool.questions().size() < size) {
            throw new PaperGenerationException("Need " + size + " questions but the subject has "
                + pool.questions().size() + ".");
        }
        Random rnd = PaperGenerationStrategy.seeded(exam, student);
        List<Question> shuffled = new ArrayList<>(pool.questions());
        Collections.shuffle(shuffled, rnd);
        List<Question> paper = new ArrayList<>(shuffled.subList(0, size));
        // MEDIUM first: sort is stable, so the shuffle order survives within each band.
        paper.sort(Comparator.comparingInt(q -> Math.abs(q.getDifficulty().ordinal() - 1)));
        return paper;
    }

    /** The difficulty to aim for after an answer, clamped to EASY..HARD. */
    public static Difficulty step(Difficulty current, boolean lastCorrect) {
        int i = current.ordinal() + (lastCorrect ? 1 : -1);
        return Difficulty.values()[Math.max(0, Math.min(Difficulty.values().length - 1, i))];
    }

    /**
     * Picks the next question from {@code remaining}: the target difficulty
     * if any is left, otherwise the closest one.
     */
    public static Question next(List<Question> remaining, Difficulty target, Random rnd) {
        if (remaining.isEmpty()) {
            throw new PaperGenerationException("No questions left to choose from.");
        }
        int best = remaining.stream()
            .mapToInt(q -> Math.abs(q.getDifficulty().ordinal() - target.ordinal())).min().getAsInt();
        List<Question> closest = remaining.stream()
            .filter(q -> Math.abs(q.getDifficulty().ordinal() - target.ordinal()) == best).toList();
        return closest.get(rnd.nextInt(closest.size()));
    }

    @Override
    public String getName() {
        return NAME;
    }
}
