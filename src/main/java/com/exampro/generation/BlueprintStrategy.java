package com.exampro.generation;

import com.exampro.exception.PaperGenerationException;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.User;
import com.exampro.model.question.Question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Follows the blueprint exactly: each rule draws its own count at random from its own slice. */
public class BlueprintStrategy implements PaperGenerationStrategy {

    public static final String NAME = "BLUEPRINT";

    @Override
    public List<Question> generate(Exam exam, User student, QuestionPool pool) {
        Random rnd = PaperGenerationStrategy.seeded(exam, student);
        Set<Long> used = new HashSet<>();
        List<Question> paper = new ArrayList<>();

        for (BlueprintRule rule : pool.blueprint().getRules()) {
            List<Question> candidates = new ArrayList<>(pool.questions().stream()
                .filter(q -> q.getTopicId() == rule.getTopicId()
                             && q.getDifficulty() == rule.getDifficulty()
                             && q.getType() == rule.getType()
                             && !used.contains(q.getId()))
                .toList());
            if (candidates.size() < rule.getCount()) {
                throw new PaperGenerationException("Rule '" + rule.getCount() + " "
                    + rule.getDifficulty() + " " + rule.getType() + " from "
                    + rule.getTopicName() + "' found only " + candidates.size() + ".");
            }
            Collections.shuffle(candidates, rnd);
            for (Question q : candidates.subList(0, rule.getCount())) {
                used.add(q.getId());
                paper.add(q);
            }
        }
        if (exam.isShuffleQuestions()) {
            Collections.shuffle(paper, rnd);
        }
        return paper;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
