package com.exampro.ui.controller;

import com.exampro.exception.ExamProException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.Attempt;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.User;
import com.exampro.service.ExamDeliveryService;
import com.exampro.ui.util.AlertFactory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** The student's exam list. Starting one swaps this page's content for the exam screen. */
public class StudentExamsView extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    private final ExamDeliveryService service = new ExamDeliveryService();
    private final User student;

    public StudentExamsView(User student) {
        this.student = student;
        showList();
    }

    private void showList() {
        Label heading = new Label("My exams");
        heading.getStyleClass().add("heading");
        VBox rows = new VBox(10);

        try {
            List<Exam> open = service.openExams();
            if (open.isEmpty()) {
                Label none = new Label("No exams are open right now.");
                none.getStyleClass().add("status-label");
                rows.getChildren().add(none);
            }
            for (Exam exam : open) {
                rows.getChildren().add(row(exam));
            }
        } catch (ExamProException e) {
            AlertFactory.error("Could not load your exams", e);
        }

        VBox page = new VBox(14, heading, rows);
        page.setPadding(new Insets(4, 0, 0, 0));
        setCenter(page);
    }

    private HBox row(Exam exam) {
        Optional<Attempt> attempt = service.attemptOf(exam, student);

        Label title = new Label(exam.getTitle());
        title.getStyleClass().add("heading");
        Label meta = new Label(exam.getDurationMinutes() + " min  ·  closes "
                               + exam.getClosesAt().format(WHEN));
        meta.getStyleClass().add("status-label");

        Button action = new Button();
        action.getStyleClass().add("primary-button");
        if (attempt.isPresent() && attempt.get().isFinished()) {
            Attempt a = attempt.get();
            boolean graded = a.getStatus() == com.exampro.model.enums.AttemptStatus.GRADED;
            String score = a.getTotalScore() == null ? "" : "  ·  " + a.getTotalScore() + " mark(s)";
            action.setText(graded ? "Graded" + score
                                  : "Submitted" + score + (a.getTotalScore() == null ? "" : "  (marking in progress)"));
            action.setDisable(true);
        } else {
            action.setText(attempt.isPresent() ? "Resume" : "Start");
            action.setOnAction(e -> start(exam));
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(14, new VBox(4, title, meta), spacer, action);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("toolbar-row");
        return row;
    }

    private void start(Exam exam) {
        try {
            ExamDeliveryService.Sitting sitting = service.start(exam, student);
            setCenter(new ExamTakingView(service, sitting, this::showList));
        } catch (ValidationException | com.exampro.exception.ExamException e) {
            AlertFactory.warn("Cannot start this exam", e.getMessage());
            showList();
        } catch (ExamProException e) {
            AlertFactory.error("Could not start the exam", e);
        }
    }
}
