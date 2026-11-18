package com.exampro.export;

import com.exampro.exception.InvalidQuestionException;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.model.question.TrueFalseQuestion;
import com.exampro.service.QuestionBankService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The flat, file-shaped form of a question. CSV rows and JSON objects both
 * map onto this one class, so import and export share a single conversion to
 * and from {@link Question}.
 *
 * Topic is the topic's name, not its id: ids differ between databases, names
 * are what a person typing a spreadsheet knows. {@code correct} holds 1-based
 * option positions; true/false uses options [True, False] like the schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionRecord {

    public String topic;
    public String type;
    public String difficulty;
    public Double marks;
    public Double negativeMarks;
    public String stem;
    public List<String> options = new ArrayList<>();
    public List<Integer> correct = new ArrayList<>();
    public Double numericAnswer;
    public Double tolerance;
    public List<String> keywords = new ArrayList<>();
    public List<String> tags = new ArrayList<>();
    public String explanation;

    public static QuestionRecord from(Question q) {
        QuestionRecord r = new QuestionRecord();
        r.topic = q.getTopicName();
        r.type = q.getType().name();
        r.difficulty = q.getDifficulty().name();
        r.marks = q.getMarks();
        r.negativeMarks = q.getNegativeMarks();
        r.stem = q.getStem();
        r.explanation = q.getExplanation();
        r.tags = new ArrayList<>(q.getTags());
        List<QuestionOption> opts = q.getOptions();
        for (int i = 0; i < opts.size(); i++) {
            r.options.add(opts.get(i).getText());
            if (opts.get(i).isCorrect()) {
                r.correct.add(i + 1);
            }
        }
        if (q instanceof NumericQuestion n) {
            r.numericAnswer = n.getAnswer();
            r.tolerance = n.getTolerance();
        }
        if (q instanceof ShortAnswerQuestion s) {
            r.keywords = new ArrayList<>(s.getKeywords());
        }
        return r;
    }

    /**
     * @param topicIds topic name (lower-case) to id
     * @throws InvalidQuestionException for anything unreadable; content rules
     *         proper (one correct MCQ option, etc.) are left to the service
     */
    public Question toQuestion(Map<String, Long> topicIds) {
        QuestionType t;
        Difficulty d;
        try {
            t = QuestionType.valueOf(clean(type).toUpperCase());
            d = difficulty == null || difficulty.isBlank()
                ? Difficulty.MEDIUM : Difficulty.valueOf(difficulty.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidQuestionException(
                "Unknown type '" + type + "' or difficulty '" + difficulty + "'.");
        }
        Long topicId = topicIds.get(clean(topic).toLowerCase());
        if (topicId == null) {
            throw new InvalidQuestionException("Unknown topic '" + topic + "'.");
        }

        Question q = QuestionBankService.blank(t);
        q.setTopicId(topicId);
        q.setDifficulty(d);
        q.setMarks(marks == null ? 1 : marks);
        q.setNegativeMarks(negativeMarks == null ? 0 : negativeMarks);
        q.setStem(stem);
        q.setExplanation(explanation == null || explanation.isBlank() ? null : explanation);
        q.getTags().addAll(tags);

        switch (t) {
            case MCQ, MSQ -> {
                q.getOptions().clear();
                for (int i = 0; i < options.size(); i++) {
                    q.getOptions().add(new QuestionOption(options.get(i), correct.contains(i + 1), i));
                }
            }
            case TRUE_FALSE -> ((TrueFalseQuestion) q).setAnswer(correct.contains(1));
            case NUMERIC -> {
                NumericQuestion n = (NumericQuestion) q;
                n.setAnswer(numericAnswer);
                n.setTolerance(tolerance == null ? 0 : tolerance);
            }
            case SHORT_ANSWER -> ((ShortAnswerQuestion) q).getKeywords().addAll(keywords);
        }
        return q;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
