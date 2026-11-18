package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.exception.InvalidQuestionException;
import com.exampro.export.CsvQuestionExporter;
import com.exampro.export.JsonBankExporter;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.MCQQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.service.QuestionBankService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week four: tags, CSV and JSON round trips, import validation. */
class BankPolishTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_polish_test;DB_CLOSE_DELAY=-1");
    }

    private static final QuestionDaoImpl dao = new QuestionDaoImpl();
    private static final QuestionBankService service = new QuestionBankService(dao);
    private static long subjectId;
    private static Topic topic;
    private static final Map<String, Long> topicIds = new HashMap<>();

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        for (Topic t : new TopicDaoImpl().findBySubject(subjectId)) {
            if (!t.isRoot()) {
                topicIds.put(t.getName().toLowerCase(), t.getId());
                if (topic == null) {
                    topic = t;
                }
            }
        }
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private Question mcq(String stem, String... tags) {
        MCQQuestion q = (MCQQuestion) QuestionBankService.blank(QuestionType.MCQ);
        q.setTopicId(topic.getId());
        q.setStem(stem);
        q.getOptions().clear();
        q.getOptions().add(new QuestionOption("a, with comma", true, 0));
        q.getOptions().add(new QuestionOption("b \"quoted\"", false, 1));
        q.getTags().addAll(List.of(tags));
        return q;
    }

    @Test
    void tagsAreNormalisedAndRoundTrip() {
        long id = service.save(mcq("tag test " + System.nanoTime(), " Java ", "java", "OOP", ""));
        Question back = dao.findById(id).orElseThrow();
        assertEquals(Set.of("java", "oop"), back.getTags());

        back.getTags().remove("oop");
        service.save(back);
        assertEquals(Set.of("java"), dao.findById(id).orElseThrow().getTags());
    }

    @Test
    void overlongTagIsRejected() {
        assertThrows(InvalidQuestionException.class, () -> service.save(mcq("x", "t".repeat(51))));
    }

    @Test
    void csvAndJsonRoundTripThroughImport(@TempDir Path dir) {
        List<Question> sample = dao.findBySubject(subjectId).stream().limit(15).toList();
        for (var exporter : List.of(new CsvQuestionExporter(), new JsonBankExporter())) {
            Path file = dir.resolve(exporter instanceof CsvQuestionExporter ? "b.csv" : "b.json");
            exporter.export(sample, file);
            long before = dao.count();
            var result = service.importFile(file, topicIds);
            assertEquals(List.of(), result.errors());
            assertEquals(sample.size(), result.imported());
            assertEquals(before + sample.size(), dao.count());
        }
    }

    @Test
    void importReportsBadRowsAndKeepsGoodOnes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mixed.csv");
        String topicName = topic.getName();
        Files.writeString(file, String.join("\n",
            "topic,type,difficulty,marks,stem,options,correct",
            topicName + ",MCQ,EASY,2,\"Good, quoted\",x|y,1",
            "Nowhere,MCQ,EASY,2,Bad topic,x|y,1",
            topicName + ",MCQ,EASY,2,Two correct,x|y,1|2"));
        var result = service.importFile(file, topicIds);
        assertEquals(1, result.imported());
        assertEquals(2, result.errors().size());
        assertTrue(result.errors().get(0).startsWith("Row 2"));
    }
}
