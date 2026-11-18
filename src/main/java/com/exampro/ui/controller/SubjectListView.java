package com.exampro.ui.controller;

import com.exampro.dao.SubjectDao;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.model.entity.Subject;
import com.exampro.ui.util.AlertFactory;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * The week-one vertical slice: subjects straight out of H2 into a table.
 *
 * Deliberately plain. The point is to prove the full stack works, pool to
 * DAO to UI, before anything is built on top of it. The styling and
 * animation pass comes later.
 */
public class SubjectListView extends BorderPane {

    private final SubjectDao subjectDao = new SubjectDaoImpl();
    private final ObservableList<Subject> rows = FXCollections.observableArrayList();
    private final Label status = new Label();

    public SubjectListView() {
        setPadding(new Insets(16));

        Label heading = new Label("Subjects");
        heading.getStyleClass().add("heading");

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(12, heading, refresh);
        header.getStyleClass().add("toolbar-row");
        setTop(header);

        setCenter(buildTable());

        status.getStyleClass().add("status-label");
        BorderPane.setMargin(status, new Insets(8, 0, 0, 0));
        setBottom(status);

        reload();
    }

    private TableView<Subject> buildTable() {
        TableView<Subject> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No subjects yet."));

        table.getColumns().addAll(
            column("Code", "code", 90),
            column("Name", "name", 200),
            column("Description", "description", 340),
            column("Topics", "topicCount", 80),
            column("Questions", "questionCount", 90));

        HBox.setHgrow(table, Priority.ALWAYS);
        return table;
    }

    private <V> TableColumn<Subject, V> column(String title, String property, double width) {
        TableColumn<Subject, V> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        return col;
    }

    private void reload() {
        try {
            rows.setAll(subjectDao.findAllWithCounts());
            status.setText(rows.size() + " subject(s) loaded from "
                           + com.exampro.config.DatabaseConfig.JDBC_URL);
        } catch (ExamProException e) {
            // Controller layer policy: the user sees a sentence, never a trace.
            rows.clear();
            status.setText("Could not load subjects.");
            AlertFactory.error("Could not load subjects", e);
        }
    }
}
