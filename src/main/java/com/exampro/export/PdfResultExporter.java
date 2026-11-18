package com.exampro.export;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** A one-exam result sheet: who scored what, with the cohort average on top. */
public class PdfResultExporter implements Exporter<ResultRow> {

    private static final float[] COLUMNS = {0, 190, 290, 360, 420};

    private final String examTitle;

    public PdfResultExporter(String examTitle) {
        this.examTitle = examTitle;
    }

    @Override
    public void export(List<ResultRow> rows, Path target) {
        try (PdfDoc pdf = new PdfDoc()) {
            pdf.paragraph("Result sheet", 10, false, 0);
            pdf.paragraph(examTitle, 20, true, 0);
            pdf.gap(4);

            List<ResultRow> scored = rows.stream().filter(r -> r.score() != null && r.maxScore() > 0).toList();
            double mean = scored.stream().mapToDouble(r -> 100 * r.score() / r.maxScore()).average().orElse(0);
            pdf.paragraph(rows.size() + " attempt(s)  |  " + scored.size() + " scored  |  mean "
                          + String.format("%.1f", mean) + "%  |  generated " + LocalDate.now(), 10, false, 0);
            pdf.gap(10);

            pdf.row(new String[] {"Student", "Status", "Score", "Percent", "Integrity"}, COLUMNS, 10, true);
            pdf.rule();
            for (ResultRow r : rows) {
                pdf.row(new String[] {
                    r.student(),
                    r.status().replace('_', ' ').toLowerCase(),
                    r.score() == null ? "-" : trim(r.score()) + " / " + trim(r.maxScore()),
                    r.score() == null || r.maxScore() <= 0 ? "-" : String.format("%.1f%%", 100 * r.score() / r.maxScore()),
                    r.integrity() == null ? "-" : String.valueOf(Math.round(r.integrity()))
                }, COLUMNS, 10, false);
            }
            pdf.save(target);
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }
}
