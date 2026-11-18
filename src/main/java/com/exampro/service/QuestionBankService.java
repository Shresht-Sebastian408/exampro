package com.exampro.service;

import com.exampro.dao.QuestionDao;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.exception.ExportException;
import com.exampro.exception.InvalidQuestionException;
import com.exampro.export.CsvQuestionExporter;
import com.exampro.export.JsonBankExporter;
import com.exampro.export.QuestionRecord;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.MCQQuestion;
import com.exampro.model.question.MSQQuestion;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.model.question.TrueFalseQuestion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validation gate in front of {@link QuestionDao}.
 *
 * Every write to the question bank goes through {@link #save}, so an invalid
 * question cannot reach the database from the editor, from a seeder, or from
 * the CSV import that arrives in week four. The rules are per type because
 * that is where the real mistakes are: an MCQ with two correct options and a
 * numeric question with no answer both look fine in a form and are unusable
 * on a paper.
 */
public class QuestionBankService {

    private static final int MIN_CHOICE_OPTIONS = 2;
    private static final int MAX_TAG_LENGTH = 50;

    private final QuestionDao questionDao;

    public QuestionBankService() {
        this(new QuestionDaoImpl());
    }

    public QuestionBankService(QuestionDao questionDao) {
        this.questionDao = questionDao;
    }

    public List<Question> listBySubject(long subjectId) {
        return questionDao.findBySubject(subjectId);
    }

    public List<Question> listByTopic(long topicId) {
        return questionDao.findByTopic(topicId);
    }

    /** @return the question id, generated on insert or echoed back on update. */
    public long save(Question question) {
        validate(question);
        if (question.getId() == null) {
            return questionDao.insert(question);
        }
        questionDao.update(question);
        return question.getId();
    }

    /** Outcome of a bulk import: rows saved, and one message per rejected row. */
    public record ImportResult(int imported, List<String> errors) {}

    /**
     * Bulk import from .csv or .json. Every row goes through {@link #save}, so
     * the same rules apply as in the editor; a bad row is reported and
     * skipped, it does not abort the rest. Row numbers are 1-based data rows.
     *
     * @param topicIds topic name to id for the target subject
     */
    public ImportResult importFile(Path file, Map<String, Long> topicIds) {
        String name = file.getFileName().toString().toLowerCase();
        List<QuestionRecord> records;
        if (name.endsWith(".json")) {
            records = JsonBankExporter.read(file);
        } else if (name.endsWith(".csv")) {
            records = CsvQuestionExporter.read(file);
        } else {
            throw new ExportException("Import expects a .csv or .json file.");
        }

        int imported = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            try {
                save(records.get(i).toQuestion(topicIds));
                imported++;
            } catch (ExamProException e) {
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new ImportResult(imported, errors);
    }

    /** Soft delete. See {@link QuestionDao#deactivate}. */
    public void deactivate(long questionId) {
        questionDao.deactivate(questionId);
    }

    public void validate(Question question) {
        if (question == null) {
            throw new InvalidQuestionException("No question to save.");
        }
        if (question.getTopicId() == null) {
            throw new InvalidQuestionException("Pick a topic for the question.");
        }
        if (question.getStem() == null || question.getStem().isBlank()) {
            throw new InvalidQuestionException("The question text cannot be empty.");
        }
        if (question.getMarks() <= 0) {
            throw new InvalidQuestionException("Marks must be greater than zero.");
        }
        if (question.getNegativeMarks() < 0) {
            throw new InvalidQuestionException(
                "Negative marks are entered as a positive number, or zero for none.");
        }

        normaliseTags(question);

        switch (question.getType()) {
            case MCQ -> {
                requireOptions(question);
                if (question.correctOptions().size() != 1) {
                    throw new InvalidQuestionException(
                        "An MCQ needs exactly one correct option. Use MSQ for several.");
                }
            }
            case MSQ -> {
                requireOptions(question);
                if (question.correctOptions().isEmpty()) {
                    throw new InvalidQuestionException("Mark at least one option correct.");
                }
                if (question.correctOptions().size() == question.getOptions().size()) {
                    throw new InvalidQuestionException(
                        "Every option is marked correct, so the question cannot discriminate.");
                }
            }
            case TRUE_FALSE -> {
                if (question.getOptions().size() != 2
                    || question.correctOptions().size() != 1) {
                    throw new InvalidQuestionException(
                        "A true or false question needs both options with exactly one correct. "
                        + "Use TrueFalseQuestion.setAnswer to build them.");
                }
            }
            case NUMERIC -> {
                NumericQuestion n = (NumericQuestion) question;
                if (n.getAnswer() == null) {
                    throw new InvalidQuestionException("Enter the numeric answer.");
                }
                if (n.getTolerance() < 0) {
                    throw new InvalidQuestionException("Tolerance cannot be negative.");
                }
            }
            case SHORT_ANSWER -> {
                ShortAnswerQuestion s = (ShortAnswerQuestion) question;
                if (s.getKeywords().isEmpty()) {
                    throw new InvalidQuestionException(
                        "Add at least one expected keyword to guide manual grading.");
                }
            }
        }
    }

    /** Trim, lower-case, drop blanks and duplicates; the tags column is 50 chars. */
    private void normaliseTags(Question question) {
        Set<String> clean = new LinkedHashSet<>();
        for (String tag : question.getTags()) {
            String t = tag == null ? "" : tag.trim().toLowerCase();
            if (t.length() > MAX_TAG_LENGTH) {
                throw new InvalidQuestionException(
                    "Tag '" + t + "' is longer than " + MAX_TAG_LENGTH + " characters.");
            }
            if (!t.isEmpty()) {
                clean.add(t);
            }
        }
        question.getTags().clear();
        question.getTags().addAll(clean);
    }

    private void requireOptions(Question question) {
        if (question.getOptions().size() < MIN_CHOICE_OPTIONS) {
            throw new InvalidQuestionException(
                "Add at least " + MIN_CHOICE_OPTIONS + " options.");
        }
        for (QuestionOption option : question.getOptions()) {
            if (option.getText() == null || option.getText().isBlank()) {
                throw new InvalidQuestionException("An option is empty. Fill it in or remove it.");
            }
        }
    }

    /** Factory so the editor and the seeder build the same shapes. */
    public static Question blank(QuestionType type) {
        return switch (type) {
            case MCQ -> {
                MCQQuestion q = new MCQQuestion();
                q.getOptions().add(new QuestionOption("", true, 0));
                q.getOptions().add(new QuestionOption("", false, 1));
                yield q;
            }
            case MSQ -> {
                MSQQuestion q = new MSQQuestion();
                q.getOptions().add(new QuestionOption("", true, 0));
                q.getOptions().add(new QuestionOption("", false, 1));
                yield q;
            }
            case TRUE_FALSE -> {
                TrueFalseQuestion q = new TrueFalseQuestion();
                q.setAnswer(true);
                yield q;
            }
            case NUMERIC -> new NumericQuestion();
            case SHORT_ANSWER -> new ShortAnswerQuestion();
        };
    }
}
