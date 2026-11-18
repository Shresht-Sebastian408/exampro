package com.exampro.ui.controller;

import com.exampro.dao.GradingDao;
import com.exampro.dao.QuestionDao;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.Rubric;
import com.exampro.model.entity.User;
import com.exampro.model.question.Question;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.service.ManualGradingService;
import com.exampro.ui.util.AlertFactory;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Faculty marking queue for short answers. The keyword count is shown as a
 * hint beside the student's text; the marks are the marker's. With a rubric
 * there is one box per criterion, without one a single box for the answer.
 */
public class GradingView extends BorderPane {

    private final ManualGradingService service = new ManualGradingService();
    private final QuestionDao questions = new QuestionDaoImpl();
    private final com.exampro.service.ProctoringService proctoring = new com.exampro.service.ProctoringService();
    private final User grader;

    private final ListView<GradingDao.PendingItem> queue = new ListView<>();
    private final VBox detail = new VBox(10);
    private final Label status = new Label();
    private final Map<Long, Question> questionCache = new HashMap<>();

    public GradingView(User grader) {
        this.grader = grader;

        Label heading = new Label("Grading queue");
        heading.getStyleClass().add("heading");
        setTop(heading);

        queue.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(GradingDao.PendingItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.student() + "  ·  " + item.exam());
            }
        });
        queue.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> open(b));

        detail.setPadding(new Insets(0, 0, 0, 12));
        SplitPane split = new SplitPane(queue, detail);
        split.setDividerPositions(0.3);
        setCenter(split);

        status.getStyleClass().add("status-label");
        BorderPane.setMargin(status, new Insets(8, 0, 0, 0));
        setBottom(status);
        reload();
    }

    private void reload() {
        try {
            queue.setItems(FXCollections.observableArrayList(service.pending()));
            detail.getChildren().setAll(hint(queue.getItems().isEmpty()
                ? "Nothing is waiting for marks." : "Select an answer to mark."));
            status.setText(queue.getItems().size() + " answer(s) waiting.");
        } catch (ExamProException e) {
            AlertFactory.error("Could not load the queue", e);
        }
    }

    private void open(GradingDao.PendingItem item) {
        if (item == null) {
            return;
        }
        try {
            Question q = questionCache.computeIfAbsent(item.questionId(),
                id -> questions.findById(id).orElseThrow());
            Optional<Rubric> rubric = service.rubricFor(q);

            Label stem = new Label(q.getStem());
            stem.setWrapText(true);
            stem.getStyleClass().add("preview-stem");

            TextArea answer = new TextArea(item.answer());
            answer.setEditable(false);
            answer.setWrapText(true);
            answer.setPrefRowCount(6);
            answer.getStyleClass().add("field");

            String hits = q instanceof ShortAnswerQuestion s
                ? s.keywordHits(item.answer()) + " of " + s.getKeywords().size()
                  + " expected keywords found (" + q.getAnswerSummary() + ")"
                : "";

            Map<Long, TextField> boxes = new LinkedHashMap<>();
            VBox marks = new VBox(6);
            if (rubric.isPresent()) {
                for (Rubric.Criterion k : rubric.get().getCriteria()) {
                    TextField box = markBox();
                    boxes.put(k.id(), box);
                    marks.getChildren().add(row(k.description() + "  (max " + trim(k.maxMarks()) + ")", box));
                }
            } else {
                TextField box = markBox();
                boxes.put(null, box);
                marks.getChildren().add(row("Marks  (max " + trim(q.getMarks()) + ")", box));
            }

            TextField comment = new TextField();
            comment.setPromptText("Comment for the student (optional)");
            comment.getStyleClass().add("field");

            Button save = new Button("Save grade");
            save.getStyleClass().add("primary-button");
            save.setOnAction(e -> save(item, q, boxes, comment.getText()));
            Button editRubric = new Button(rubric.isPresent() ? "Edit rubric" : "Create rubric");
            editRubric.setOnAction(e -> editRubric(item, q, rubric.orElse(null)));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            var integrity = proctoring.integrityOf(item.attemptId());
            Label integrityHint = hint("Integrity: " + Math.round(integrity.score()) + "/100"
                + (integrity.counts().isEmpty() ? " (no events)" : "  " + integrity.counts()));

            detail.getChildren().setAll(stem, hint(hits), integrityHint, answer, marks, comment,
                                        new HBox(8, save, editRubric, spacer));
        } catch (ExamProException e) {
            AlertFactory.error("Could not open the answer", e);
        }
    }

    private void save(GradingDao.PendingItem item, Question q, Map<Long, TextField> boxes, String comment) {
        try {
            Map<Long, Double> marks = new LinkedHashMap<>();
            for (Map.Entry<Long, TextField> e : boxes.entrySet()) {
                String text = e.getValue().getText().trim();
                marks.put(e.getKey(), text.isEmpty() ? null : Double.valueOf(text));
            }
            double total = service.grade(item.responseId(), grader.getId(), q, marks, comment);
            reload();
            status.setText("Saved " + trim(total) + " mark(s). " + queue.getItems().size() + " waiting.");
        } catch (NumberFormatException e) {
            status.setText("Marks must be numbers.");
        } catch (ValidationException e) {
            status.setText(e.getMessage());
        } catch (ExamProException e) {
            AlertFactory.error("Could not save the grade", e);
        }
    }

    /** One criterion per line: "description, marks". */
    private void editRubric(GradingDao.PendingItem item, Question q, Rubric current) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Rubric for this question (worth " + trim(q.getMarks()) + " marks)");
        dialog.setContentText("One criterion per line, as: description, marks");
        TextArea area = new TextArea(current == null ? "" : current.getCriteria().stream()
            .map(k -> k.description() + ", " + trim(k.maxMarks()))
            .reduce((a, b) -> a + "\n" + b).orElse(""));
        area.setPrefRowCount(6);
        dialog.getDialogPane().setContent(area);

        dialog.showAndWait().ifPresent(ignored -> {
            try {
                List<Rubric.Criterion> criteria = new ArrayList<>();
                for (String line : area.getText().split("\\R")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int comma = line.lastIndexOf(',');
                    if (comma < 0) {
                        throw new ValidationException("Each line needs a comma and marks: " + line);
                    }
                    criteria.add(new Rubric.Criterion(null, line.substring(0, comma).trim(),
                                                      Double.parseDouble(line.substring(comma + 1).trim())));
                }
                service.saveRubric(q, criteria);
                open(item);
                status.setText("Rubric saved.");
            } catch (NumberFormatException e) {
                status.setText("Marks in the rubric must be numbers.");
            } catch (ValidationException e) {
                status.setText(e.getMessage());
            } catch (ExamProException e) {
                AlertFactory.error("Could not save the rubric", e);
            }
        });
    }

    private TextField markBox() {
        TextField f = new TextField();
        f.setPrefWidth(80);
        f.getStyleClass().add("field");
        return f;
    }

    private HBox row(String text, TextField box) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #d7dee9;");   // the default label colour is too dim on this background
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, label, box);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("status-label");
        l.setWrapText(true);
        return l;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
