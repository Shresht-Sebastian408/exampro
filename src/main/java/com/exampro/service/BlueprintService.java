package com.exampro.service;

import com.exampro.dao.BlueprintDao;
import com.exampro.dao.QuestionDao;
import com.exampro.dao.impl.BlueprintDaoImpl;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.exception.InsufficientQuestionsException;
import com.exampro.exception.InvalidBlueprintException;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Saves blueprints, and refuses the ones the bank cannot satisfy.
 *
 * Capacity is checked here, at save time, not when a student starts the
 * exam: finding out at 9:00 on exam morning that a topic holds six hard
 * questions against a rule asking for ten is the failure this exists to
 * prevent.
 */
public class BlueprintService {

    private final BlueprintDao blueprintDao;
    private final QuestionDao questionDao;

    public BlueprintService() {
        this(new BlueprintDaoImpl(), new QuestionDaoImpl());
    }

    public BlueprintService(BlueprintDao blueprintDao, QuestionDao questionDao) {
        this.blueprintDao = blueprintDao;
        this.questionDao = questionDao;
    }

    public Optional<Blueprint> findByExam(long examId) {
        return blueprintDao.findByExam(examId);
    }

    /** Active questions that could satisfy a rule's slice. */
    public int available(long topicId, Difficulty difficulty, QuestionType type) {
        return countIn(questionDao.findByTopic(topicId), difficulty, type);
    }

    /** @return the blueprint id */
    public long save(Blueprint blueprint) {
        validate(blueprint);

        Optional<Blueprint> existing = blueprintDao.findByExam(blueprint.getExamId());
        if (existing.isPresent()) {
            blueprint.setId(existing.get().getId());
            blueprintDao.update(blueprint);
            return blueprint.getId();
        }
        return blueprintDao.insert(blueprint);
    }

    public void validate(Blueprint blueprint) {
        if (blueprint.getExamId() == null) {
            throw new InvalidBlueprintException("The blueprint belongs to no exam.");
        }
        if (blueprint.getRules().isEmpty()) {
            throw new InvalidBlueprintException("Add at least one rule.");
        }
        for (BlueprintRule r : blueprint.getRules()) {
            if (r.getCount() <= 0) {
                throw new InvalidBlueprintException("Every rule needs a count of at least 1.");
            }
        }

        // Rules may overlap (two rules on the same slice), so demand is summed
        // per slice before it is compared with supply.
        record Slice(long topicId, Difficulty difficulty, QuestionType type) {}
        Map<Slice, Integer> demand = new HashMap<>();
        Map<Slice, String> label = new HashMap<>();
        for (BlueprintRule r : blueprint.getRules()) {
            Slice s = new Slice(r.getTopicId(), r.getDifficulty(), r.getType());
            demand.merge(s, r.getCount(), Integer::sum);
            label.putIfAbsent(s, r.getDifficulty() + " " + r.getType() + " from " + r.getTopicName());
        }

        Map<Long, List<Question>> byTopic = new HashMap<>();
        List<String> shortfalls = new ArrayList<>();
        for (Map.Entry<Slice, Integer> e : demand.entrySet()) {
            Slice s = e.getKey();
            List<Question> pool = byTopic.computeIfAbsent(s.topicId(), questionDao::findByTopic);
            int have = countIn(pool, s.difficulty(), s.type());
            if (have < e.getValue()) {
                shortfalls.add(e.getValue() + " " + label.get(s) + " requested, " + have + " available");
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new InsufficientQuestionsException(
                "The bank cannot satisfy this blueprint:\n" + String.join("\n", shortfalls));
        }
    }

    private static int countIn(List<Question> questions, Difficulty d, QuestionType t) {
        return (int) questions.stream()
            .filter(q -> q.getDifficulty() == d && q.getType() == t).count();
    }
}
