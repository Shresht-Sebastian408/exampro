package com.exampro.export;

import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;

import java.nio.file.Path;
import java.util.List;

/**
 * A printable question paper, optionally followed by an answer key on its
 * own page. Options appear in the order the questions carry them; the paper
 * a student actually sat is the frozen one, which this exports if given it.
 */
public class PdfPaperExporter implements Exporter<Question> {

    private final String title;
    private final int durationMinutes;
    private final boolean includeKey;

    public PdfPaperExporter(String title, int durationMinutes, boolean includeKey) {
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.includeKey = includeKey;
    }

    @Override
    public void export(List<Question> paper, Path target) {
        try (PdfDoc pdf = new PdfDoc()) {
            double total = paper.stream().mapToDouble(Question::getMarks).sum();
            pdf.paragraph(title, 20, true, 0);
            pdf.paragraph("Time allowed: " + durationMinutes + " minutes   |   Total marks: " + trim(total)
                          + "   |   Questions: " + paper.size(), 10, false, 0);
            pdf.rule();
            pdf.gap(6);

            int n = 1;
            for (Question q : paper) {
                pdf.ensureSpace(60);
                pdf.paragraph(n++ + ".  " + q.getStem() + "   [" + trim(q.getMarks())
                              + (q.getMarks() == 1 ? " mark" : " marks")
                              + (q.getNegativeMarks() > 0 ? ", -" + trim(q.getNegativeMarks()) + " if wrong" : "")
                              + "]", 11, true, 0);
                body(pdf, q);
                pdf.gap(10);
            }

            if (includeKey) {
                pdf.ensureSpace(1000);   // force the key onto a fresh page
                pdf.paragraph("Answer key", 18, true, 0);
                pdf.rule();
                int k = 1;
                for (Question q : paper) {
                    pdf.paragraph(k++ + ".  " + q.getAnswerSummary(), 10, false, 0);
                }
            }
            pdf.save(target);
        }
    }

    private void body(PdfDoc pdf, Question q) {
        switch (q.getType()) {
            case MCQ, MSQ, TRUE_FALSE -> {
                if (q.getType() == com.exampro.model.enums.QuestionType.MSQ) {
                    pdf.paragraph("Select all that apply.", 9, false, 18);
                }
                char letter = 'A';
                for (QuestionOption o : q.getOptions()) {
                    pdf.paragraph("(" + letter++ + ")  " + o.getText(), 10, false, 18);
                }
            }
            case NUMERIC -> pdf.paragraph("Answer: ______________________", 10, false, 18);
            case SHORT_ANSWER -> {
                for (int i = 0; i < 4; i++) {
                    pdf.paragraph("______________________________________________________________", 10, false, 18);
                }
            }
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
