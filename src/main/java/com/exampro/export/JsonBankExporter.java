package com.exampro.export;

import com.exampro.exception.ExportException;
import com.exampro.model.question.Question;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** JSON bank: an array of {@link QuestionRecord}. Also the JSON reader. */
public class JsonBankExporter implements Exporter<Question> {

    private static final ObjectMapper JSON =
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void export(List<Question> items, Path target) {
        try {
            JSON.writeValue(target.toFile(), items.stream().map(QuestionRecord::from).toList());
        } catch (IOException e) {
            throw new ExportException("Could not write " + target, e);
        }
    }

    public static List<QuestionRecord> read(Path source) {
        try {
            return JSON.readValue(Files.readAllBytes(source), new TypeReference<>() {});
        } catch (IOException e) {
            throw new ExportException("Could not read JSON from " + source + ": " + e.getMessage(), e);
        }
    }
}
