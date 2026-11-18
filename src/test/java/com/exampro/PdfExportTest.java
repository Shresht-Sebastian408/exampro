package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.dao.impl.AttemptDaoImpl;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.dao.impl.UserDaoImpl;
import com.exampro.export.PdfPaperExporter;
import com.exampro.export.PdfResultExporter;
import com.exampro.export.ResultRow;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Response;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.service.BlueprintService;
import com.exampro.service.ExamDeliveryService;
import com.exampro.service.PaperGenerationService;
import com.exampro.service.QuestionBankService;
import com.exampro.service.RecoveryCache;
import com.exampro.service.ResultsService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Week ten: PDFs are read back with PDFBox and checked for content, paging and hostile text. */
class PdfExportTest {

    static {
        System.setProperty("exampro.jdbcUrl", "jdbc:h2:mem:exampro_pdf_test;DB_CLOSE_DELAY=-1");
    }

    private static Topic topic;

    @BeforeAll
    static void setUp() {
        new SchemaInitializer().initialize();
        new DataSeeder().seedIfEmpty();
        long subjectId = new SubjectDaoImpl().findByCode("CS201").orElseThrow().getId();
        topic = new TopicDaoImpl().findBySubject(subjectId).stream().filter(t -> !t.isRoot()).findFirst().orElseThrow();
        QuestionBankService bank = new QuestionBankService();
        for (int i = 0; i < 2; i++) {
            Question q = QuestionBankService.blank(QuestionType.MCQ);
            q.setTopicId(topic.getId());
            q.setStem("pdf test " + i);
            q.getOptions().get(0).setText("right");
            q.getOptions().get(1).setText("wrong");
            bank.save(q);
        }
    }

    @AfterAll
    static void tearDown() {
        ConnectionPool.shutdown();
    }

    private static String textOf(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pagesOf(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void resultSheetListsEveryStudentAndTheAverage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("results.pdf");
        new PdfResultExporter("Midterm CS201").export(List.of(
            new ResultRow("Asha Rao", "GRADED", 8.0, 10, 96.0),
            new ResultRow("Ben Cole", "AUTO_SUBMITTED", 4.0, 10, 70.0),
            new ResultRow("Cara Dunn", "SUBMITTED", null, 10, null)), file);

        String text = textOf(file);
        assertTrue(text.contains("Midterm CS201"));
        assertTrue(text.contains("Asha Rao") && text.contains("Ben Cole") && text.contains("Cara Dunn"));
        assertTrue(text.contains("8 / 10"));
        assertTrue(text.contains("mean 60.0%"), "mean of 80% and 40%; the unscored attempt is excluded");
        assertTrue(text.contains("auto submitted"));
    }

    @Test
    void aLongSheetSpillsOntoFurtherPages(@TempDir Path dir) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rows.add(new ResultRow("Student " + i, "GRADED", (double) (i % 10), 10, 90.0));
        }
        Path file = dir.resolve("long.pdf");
        new PdfResultExporter("Big exam").export(rows, file);
        assertTrue(pagesOf(file) >= 3);
        assertTrue(textOf(file).contains("Student 119"));
        assertTrue(textOf(file).contains("Page 2"));
    }

    @Test
    void paperHasQuestionsOptionsMarksAndAKeyOnItsOwnPage(@TempDir Path dir) throws Exception {
        QuestionBankService bank = new QuestionBankService();
        Question mcq = QuestionBankService.blank(QuestionType.MCQ);
        mcq.setStem("Which is a prime number?");
        mcq.setMarks(2);
        mcq.setNegativeMarks(0.5);
        mcq.getOptions().get(0).setText("7");
        mcq.getOptions().get(1).setText("8");
        NumericQuestion num = (NumericQuestion) QuestionBankService.blank(QuestionType.NUMERIC);
        num.setStem("What is 6 x 7?");
        num.setAnswer(42.0);
        ShortAnswerQuestion sa = (ShortAnswerQuestion) QuestionBankService.blank(QuestionType.SHORT_ANSWER);
        sa.setStem("Define recursion.");
        sa.getKeywords().add("itself");

        Path withKey = dir.resolve("paper-key.pdf");
        new PdfPaperExporter("Quiz 1", 30, true).export(List.of(mcq, num, sa), withKey);
        String text = textOf(withKey);
        assertTrue(text.contains("Which is a prime number?"));
        assertTrue(text.contains("[2 marks, -0.5 if wrong]"));
        assertTrue(text.contains("(A)") && text.contains("(B)"));
        assertTrue(text.contains("Answer: ____"));
        assertTrue(text.contains("Answer key"));
        assertEquals(2, pagesOf(withKey), "the key starts a new page");
        assertTrue(text.contains("42.0"), "the key lists the numeric answer");

        Path noKey = dir.resolve("paper.pdf");
        new PdfPaperExporter("Quiz 1", 30, false).export(List.of(mcq, num, sa), noKey);
        assertFalse(textOf(noKey).contains("Answer key"));
        assertEquals(1, pagesOf(noKey));
    }

    @Test
    void textOutsideLatin1AndHugeWordsDoNotBreakTheDocument(@TempDir Path dir) throws Exception {
        Question q = QuestionBankService.blank(QuestionType.NUMERIC);
        q.setStem("Compute π × r² for 中文 " + "x".repeat(300));
        ((NumericQuestion) q).setAnswer(1.0);
        Path file = dir.resolve("odd.pdf");
        new PdfPaperExporter("Odd", 10, false).export(List.of(q), file);
        String text = textOf(file);
        assertTrue(text.contains("Compute ? × r² for ??"), "Latin-1 survives, the rest becomes '?'");
    }

    @Test
    void resultRowsComeFromRealAttemptsBestFirst(@TempDir Path dir) {
        ExamDeliveryService svc = new ExamDeliveryService(new ExamDaoImpl(), new AttemptDaoImpl(),
            new PaperGenerationService(), new RecoveryCache(dir));
        Exam e = new Exam();
        e.setSubjectId(topic.getSubjectId());
        e.setTitle("pdf rows");
        e.setPublished(true);
        e.setOpensAt(LocalDateTime.now().minusHours(1));
        e.setClosesAt(LocalDateTime.now().plusHours(2));
        new ExamDaoImpl().insert(e);
        Blueprint b = new Blueprint();
        b.setExamId(e.getId());
        b.setName("bp");
        int pool = new BlueprintService().available(topic.getId(), Difficulty.MEDIUM, QuestionType.MCQ);
        b.getRules().add(new BlueprintRule(topic.getId(), topic.getName(), Difficulty.MEDIUM, QuestionType.MCQ, pool));
        new BlueprintService().save(b);

        // student18 answers everything right, student19 nothing
        var top = svc.start(e, new UserDaoImpl().findByUsername("student18").orElseThrow());
        List<Response> right = top.paper().stream().map(q -> {
            Response r = new Response(q.getId());
            r.setAttemptId(top.attempt().getId());
            r.getSelectedOptionIds().add(q.correctOptions().get(0).getId());
            return r;
        }).toList();
        svc.submit(top.attempt(), right, false);
        var none = svc.start(e, new UserDaoImpl().findByUsername("student19").orElseThrow());
        svc.submit(none.attempt(), List.of(), false);

        List<ResultRow> rows = new ResultsService().rows(e.getId());
        assertEquals(2, rows.size());
        assertEquals(pool, rows.get(0).score(), "best first");
        assertEquals(pool, rows.get(0).maxScore());
        assertEquals(0.0, rows.get(1).score());
        assertEquals(100.0, rows.get(0).integrity(), "a clean attempt keeps full integrity");
    }
}
