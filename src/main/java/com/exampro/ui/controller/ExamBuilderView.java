package com.exampro.ui.controller;

import com.exampro.dao.ExamDao;
import com.exampro.dao.SubjectDao;
import com.exampro.dao.TopicDao;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.dao.impl.SubjectDaoImpl;
import com.exampro.dao.impl.TopicDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.exception.InsufficientQuestionsException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.Blueprint;
import com.exampro.model.entity.BlueprintRule;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.Subject;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.Question;
import com.exampro.service.BlueprintService;
import com.exampro.service.PaperGenerationService;
import com.exampro.ui.util.AlertFactory;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

/**
 * Exams and their blueprints. Pick a subject, pick or create an exam, then
 * edit its rules: each rule is one slice of the bank (topic, difficulty,
 * type) and a count. The Available column shows live supply against each
 * rule, and Save refuses a blueprint the bank cannot satisfy.
 */
public class ExamBuilderView extends BorderPane {

    private final SubjectDao subjectDao = new SubjectDaoImpl();
    private final TopicDao topicDao = new TopicDaoImpl();
    private final ExamDao examDao = new ExamDaoImpl();
    private final BlueprintService blueprints = new BlueprintService();
    private final PaperGenerationService generator = new PaperGenerationService();

    private final ComboBox<Subject> subject = new ComboBox<>();
    private final ListView<Exam> examList = new ListView<>();
    private final Label examHeading = new Label("Select or create an exam");
    private final TextField duration = new TextField("60");
    private final ComboBox<String> strategy = new ComboBox<>();

    private final ObservableList<BlueprintRule> rules = FXCollections.observableArrayList();
    private final TableView<BlueprintRule> table = new TableView<>(rules);
    private final ComboBox<Topic> ruleTopic = new ComboBox<>();
    private final ComboBox<Difficulty> ruleDifficulty = new ComboBox<>();
    private final ComboBox<QuestionType> ruleType = new ComboBox<>();
    private final TextField ruleCount = new TextField("1");
    private final javafx.scene.control.CheckBox published =
        new javafx.scene.control.CheckBox("Published (students can start it)");
    private final Label status = new Label();

    private Exam current;

    public ExamBuilderView() {
        setTop(buildToolbar());
        SplitPane split = new SplitPane(buildExamPane(), buildBlueprintPane());
        split.setDividerPositions(0.28);
        setCenter(split);
        status.getStyleClass().add("status-label");
        BorderPane.setMargin(status, new Insets(8, 0, 0, 0));
        setBottom(status);
        setBlueprintEnabled(false);
        loadSubjects();
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private HBox buildToolbar() {
        Label heading = new Label("Exams and blueprints");
        heading.getStyleClass().add("heading");
        subject.setPrefWidth(240);
        subject.setButtonCell(subjectCell());
        subject.setCellFactory(l -> subjectCell());
        subject.valueProperty().addListener((o, a, b) -> onSubjectChosen(b));

        HBox bar = new HBox(10, heading, subject);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-row");
        return bar;
    }

    private VBox buildExamPane() {
        Button create = new Button("New exam");
        create.getStyleClass().add("primary-button");
        create.setOnAction(e -> createExam());

        examList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> openExam(b));
        VBox.setVgrow(examList, Priority.ALWAYS);

        VBox box = new VBox(8, create, examList);
        box.setPadding(new Insets(0, 8, 0, 0));
        return box;
    }

    private VBox buildBlueprintPane() {
        examHeading.getStyleClass().add("heading");

        duration.setPrefWidth(70);
        duration.getStyleClass().add("field");
        strategy.setItems(FXCollections.observableArrayList(PaperGenerationService.strategyNames()));

        HBox settings = new HBox(10, labelled("Duration (min)", duration),
                                 labelled("Paper generation", strategy),
                                 labelled("Visibility", published));

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No rules yet. Add one below."));
        table.getColumns().addAll(
            column("Topic", BlueprintRule::getTopicName),
            column("Difficulty", r -> r.getDifficulty().name()),
            column("Type", r -> r.getType().name()),
            column("Count", r -> String.valueOf(r.getCount())),
            column("Available", this::availability));
        VBox.setVgrow(table, Priority.ALWAYS);

        ruleTopic.setPromptText("Topic");
        ruleTopic.setPrefWidth(190);
        ruleDifficulty.setItems(FXCollections.observableArrayList(Difficulty.values()));
        ruleDifficulty.setValue(Difficulty.MEDIUM);
        ruleType.setItems(FXCollections.observableArrayList(QuestionType.values()));
        ruleType.setValue(QuestionType.MCQ);
        ruleCount.setPrefWidth(60);
        ruleCount.setMinWidth(60);
        ruleCount.getStyleClass().add("field");

        Button add = new Button("Add rule");
        add.setOnAction(e -> addRule());
        Button remove = new Button("Remove selected");
        remove.setOnAction(e -> rules.remove(table.getSelectionModel().getSelectedItem()));
        // fixed-size controls keep their labels; only the topic box gives way when space is tight
        for (Button b : new Button[] {add, remove}) b.setMinWidth(Region.USE_PREF_SIZE);
        ruleDifficulty.setMinWidth(110);
        ruleType.setMinWidth(100);
        ruleTopic.setMinWidth(90);
        HBox ruleRow = new HBox(8, ruleTopic, ruleDifficulty, ruleType, ruleCount, add, remove);
        ruleRow.setAlignment(Pos.CENTER_LEFT);

        Button save = new Button("Save blueprint");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> saveBlueprint());
        Button preview = new Button("Preview a paper");
        preview.setOnAction(e -> previewPaper());
        Button exportPaper = new Button("Export paper (PDF)");
        exportPaper.setOnAction(e -> exportPaper());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, save, preview, exportPaper, spacer);

        VBox box = new VBox(10, examHeading, settings, table, ruleRow, actions);
        box.setPadding(new Insets(0, 0, 0, 8));
        return box;
    }

    private VBox labelled(String text, javafx.scene.Node field) {
        Label caption = new Label(text);
        caption.getStyleClass().add("section-label");
        return new VBox(4, caption, field);
    }

    private TableColumn<BlueprintRule, String> column(String title, Function<BlueprintRule, String> value) {
        TableColumn<BlueprintRule, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return col;
    }

    private javafx.scene.control.ListCell<Subject> subjectCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Subject item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Subject" : item.getCode() + "  " + item.getName());
            }
        };
    }

    /** "12" or "12 (short by 3)" so a doomed rule is visible before Save. */
    private String availability(BlueprintRule r) {
        try {
            int have = blueprints.available(r.getTopicId(), r.getDifficulty(), r.getType());
            return have >= r.getCount() ? String.valueOf(have) : have + "  (short)";
        } catch (ExamProException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // behaviour
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
            // Leaf topics only, same rule as the question editor.
            ruleTopic.setItems(FXCollections.observableArrayList(
                topicDao.findBySubject(chosen.getId()).stream().filter(t -> !t.isRoot()).toList()));
            examList.setItems(FXCollections.observableArrayList(examDao.findBySubject(chosen.getId())));
            current = null;
            rules.clear();
            examHeading.setText("Select or create an exam");
            setBlueprintEnabled(false);
        } catch (ExamProException e) {
            AlertFactory.error("Could not load exams", e);
        }
    }

    private void createExam() {
        Subject chosen = subject.getValue();
        if (chosen == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Exam title");
        dialog.showAndWait().map(String::trim).filter(t -> !t.isEmpty()).ifPresent(title -> {
            try {
                Exam exam = new Exam();
                exam.setSubjectId(chosen.getId());
                exam.setTitle(title);
                examDao.insert(exam);
                examList.getItems().add(0, exam);
                examList.getSelectionModel().select(exam);
            } catch (ExamProException e) {
                AlertFactory.error("Could not create the exam", e);
            }
        });
    }

    private void openExam(Exam exam) {
        current = exam;
        rules.clear();
        if (exam == null) {
            setBlueprintEnabled(false);
            return;
        }
        try {
            examHeading.setText(exam.getTitle());
            duration.setText(String.valueOf(exam.getDurationMinutes()));
            strategy.setValue(exam.getGenerationStrategy());
            published.setSelected(exam.isPublished());
            blueprints.findByExam(exam.getId()).ifPresent(b -> rules.addAll(b.getRules()));
            setBlueprintEnabled(true);
            status.setText(rules.isEmpty() ? "No blueprint saved for this exam yet."
                                           : "Blueprint totals " + total() + " question(s).");
        } catch (ExamProException e) {
            AlertFactory.error("Could not open the exam", e);
        }
    }

    private void addRule() {
        Topic topic = ruleTopic.getValue();
        if (topic == null) {
            status.setText("Pick a topic for the rule.");
            return;
        }
        try {
            int count = Integer.parseInt(ruleCount.getText().trim());
            if (count <= 0) {
                throw new NumberFormatException();
            }
            rules.add(new BlueprintRule(topic.getId(), topic.getName(),
                                        ruleDifficulty.getValue(), ruleType.getValue(), count));
            status.setText("Blueprint totals " + total() + " question(s). Not saved yet.");
        } catch (NumberFormatException e) {
            status.setText("Count must be a whole number of at least 1.");
        }
    }

    private int total() {
        return rules.stream().mapToInt(BlueprintRule::getCount).sum();
    }

    private void saveBlueprint() {
        if (current == null) {
            return;
        }
        try {
            current.setDurationMinutes(Integer.parseInt(duration.getText().trim()));
            current.setGenerationStrategy(strategy.getValue());
            current.setPublished(published.isSelected());
            Blueprint bp = new Blueprint();
            bp.setExamId(current.getId());
            bp.setName(current.getTitle() + " blueprint");
            bp.getRules().addAll(rules);

            blueprints.save(bp);      // validates capacity first; throws before anything is written
            examDao.update(current);
            table.refresh();
            status.setText("Saved: " + total() + " question(s), " + strategy.getValue() + " strategy.");
        } catch (NumberFormatException e) {
            status.setText("Duration must be a whole number of minutes.");
        } catch (InsufficientQuestionsException | ValidationException e) {
            table.refresh();
            new Alert(Alert.AlertType.WARNING, e.getMessage(), ButtonType.OK).showAndWait();
            status.setText("Not saved.");
        } catch (ExamProException e) {
            AlertFactory.error("Could not save the blueprint", e);
        }
    }

    /** Generates a paper from the saved blueprint, for no particular student. */
    private void previewPaper() {
        if (current == null) {
            return;
        }
        try {
            current.setGenerationStrategy(strategy.getValue());
            List<Question> paper = generator.generate(current, null);
            StringBuilder text = new StringBuilder();
            int n = 1;
            for (Question q : paper) {
                text.append(n++).append(". [").append(q.getDifficulty()).append(" ").append(q.getType())
                    .append("] ").append(q.shortStem()).append('\n');
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION, text.toString(), ButtonType.OK);
            alert.setHeaderText(paper.size() + " question(s), " + strategy.getValue()
                                + " (uses the saved blueprint)");
            alert.getDialogPane().setPrefWidth(640);
            alert.showAndWait();
        } catch (ExamProException e) {
            AlertFactory.error("Could not generate a paper", e);
        }
    }

    /** A sample paper from the saved blueprint, as a printable PDF with or without the answer key. */
    private void exportPaper() {
        if (current == null) {
            return;
        }
        ButtonType paperOnly = new ButtonType("Paper only");
        ButtonType withKey = new ButtonType("Paper and answer key");
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, "Export a sample paper from the saved blueprint.",
                              paperOnly, withKey, ButtonType.CANCEL);
        ask.setHeaderText("Export question paper");
        java.util.Optional<ButtonType> choice = ask.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setInitialFileName(current.getTitle().replaceAll("[^A-Za-z0-9 _-]", "") + " paper.pdf");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            current.setGenerationStrategy(strategy.getValue());
            new com.exampro.export.PdfPaperExporter(current.getTitle(), current.getDurationMinutes(),
                                                    choice.get() == withKey)
                .export(generator.generate(current, null), file.toPath());
            status.setText("Saved " + file.getName());
        } catch (ExamProException e) {
            AlertFactory.error("Could not export the paper", e);
        }
    }

    private void setBlueprintEnabled(boolean on) {
        duration.setDisable(!on);
        strategy.setDisable(!on);
        published.setDisable(!on);
        table.setDisable(!on);
        ruleTopic.setDisable(!on);
        ruleDifficulty.setDisable(!on);
        ruleType.setDisable(!on);
        ruleCount.setDisable(!on);
    }
}
