package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.QuestionDao;
import com.exampro.dao.SubjectDao;
import com.exampro.dao.TopicDao;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.exception.InvalidQuestionException;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.MCQQuestion;
import com.exampro.model.question.MSQQuestion;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.model.question.TrueFalseQuestion;
import com.exampro.service.QuestionBankService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week three: the question hierarchy, its DAO, and the validation gate. */
class QuestionBankTest {

    static {
        System.setProperty("exampro.jdbcUrl",
                           "jdbc:h2:mem:exampro_bank_test;DB_CLOSE_DELAY=-1");
    }

    private static final QuestionDao questions = new QuestionDaoImpl();
    private static final QuestionBankService service = new QuestionBankService(questions);
    private static long topicId;
    private static long otherTopicId;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();

        SubjectDao subjects = new SubjectDaoImpl();
        TopicDao topics = new TopicDaoImpl();
        long subjectId = subjects.findByCode("CS201").orElseThrow().getId();
        List<Topic> leaves = topics.findBySubject(subjectId).stream()
                                   .filter(t -> !t.isRoot())
                                   .toList();
        topicId = leaves.get(0).getId();
        otherTopicId = leaves.get(1).getId();
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private static Question mcq(String stem, long topic) {
        MCQQuestion q = new MCQQuestion();
        q.setTopicId(topic);
        q.setStem(stem);
        q.setDifficulty(Difficulty.EASY);
        q.setMarks(2);
        q.getOptions().addAll(List.of(
            new QuestionOption("Right", true, 0),
            new QuestionOption("Wrong", false, 1),
            new QuestionOption("Also wrong", false, 2)));
        return q;
    }

    @Test
    void seedingProducesEveryTypeAndEveryDifficulty() {
        List<Question> all = new SubjectDaoImpl().findAll().stream()
            .flatMap(s -> questions.findBySubject(s.getId()).stream())
            .toList();

        assertTrue(all.size() >= 40 && all.size() <= 60,
                   "plan asks for 40 to 60 seeded questions, got " + all.size());
        for (QuestionType type : QuestionType.values()) {
            assertTrue(all.stream().anyMatch(q -> q.getType() == type), "no seeded " + type);
        }
        for (Difficulty difficulty : Difficulty.values()) {
            assertTrue(all.stream().anyMatch(q -> q.getDifficulty() == difficulty),
                       "no seeded " + difficulty);
        }
    }

    @Test
    void theTypeColumnDecidesTheSubclassOnReload() {
        Map<QuestionType, Class<?>> expected = Map.of(
            QuestionType.MCQ, MCQQuestion.class,
            QuestionType.MSQ, MSQQuestion.class,
            QuestionType.TRUE_FALSE, TrueFalseQuestion.class,
            QuestionType.NUMERIC, NumericQuestion.class,
            QuestionType.SHORT_ANSWER, ShortAnswerQuestion.class);

        List<Question> seeded = questions.findBySubject(
            new SubjectDaoImpl().findByCode("LAW101").orElseThrow().getId());

        for (Question q : seeded) {
            assertInstanceOf(expected.get(q.getType()), q,
                             q.getType() + " mapped to the wrong class");
        }
    }

    @Test
    void optionsSurviveTheRoundTripInOrder() {
        long id = service.save(mcq("Which traversal visits the root first?", topicId));
        Question reloaded = questions.findById(id).orElseThrow();

        assertEquals(3, reloaded.getOptions().size());
        assertEquals("Right", reloaded.getOptions().get(0).getText());
        assertEquals("Also wrong", reloaded.getOptions().get(2).getText());
        assertEquals(1, reloaded.correctOptions().size());
        assertEquals("Right", reloaded.getAnswerSummary());
        // findById joins topics, so the display name comes back with the row.
        assertEquals("Arrays", reloaded.getTopicName());
    }

    @Test
    void numericAndKeywordFieldsSurviveTheRoundTrip() {
        NumericQuestion numeric = new NumericQuestion();
        numeric.setTopicId(topicId);
        numeric.setStem("Height of a balanced tree of 15 nodes?");
        numeric.setAnswer(3.5);
        numeric.setTolerance(0.25);
        long numericId = service.save(numeric);

        NumericQuestion reloadedNumeric =
            assertInstanceOf(NumericQuestion.class, questions.findById(numericId).orElseThrow());
        assertEquals(3.5, reloadedNumeric.getAnswer());
        assertEquals(0.25, reloadedNumeric.getTolerance());
        assertTrue(reloadedNumeric.accepts(3.3));
        assertFalse(reloadedNumeric.accepts(3.0));

        ShortAnswerQuestion text = new ShortAnswerQuestion();
        text.setTopicId(topicId);
        text.setStem("Explain amortised analysis.");
        // The comma is the point: a delimited column would split this keyword.
        text.getKeywords().addAll(List.of("amortised, not average", "worst case"));
        long textId = service.save(text);

        ShortAnswerQuestion reloadedText =
            assertInstanceOf(ShortAnswerQuestion.class, questions.findById(textId).orElseThrow());
        assertEquals(List.of("amortised, not average", "worst case"),
                     List.copyOf(reloadedText.getKeywords()));
        assertEquals(1, reloadedText.keywordHits("the worst case bound holds"));
    }

    @Test
    void updateReplacesOptionsAndKeepsOneRow() {
        long id = service.save(mcq("Original stem", topicId));
        Question loaded = questions.findById(id).orElseThrow();
        long before = questions.count();

        loaded.setStem("Edited stem");
        loaded.setTopicId(otherTopicId);
        loaded.getOptions().remove(2);
        service.save(loaded);

        Question reloaded = questions.findById(id).orElseThrow();
        assertEquals("Edited stem", reloaded.getStem());
        assertEquals(otherTopicId, reloaded.getTopicId());
        assertEquals(2, reloaded.getOptions().size());
        assertEquals(before, questions.count(), "update must not insert a second row");
    }

    @Test
    void validationRejectsTheMistakesAFormLetsThrough() {
        Question twoCorrect = mcq("Two correct options", topicId);
        twoCorrect.getOptions().get(1).setCorrect(true);
        assertThrows(InvalidQuestionException.class, () -> service.save(twoCorrect));

        Question blankStem = mcq("   ", topicId);
        assertThrows(InvalidQuestionException.class, () -> service.save(blankStem));

        Question zeroMarks = mcq("Zero marks", topicId);
        zeroMarks.setMarks(0);
        assertThrows(InvalidQuestionException.class, () -> service.save(zeroMarks));

        NumericQuestion noAnswer = new NumericQuestion();
        noAnswer.setTopicId(topicId);
        noAnswer.setStem("Numeric with no answer key");
        assertThrows(InvalidQuestionException.class, () -> service.save(noAnswer));

        ShortAnswerQuestion noKeywords = new ShortAnswerQuestion();
        noKeywords.setTopicId(topicId);
        noKeywords.setStem("Short answer with no keywords");
        assertThrows(InvalidQuestionException.class, () -> service.save(noKeywords));

        MSQQuestion allCorrect = new MSQQuestion();
        allCorrect.setTopicId(topicId);
        allCorrect.setStem("Everything is correct");
        allCorrect.getOptions().addAll(List.of(
            new QuestionOption("a", true, 0), new QuestionOption("b", true, 1)));
        assertThrows(InvalidQuestionException.class, () -> service.save(allCorrect));
    }

    @Test
    void nothingIsWrittenWhenValidationFails() {
        long before = questions.count();
        assertThrows(InvalidQuestionException.class,
                     () -> service.save(mcq("", topicId)));
        assertEquals(before, questions.count());
    }

    @Test
    void msqPartialCreditRefusesToRewardTickingEverything() {
        MSQQuestion msq = new MSQQuestion();
        msq.setTopicId(topicId);
        msq.setStem("Select the linear structures.");
        msq.getOptions().addAll(List.of(
            new QuestionOption("Array", true, 0),
            new QuestionOption("Linked list", true, 1),
            new QuestionOption("Binary heap", false, 2)));
        long id = service.save(msq);

        MSQQuestion reloaded =
            assertInstanceOf(MSQQuestion.class, questions.findById(id).orElseThrow());
        List<Long> correctIds = reloaded.correctOptions().stream()
                                        .map(QuestionOption::getId).toList();
        Long wrongId = reloaded.getOptions().get(2).getId();

        assertEquals(1.0, reloaded.creditFraction(correctIds));
        assertEquals(0.5, reloaded.creditFraction(List.of(correctIds.get(0))));
        assertEquals(0.0, reloaded.creditFraction(List.of(correctIds.get(0), wrongId)));
        assertEquals(0.0, reloaded.creditFraction(List.of()));
    }

    @Test
    void isAttemptedIsAnsweredByTheSubclass() {
        Response blank = new Response(1L);
        Response chosen = new Response(1L);
        chosen.getSelectedOptionIds().add(7L);
        Response typed = new Response(1L);
        typed.setTextAnswer("some prose");
        Response numeric = new Response(1L);
        numeric.setNumericAnswer(0.0);

        assertFalse(new MCQQuestion().isAttempted(blank));
        assertTrue(new MCQQuestion().isAttempted(chosen));
        assertFalse(new ShortAnswerQuestion().isAttempted(blank));
        assertTrue(new ShortAnswerQuestion().isAttempted(typed));
        // Zero is an answer. A null check that used the value would lose it.
        assertTrue(new NumericQuestion().isAttempted(numeric));
        assertFalse(new NumericQuestion().isAttempted(typed));
    }

    @Test
    void retiringHidesAQuestionWithoutDeletingIt() {
        long id = service.save(mcq("Question to retire", topicId));
        service.deactivate(id);

        assertTrue(questions.findByTopic(topicId).stream()
                            .noneMatch(q -> q.getId().equals(id)),
                   "retired question must not appear in the active list");
        Question stillThere = questions.findById(id).orElseThrow();
        assertFalse(stillThere.isActive());
    }

    @Test
    void trueFalseStoresBothOptionsWithOneCorrect() {
        TrueFalseQuestion tf = new TrueFalseQuestion();
        tf.setTopicId(topicId);
        tf.setStem("A stack is LIFO.");
        tf.setAnswer(true);
        long id = service.save(tf);

        TrueFalseQuestion reloaded =
            assertInstanceOf(TrueFalseQuestion.class, questions.findById(id).orElseThrow());
        assertEquals(2, reloaded.getOptions().size());
        assertEquals(1, reloaded.correctOptions().size());
        assertTrue(reloaded.isAnswerTrue());
        assertEquals("True", reloaded.getAnswerSummary());

        // One option removed, so neither the schema nor the grader can rely
        // on the pair any more: validation has to catch it.
        reloaded.getOptions().remove(1);
        assertThrows(InvalidQuestionException.class, () -> service.save(reloaded));
    }

    @Test
    void blankFactoryProducesTheMatchingSubclass() {
        for (QuestionType type : QuestionType.values()) {
            assertSame(type, QuestionBankService.blank(type).getType());
        }
    }
}
