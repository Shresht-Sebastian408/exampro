package com.exampro.export;

import com.exampro.exception.ExportException;
import com.exampro.model.question.Question;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RFC 4180 CSV. List cells (options, correct, keywords, tags) are joined with
 * '|'. ponytail: an item containing '|' cannot round-trip, so export refuses
 * it and points at JSON; switch list cells to JSON arrays if that bites.
 */
public class CsvQuestionExporter implements Exporter<Question> {

    static final List<String> HEADER = List.of(
        "topic", "type", "difficulty", "marks", "negativeMarks", "stem", "options",
        "correct", "numericAnswer", "tolerance", "keywords", "tags", "explanation");

    @Override
    public void export(List<Question> items, Path target) {
        StringBuilder out = new StringBuilder("﻿"); // BOM so Excel reads UTF-8
        out.append(String.join(",", HEADER)).append("\r\n");
        for (Question q : items) {
            QuestionRecord r = QuestionRecord.from(q);
            List<String> cells = List.of(
                nz(r.topic), nz(r.type), nz(r.difficulty), num(r.marks), num(r.negativeMarks),
                nz(r.stem), join(r.options), join(r.correct), num(r.numericAnswer),
                num(r.tolerance), join(r.keywords), join(r.tags), nz(r.explanation));
            out.append(cells.stream().map(CsvQuestionExporter::quote)
                            .collect(Collectors.joining(","))).append("\r\n");
        }
        try {
            Files.writeString(target, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ExportException("Could not write " + target, e);
        }
    }

    /** Parses what {@link #export} writes (and hand-made files of the same shape). */
    public static List<QuestionRecord> read(Path source) {
        List<List<String>> rows;
        try {
            rows = parse(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExportException("Could not read " + source, e);
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> head = rows.get(0).stream().map(String::trim).toList();
        List<QuestionRecord> out = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            QuestionRecord r = new QuestionRecord();
            r.topic = cell(head, row, "topic");
            r.type = cell(head, row, "type");
            r.difficulty = cell(head, row, "difficulty");
            r.marks = dbl(cell(head, row, "marks"));
            r.negativeMarks = dbl(cell(head, row, "negativeMarks"));
            r.stem = cell(head, row, "stem");
            r.options = split(cell(head, row, "options"));
            r.correct = split(cell(head, row, "correct")).stream()
                            .map(s -> (int) Math.round(Double.parseDouble(s.trim()))).toList();
            r.numericAnswer = dbl(cell(head, row, "numericAnswer"));
            r.tolerance = dbl(cell(head, row, "tolerance"));
            r.keywords = split(cell(head, row, "keywords"));
            r.tags = split(cell(head, row, "tags"));
            r.explanation = cell(head, row, "explanation");
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static String join(List<?> items) {
        for (Object o : items) {
            if (String.valueOf(o).contains("|")) {
                throw new ExportException(
                    "'" + o + "' contains '|', which CSV uses as a list separator. Export as JSON.");
            }
        }
        return items.stream().map(String::valueOf).collect(Collectors.joining("|"));
    }

    private static List<String> split(String cell) {
        return cell.isBlank() ? new ArrayList<>() : new ArrayList<>(List.of(cell.split("\\|", -1)));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String num(Double d) {
        return d == null ? "" : d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static Double dbl(String s) {
        try {
            return s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            throw new ExportException("'" + s + "' is not a number.");
        }
    }

    private static String cell(List<String> head, List<String> row, String column) {
        int i = head.indexOf(column);
        return i < 0 || i >= row.size() ? "" : row.get(i);
    }

    private static String quote(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
               ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** Minimal RFC 4180 reader: quoted cells, doubled quotes, newlines inside quotes. */
    static List<List<String>> parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cur.toString());
                cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            rows.add(row);
        }
        return rows;
    }
}
