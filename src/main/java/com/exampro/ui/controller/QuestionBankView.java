package com.exampro.ui.controller;

import com.exampro.dao.SubjectDao;
import com.exampro.dao.TopicDao;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.export.CsvQuestionExporter;
import com.exampro.export.Exporter;
import com.exampro.export.JsonBankExporter;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.entity.Subject;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;
import com.exampro.service.QuestionBankService;
import com.exampro.ui.component.QuestionEditor;
import com.exampro.ui.util.AlertFactory;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Question bank: subject and topic filter, the list, and the editor.
 *
 * Master and detail rather than a dialog, because the common action here is
 * editing twenty questions in a row and a modal per question makes that
 * tedious. The list reloads from the database after a save rather than
 * patching the row in place, so what is on screen is what was stored.
 */
public class QuestionBankView extends BorderPane {

    private static final Topic ALL_TOPICS = allTopicsSentinel();

    private final SubjectDao subjectDao = new SubjectDaoImpl();
    private final TopicDao topicDao = new TopicDaoImpl();
    private final QuestionBankService service = new QuestionBankService();

    private final ComboBox<Subject> subject = new ComboBox<>();
    private final ComboBox<Topic> topicFilter = new ComboBox<>();
    private final ObservableList<Question> all = FXCollections.observableArrayList();
    private final FilteredList<Question> rows = new FilteredList<>(all);
    private final TableView<Question> table = new TableView<>(rows);
    private final TextField search = new TextField();
    private final ComboBox<QuestionType> typeFilter = new ComboBox<>();
    private final ComboBox<Difficulty> difficultyFilter = new ComboBox<>();
    private List<Topic> topics = List.of();
    private final QuestionEditor editor;
    private final Label status = new Label();

    public QuestionBankView() {
        editor = new QuestionEditor(service, saved -> reload());

        VBox top = new VBox(8, buildToolbar(), buildFilterBar());
        setTop(top);
        setCenter(buildSplit());
        status.getStyleClass().add("status-label");
        BorderPane.setMargin(status, new Insets(8, 0, 0, 0));
        setBottom(status);

        loadSubjects();
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private HBox buildToolbar() {
        Label heading = new Label("Question bank");
        heading.getStyleClass().add("heading");

        subject.setPrefWidth(220);
        subject.setButtonCell(subjectCell());
        subject.setCellFactory(list -> subjectCell());
        subject.valueProperty().addListener((o, a, chosen) -> onSubjectChosen(chosen));

        topicFilter.setPrefWidth(200);
        topicFilter.setButtonCell(topicCell());
        topicFilter.setCellFactory(list -> topicCell());
        topicFilter.valueProperty().addListener((o, a, b) -> applyFilter());

        Button newQuestion = new Button("New question");
        newQuestion.getStyleClass().add("primary-button");
        newQuestion.setOnAction(e -> {
            table.getSelectionModel().clearSelection();
            editor.startNew(QuestionType.MCQ);
        });

        Button deactivate = new Button("Retire");
        deactivate.setOnAction(e -> retireSelected());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, heading, subject, topicFilter, spacer, newQuestion, deactivate);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-row");
        return bar;
    }

    private HBox buildFilterBar() {
        search.setPromptText("Search text, topic or tag");
        search.setPrefWidth(260);
        search.getStyleClass().add("field");
        search.textProperty().addListener((o, a, b) -> applyFilter());

        typeFilter.setPromptText("Any type");
        typeFilter.setItems(FXCollections.observableArrayList(QuestionType.values()));
        typeFilter.valueProperty().addListener((o, a, b) -> applyFilter());

        difficultyFilter.setPromptText("Any difficulty");
        difficultyFilter.setItems(FXCollections.observableArrayList(Difficulty.values()));
        difficultyFilter.valueProperty().addListener((o, a, b) -> applyFilter());

        Button clear = new Button("Clear filters");
        clear.setOnAction(e -> {
            search.clear();
            typeFilter.setValue(null);
            difficultyFilter.setValue(null);
            topicFilter.setValue(ALL_TOPICS);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button importButton = new Button("Import…");
        importButton.setOnAction(e -> importQuestions());
        Button exportButton = new Button("Export…");
        exportButton.setOnAction(e -> exportQuestions());

        HBox bar = new HBox(10, search, typeFilter, difficultyFilter, clear, spacer,
                            importButton, exportButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-row");
        return bar;
    }

    private SplitPane buildSplit() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No questions on this selection yet."));
        table.getColumns().addAll(
            column("Type", 90, q -> q.getType().name()),
            column("Difficulty", 90, q -> q.getDifficulty().name()),
            column("Marks", 60, q -> String.valueOf(q.getMarks())),
            column("Topic", 150, Question::getTopicName),
            column("Question", 380, Question::shortStem),
            column("Answer", 160, Question::getAnswerSummary),
            column("Tags", 120, q -> String.join(", ", q.getTags())));

        table.getSelectionModel().selectedItemProperty().addListener((o, a, chosen) -> {
            if (chosen != null) {
                editor.load(chosen);
            }
        });

        SplitPane split = new SplitPane(table, editor.inScrollPane());
        split.setDividerPositions(0.56);
        return split;
    }

    private TableColumn<Question, String> column(String title, double width,
                                                 java.util.function.Function<Question, String> value) {
        TableColumn<Question, String> col = new TableColumn<>(title);
        // A plain value extractor rather than PropertyValueFactory: half of
        // these cells are derived (getAnswerSummary, shortStem) and are not
        // bean properties at all.
        col.setCellValueFactory(cell ->
            new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    /** Indents each topic under its parent so the combo reads as the tree. */
    private ListCell<Topic> topicCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Topic item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Topic" : "    ".repeat(depth(item)) + item.getName());
            }
        };
    }

    private int depth(Topic topic) {
        int d = 0;
        Long parent = topic.getParentId();
        while (parent != null && d < 10) {
            Long p = parent;
            parent = topics.stream().filter(t -> p.equals(t.getId()))
                           .map(Topic::getParentId).findFirst().orElse(null);
            d++;
        }
        return d;
    }

    private ListCell<Subject> subjectCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Subject item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Subject" : item.getCode() + "  " + item.getName());
            }
        };
    }

    // ------------------------------------------------------------------
    // data
    // ------------------------------------------------------------------

    private void loadSubjects() {
        try {
            List<Subject> subjects = subjectDao.findAll();
            subject.setItems(FXCollections.observableArrayList(subjects));
            if (!subjects.isEmpty()) {
                subject.setValue(subjects.get(0));
            }
        } catch (ExamProException e) {
            AlertFactory.error("Could not load subjects", e);
        }
    }

    private void onSubjectChosen(Subject chosen) {
        if (chosen == null) {
            return;
        }
        try {
            topics = topicDao.findBySubject(chosen.getId());
            List<Topic> withSentinel = new ArrayList<>();
            withSentinel.add(ALL_TOPICS);
            withSentinel.addAll(topics);
            topicFilter.setItems(FXCollections.observableArrayList(withSentinel));
            topicFilter.setValue(ALL_TOPICS);

            // The editor only offers leaf topics; hanging a question off a
            // root topic makes blueprint rules ambiguous later.
            editor.setTopics(topics.stream().filter(t -> !t.isRoot()).toList());
            reload();
        } catch (ExamProException e) {
            AlertFactory.error("Could not load topics", e);
        }
    }

    private void reload() {
        Subject chosen = subject.getValue();
        if (chosen == null) {
            return;
        }
        try {
            // Always the whole subject; topic, type, difficulty and search are
            // narrowed in memory, so a root topic naturally includes its subtree.
            all.setAll(service.listBySubject(chosen.getId()));
            applyFilter();
        } catch (ExamProException e) {
            all.clear();
            status.setText("Could not load questions.");
            AlertFactory.error("Could not load questions", e);
        }
    }

    private void applyFilter() {
        Topic topic = topicFilter.getValue();
        Set<Long> topicIds = topic == null || topic == ALL_TOPICS ? null : subtree(topic);
        QuestionType type = typeFilter.getValue();
        Difficulty level = difficultyFilter.getValue();
        String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase();

        rows.setPredicate(q ->
            (topicIds == null || topicIds.contains(q.getTopicId()))
            && (type == null || q.getType() == type)
            && (level == null || q.getDifficulty() == level)
            && (needle.isEmpty()
                || q.getStem().toLowerCase().contains(needle)
                || (q.getTopicName() != null && q.getTopicName().toLowerCase().contains(needle))
                || q.getTags().stream().anyMatch(t -> t.contains(needle))));
        status.setText(rows.size() + " of " + all.size() + " active question(s).");
    }

    /** The topic and every descendant. */
    private Set<Long> subtree(Topic root) {
        Set<Long> ids = new HashSet<>();
        ids.add(root.getId());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Topic t : topics) {
                if (t.getParentId() != null && ids.contains(t.getParentId()) && ids.add(t.getId())) {
                    grew = true;
                }
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // import and export
    // ------------------------------------------------------------------

    private void importQuestions() {
        Subject chosen = subject.getValue();
        if (chosen == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import questions into " + chosen.getCode());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV or JSON", "*.csv", "*.json"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Map<String, Long> topicIds = new HashMap<>();
            topics.stream().filter(t -> !t.isRoot())
                  .forEach(t -> topicIds.put(t.getName().toLowerCase(), t.getId()));
            QuestionBankService.ImportResult result = service.importFile(file.toPath(), topicIds);
            reload();

            String text = "Imported " + result.imported() + " question(s); "
                          + result.errors().size() + " rejected.";
            if (!result.errors().isEmpty()) {
                text += "\n\n" + String.join("\n", result.errors().stream().limit(12).toList());
                if (result.errors().size() > 12) {
                    text += "\n… and " + (result.errors().size() - 12) + " more.";
                }
            }
            new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK).showAndWait();
        } catch (ExamProException e) {
            AlertFactory.error("Could not import", e);
        }
    }

    /** Exports what the filters currently show. */
    private void exportQuestions() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + rows.size() + " question(s)");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("CSV", "*.csv"),
            new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            boolean json = file.getName().toLowerCase().endsWith(".json")
                || (!file.getName().toLowerCase().endsWith(".csv")
                    && chooser.getSelectedExtensionFilter().getDescription().equals("JSON"));
            Exporter<Question> exporter = json ? new JsonBankExporter() : new CsvQuestionExporter();
            exporter.export(new ArrayList<>(rows), file.toPath());
            status.setText("Exported " + rows.size() + " question(s) to " + file.getName() + ".");
        } catch (ExamProException e) {
            AlertFactory.error("Could not export", e);
        }
    }

    private void retireSelected() {
        Question chosen = table.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            status.setText("Select a question to retire.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Retire this question? It stays in the database and on any paper that "
            + "already uses it, but it will not be offered for new exams.",
            ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(chosen.shortStem());
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }

        try {
            service.deactivate(chosen.getId());
            reload();
            editor.startNew(chosen.getType());
            status.setText("Retired question " + chosen.getId() + ".");
        } catch (ExamProException e) {
            AlertFactory.error("Could not retire the question", e);
        }
    }

    /** Stands in for "no topic filter" in the combo, so the list has no null item. */
    private static Topic allTopicsSentinel() {
        Topic all = new Topic();
        all.setName("All topics");
        return all;
    }
}
