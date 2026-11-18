package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.exception.InsufficientQuestionsException;
import com.exampro.exception.InvalidBlueprintException;
import com.exampro.generation.AdaptiveDifficultyStrategy;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;
import com.exampro.service.BlueprintService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.PaperGenerationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week five: blueprint capacity check and the three generation strategies. */
class GenerationTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_gen_test;DB_CLOSE_DELAY=-1");
    }

    private static final BlueprintService blueprints = new BlueprintService();
    private static final PaperGenerationService generator = new PaperGenerationService();
    private static long subjectId;
    private static Topic topic;
    private static int mediumMcq;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        List<Topic> leaves = new TopicDaoImpl().findBySubject(subjectId).stream()
                                               .filter(t -> !t.isRoot()).toList();
        topic = leaves.get(0);
        // The seeder spreads types thinly, so add a known supply: 5 MEDIUM MCQs.
        QuestionBankService bank = new QuestionBankService();
        for (int i = 0; i < 5; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setDifficulty(Difficulty.MEDIUM);
            q.setStem("generation test " + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
        mediumMcq = blueprints.available(topic.getId(), Difficulty.MEDIUM, QuestionType.MCQ);
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private Exam newExam(String strategy) {
        Exam e = new Exam();
        e.setSubjectId(subjectId);
        e.setTitle("t-" + System.nanoTime());
        e.setGenerationStrategy(strategy);
        new ExamDaoImpl().insert(e);
        return e;
    }

    private Blueprint blueprint(Exam e, int count) {
        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(),
                                           Difficulty.MEDIUM, QuestionType.MCQ, count));
        return b;
    }

    @Test
    void saveRefusesWhatTheBankCannotSupply() {
        Exam e = newExam("BLUEPRINT");
        var ex = assertThrows(InsufficientQuestionsException.class,
                              () -> blueprints.save(blueprint(e, mediumMcq + 1)));
        assertTrue(ex.getMessage().contains(mediumMcq + " available"));
        assertTrue(blueprints.findByExam(e.getId()).isEmpty(), "nothing is written on refusal");
    }

    @Test
    void overlappingRulesAreSummedBeforeTheCapacityCheck() {
        Exam e = newExam("BLUEPRINT");
        Blueprint b = blueprint(e, mediumMcq);
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(),
                                           Difficulty.MEDIUM, QuestionType.MCQ, 1));
        assertThrows(InsufficientQuestionsException.class, () -> blueprints.save(b));
    }

    @Test
    void emptyBlueprintIsInvalid() {
        Exam e = newExam("BLUEPRINT");
        Blueprint b = blueprint(e, 1);
        b.getRules().clear();
        assertThrows(InvalidBlueprintException.class, () -> blueprints.save(b));
    }

    @Test
    void blueprintStrategyHonoursTheRuleAndIsStablePerStudent() {
        Exam e = newExam("BLUEPRINT");
        int want = Math.min(2, mediumMcq);
        blueprints.save(blueprint(e, want));
        // Saving again replaces rather than duplicates the rules.
        blueprints.save(blueprint(e, want));
        assertEquals(1, blueprints.findByExam(e.getId()).orElseThrow().getRules().size());

        List<Question> paper = generator.generate(e, null);
        assertEquals(want, paper.size());
        assertTrue(paper.stream().allMatch(q -> q.getTopicId().equals(topic.getId())
            && q.getDifficulty() == Difficulty.MEDIUM && q.getType() == QuestionType.MCQ));
        assertEquals(want, new HashSet<>(paper.stream().map(Question::getId).toList()).size());
        assertEquals(paper.stream().map(Question::getId).toList(),
                     generator.generate(e, null).stream().map(Question::getId).toList());
    }

    @Test
    void randomPoolAndAdaptiveDrawTheBlueprintTotalFromTheWholeSubject() {
        for (String name : List.of("RANDOM_POOL", "ADAPTIVE")) {
            Exam e = newExam(name);
            blueprints.save(blueprint(e, Math.min(3, mediumMcq)));
            List<Question> paper = generator.generate(e, null);
            assertEquals(Math.min(3, mediumMcq), paper.size());
            assertEquals(paper.size(), new HashSet<>(paper.stream().map(Question::getId).toList()).size());
        }
    }

    @Test
    void adaptiveStepsAndPicks() {
        assertEquals(Difficulty.HARD, AdaptiveDifficultyStrategy.step(Difficulty.MEDIUM, true));
        assertEquals(Difficulty.HARD, AdaptiveDifficultyStrategy.step(Difficulty.HARD, true));
        assertEquals(Difficulty.EASY, AdaptiveDifficultyStrategy.step(Difficulty.EASY, false));

        List<Question> all = new QuestionDaoImpl().findBySubject(subjectId);
        Question picked = AdaptiveDifficultyStrategy.next(all, Difficulty.HARD, new Random(1));
        assertEquals(Difficulty.HARD, picked.getDifficulty());
    }
}
