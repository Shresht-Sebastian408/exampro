package com.exampro.service;

import com.exampro.dao.BlueprintDao;
import com.exampro.dao.QuestionDao;
import com.exampro.dao.impl.BlueprintDaoImpl;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.exception.PaperGenerationException;
import com.exampro.generation.AdaptiveDifficultyStrategy;
import com.exampro.generation.BlueprintStrategy;
import com.exampro.generation.PaperGenerationStrategy;
import com.exampro.generation.QuestionPool;
import com.exampro.generation.RandomPoolStrategy;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.User;
import com.exampro.model.question.Question;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Looks up the exam's strategy by name and runs it against the exam's pool. */
public class PaperGenerationService {

    private static final Map<String, PaperGenerationStrategy> STRATEGIES = new LinkedHashMap<>();

    static {
        for (PaperGenerationStrategy s : List.of(new BlueprintStrategy(), new RandomPoolStrategy(),
                                                 new AdaptiveDifficultyStrategy())) {
            STRATEGIES.put(s.getName(), s);
        }
    }

    /** For the exam builder's dropdown. */
    public static List<String> strategyNames() {
        return List.copyOf(STRATEGIES.keySet());
    }

    private final BlueprintDao blueprintDao;
    private final QuestionDao questionDao;

    public PaperGenerationService() {
        this(new BlueprintDaoImpl(), new QuestionDaoImpl());
    }

    public PaperGenerationService(BlueprintDao blueprintDao, QuestionDao questionDao) {
        this.blueprintDao = blueprintDao;
        this.questionDao = questionDao;
    }

    /** @param student null for a preview */
    public List<Question> generate(Exam exam, User student) {
        PaperGenerationStrategy strategy = STRATEGIES.get(exam.getGenerationStrategy());
        if (strategy == null) {
            throw new PaperGenerationException("Unknown strategy '" + exam.getGenerationStrategy() + "'.");
        }
        Blueprint blueprint = blueprintDao.findByExam(exam.getId()).orElseThrow(
            () -> new PaperGenerationException("This exam has no blueprint yet."));
        return strategy.generate(exam, student,
                                 new QuestionPool(questionDao.findBySubject(exam.getSubjectId()), blueprint));
    }
}
