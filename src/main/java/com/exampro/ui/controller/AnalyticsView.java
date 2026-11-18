package com.exampro.ui.controller;

import com.exampro.analytics.ItemStat;
import com.exampro.analytics.ScoreSummary;
import com.exampro.dao.ExamDao;
import com.exampro.dao.QuestionDao;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.QuestionDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.model.entity.Exam;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.service.AnalyticsService;
import com.exampro.ui.util.AlertFactory;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-exam analytics: how the cohort scored, and how each question behaved.
 * Selecting a question shows which options students picked, so a distractor
 * nobody chose (or the wrong one everybody chose) is easy to spot.
 */
public class AnalyticsView extends BorderPane {

    private static final String[] BAND_LABELS =
        {"0-9", "10-19", "20-29", "30-39", "40-49", "50-59", "60-69", "70-79", "80-89", "90-100"};

    private final ExamDao examDao = new ExamDaoImpl();
    private final QuestionDao questionDao = new QuestionDaoImpl();
    private final AnalyticsService service = new AnalyticsService();

    private final ComboBox<Exam> examPicker = new ComboBox<>();
    private final Label summaryLine = new Label("Choose an exam.");
    private final VBox body = new VBox(16);
    private final Map<Long, Question> questions = new HashMap<>();

    public AnalyticsView() {
        Label heading = new Label("Analytics");
        heading.getStyleClass().add("heading");
        examPicker.setPromptText("Choose an exam");
        examPicker.setPrefWidth(300);
        examPicker.valueProperty().addListener((o, a, b) -> load());
        Button refresh = new Button("Recompute");
        refresh.setOnAction(e -> load());

        Button exportPdf = new Button("Export result sheet (PDF)");
        exportPdf.setOnAction(e -> exportResults());
        HBox bar = new HBox(12, heading, examPicker, refresh, exportPdf);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-row");
        setTop(bar);

        summaryLine.getStyleClass().add("status-label");
        body.setPadding(new Insets(8, 4, 8, 0));
        body.getChildren().add(summaryLine);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("editor-scroll");
        setCenter(scroll);

        try {
            examPicker.setItems(FXCollections.observableArrayList(examDao.findAll()));
        } catch (ExamProException e) {
            AlertFactory.error("Could not load exams", e);
        }
    }

    private void exportResults() {
        Exam exam = examPicker.getValue();
        if (exam == null) {
            summaryLine.setText("Choose an exam first.");
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save result sheet");
        chooser.setInitialFileName(exam.getTitle().replaceAll("[^A-Za-z0-9 _-]", "") + " results.pdf");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            new com.exampro.export.PdfResultExporter(exam.getTitle())
                .export(new com.exampro.service.ResultsService().rows(exam.getId()), file.toPath());
            summaryLine.setText("Saved " + file.getName());
        } catch (ExamProException e) {
            AlertFactory.error("Could not export the result sheet", e);
        }
    }

    private void load() {
        Exam exam = examPicker.getValue();
        if (exam == null) {
            return;
        }
        try {
            AnalyticsService.Report report = service.compute(exam.getId());
            body.getChildren().clear();

            ScoreSummary s = report.summary();
            if (s.attempts() == 0) {
                body.getChildren().add(new Label("Nobody has submitted this exam yet."));
                return;
            }
            summaryLine.setText(s.attempts() + " attempt(s)  ·  mean " + fmt(s.mean()) + "%  ·  median "
                                + fmt(s.median()) + "%  ·  range " + fmt(s.min()) + "% to " + fmt(s.max()) + "%");
            body.getChildren().addAll(summaryLine, distributionChart(s));

            if (!report.items().isEmpty()) {
                TableView<ItemStat> table = itemTable(report.items());
                VBox detail = new VBox(8);
                table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDistractors(detail, b));
                body.getChildren().addAll(scatter(report.items()), table, detail);
            }
            com.exampro.ui.animation.StaggerAnimator.play(body.getChildren(), 110);
        } catch (ExamProException e) {
            AlertFactory.error("Could not compute analytics", e);
        }
    }

    // ------------------------------------------------------------------

    private BarChart<String, Number> distributionChart(ScoreSummary s) {
        CategoryAxis x = new CategoryAxis();
        x.setLabel("Score (%)");
        NumberAxis y = new NumberAxis();
        y.setLabel("Students");
        y.setMinorTickVisible(false);
        y.setTickUnit(1);

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (int i = 0; i < 10; i++) {
            series.getData().add(new XYChart.Data<>(BAND_LABELS[i], s.bands()[i]));
        }
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setTitle("Score distribution");
        chart.setLegendVisible(false);
        chart.setPrefHeight(260);
        chart.getData().add(series);
        return chart;
    }

    private ScatterChart<Number, Number> scatter(List<ItemStat> items) {
        NumberAxis x = new NumberAxis(0, 1, 0.2);
        x.setLabel("Difficulty (p value: higher is easier)");
        NumberAxis y = new NumberAxis(-1, 1, 0.5);
        y.setLabel("Discrimination");

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        int[] order = {0};
        for (ItemStat i : items) {
            if (i.pValue() != null && i.discrimination() != null) {
                XYChart.Data<Number, Number> point = new XYChart.Data<>(i.pValue(), i.discrimination());
                series.getData().add(point);
                point.nodeProperty().addListener((o, a, node) -> {
                    if (node != null) {
                        node.setOpacity(0);
                        javafx.animation.FadeTransition in = new javafx.animation.FadeTransition(
                            javafx.util.Duration.millis(260), node);
                        in.setToValue(1);
                        in.setDelay(javafx.util.Duration.millis(500 + 40.0 * order[0]++));
                        in.play();
                        Tooltip.install(node, new Tooltip(stemOf(i.questionId())));
                        // the default series colour is orange, too close to the red to tell apart
                        node.setStyle("-fx-background-color: " + (i.needsRevision() ? "#d9534f" : "#5b8def") + ";");
                    }
                });
            }
        }
        ScatterChart<Number, Number> chart = new ScatterChart<>(x, y);
        chart.setTitle("Item analysis (red = worth revising)");
        chart.setLegendVisible(false);
        chart.setPrefHeight(300);
        chart.getData().add(series);
        return chart;
    }

    private TableView<ItemStat> itemTable(List<ItemStat> items) {
        TableView<ItemStat> table = new TableView<>(FXCollections.observableArrayList(items));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(260);
        table.getColumns().addAll(
            column("Question", i -> stemOf(i.questionId())),
            column("Answered", i -> String.valueOf(i.attempts())),
            column("p", i -> i.pValue() == null ? "-" : String.format("%.2f", i.pValue())),
            column("Discrimination", i -> i.discrimination() == null ? "-" : String.format("%.2f", i.discrimination())),
            column("Verdict", i -> i.needsRevision() ? "revise" : "ok"));
        return table;
    }

    private TableColumn<ItemStat, String> column(String title, Function<ItemStat, String> value) {
        TableColumn<ItemStat, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    /** Bars for how many students chose each option; the correct option is marked. */
    private void showDistractors(VBox target, ItemStat stat) {
        target.getChildren().clear();
        if (stat == null) {
            return;
        }
        Question q = question(stat.questionId());
        if (q == null || q.getOptions().isEmpty()) {
            target.getChildren().add(new Label("Option breakdown applies to choice questions only."));
            return;
        }
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel("Students");
        y.setMinorTickVisible(false);
        y.setTickUnit(1);

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        int n = 1;
        for (QuestionOption o : q.getOptions()) {
            String label = (o.isCorrect() ? "✓ " : "") + (n++) + ". " + shorten(o.getText());
            series.getData().add(new XYChart.Data<>(label, stat.distractors().getOrDefault(o.getId(), 0)));
        }
        series.getData().add(new XYChart.Data<>("(blank)", stat.blanks()));

        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setTitle("Which options were chosen: " + shorten(q.getStem()));
        chart.setLegendVisible(false);
        chart.setPrefHeight(240);
        chart.getData().add(series);
        VBox.setVgrow(chart, Priority.NEVER);
        target.getChildren().add(chart);
    }

    // ------------------------------------------------------------------

    private Question question(long id) {
        return questions.computeIfAbsent(id, k -> questionDao.findById(k).orElse(null));
    }

    private String stemOf(long id) {
        Question q = question(id);
        return q == null ? "Question " + id : q.shortStem();
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() <= 28 ? s : s.substring(0, 25) + "...";
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
