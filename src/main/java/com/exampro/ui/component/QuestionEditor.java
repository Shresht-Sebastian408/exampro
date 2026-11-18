package com.exampro.ui.component;

import com.exampro.exception.ExamProException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.Topic;
import com.exampro.model.enums.Difficulty;
import com.exampro.model.enums.QuestionType;
import com.exampro.model.question.NumericQuestion;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.model.question.ShortAnswerQuestion;
import com.exampro.model.question.TrueFalseQuestion;
import com.exampro.service.QuestionBankService;
import com.exampro.ui.util.AlertFactory;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The question editor: type selector, morphing form, live preview.
 *
 * The type selector morphs the form rather than swapping it: the common
 * fields stay put and only the type-specific pane crossfades, so the eye
 * does not lose its place mid-edit. The preview is rebuilt from the form on
 * every keystroke and flashes once, which is cheap for a form this size and
 * means the preview can never disagree with the form.
 *
 * Type is locked once a question exists. Changing an existing question's
 * type would mean migrating its options and answer key to a different
 * subclass, and the honest answer is to create a new question instead.
 */
public class QuestionEditor extends VBox {

    /** One option row: correctness control, text, reorder and remove. */
    private final class OptionRow extends HBox {
        private final BooleanProperty correct = new SimpleBooleanProperty(false);
        private final TextField text = new TextField();

        OptionRow(QuestionOption option, boolean singleCorrect, ToggleGroup group) {
            super(8);
            setAlignment(Pos.CENTER_LEFT);

            Node selector;
            if (singleCorrect) {
                RadioButton radio = new RadioButton();
                radio.setToggleGroup(group);
                radio.selectedProperty().bindBidirectional(correct);
                selector = radio;
            } else {
                CheckBox check = new CheckBox();
                check.selectedProperty().bindBidirectional(correct);
                selector = check;
            }
            correct.set(option.isCorrect());
            correct.addListener((o, a, b) -> refreshPreview());

            text.setText(option.getText());
            text.setPromptText("Option text");
            text.getStyleClass().add("field");
            HBox.setHgrow(text, Priority.ALWAYS);
            text.textProperty().addListener((o, a, b) -> refreshPreview());

            Button up = iconButton("↑", () -> move(this, -1));
            Button down = iconButton("↓", () -> move(this, 1));
            Button remove = iconButton("✕", () -> removeRow(this));

            boolean fixed = typeOf() == QuestionType.TRUE_FALSE;
            text.setEditable(!fixed);
            up.setDisable(fixed);
            down.setDisable(fixed);
            remove.setDisable(fixed);

            getChildren().addAll(selector, text, up, down, remove);
        }

        QuestionOption toOption() {
            return new QuestionOption(text.getText(), correct.get(), 0);
        }
    }

    private final QuestionBankService service;
    private final Consumer<Question> onSaved;

    private final ToggleGroup typeGroup = new ToggleGroup();
    private final ComboBox<Topic> topic = new ComboBox<>();
    private final ComboBox<Difficulty> difficulty = new ComboBox<>();
    private final TextField marks = new TextField("1");
    private final TextField negativeMarks = new TextField("0");
    private final TextArea stem = new TextArea();
    private final TextArea explanation = new TextArea();
    private final TextField tags = new TextField();

    private final VBox typeSpecific = new VBox(8);
    private final VBox optionRows = new VBox(6);
    private final ToggleGroup correctGroup = new ToggleGroup();
    private final TextField numericAnswer = new TextField();
    private final TextField numericTolerance = new TextField("0");
    private final TextArea keywords = new TextArea();

    private final VBox preview = new VBox(8);
    private final Label status = new Label();

    private Question loaded;
    /** True while the code, not the user, is moving the type selector. */
    private boolean selectingProgrammatically;

    public QuestionEditor(QuestionBankService service, Consumer<Question> onSaved) {
        this.service = service;
        this.onSaved = onSaved;

        setSpacing(12);
        setPadding(new Insets(4, 0, 0, 0));
        getStyleClass().add("editor-pane");

        getChildren().addAll(buildTypeSelector(), buildCommonFields(), typeSpecific,
                             buildPreviewPane(), buildActions(), status);
        status.getStyleClass().add("status-label");

        // Registered once here, not inside the block builders: those run on
        // every morph and would stack a duplicate listener each time.
        numericAnswer.textProperty().addListener((o, a, b) -> refreshPreview());
        numericTolerance.textProperty().addListener((o, a, b) -> refreshPreview());
        keywords.textProperty().addListener((o, a, b) -> refreshPreview());

        startNew(QuestionType.MCQ);
    }

    // ------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------

    public void setTopics(List<Topic> topics) {
        topic.setItems(FXCollections.observableArrayList(topics));
    }

    /** Fresh question of the given type, nothing loaded. */
    public void startNew(QuestionType type) {
        loaded = null;
        loadOptions(QuestionBankService.blank(type).getOptions(), type);
        selectType(type);
        topic.getSelectionModel().clearSelection();
        difficulty.setValue(Difficulty.MEDIUM);
        marks.setText("1");
        negativeMarks.setText("0");
        stem.clear();
        explanation.clear();
        tags.clear();
        numericAnswer.clear();
        numericTolerance.setText("0");
        keywords.clear();
        setTypeLocked(false);
        status.setText("New question.");
        refreshPreview();
    }

    /** Loads an existing question for editing. */
    public void load(Question question) {
        loaded = question;
        loadOptions(question.getOptions(), question.getType());
        selectType(question.getType());
        topic.getItems().stream()
             .filter(t -> t.getId().equals(question.getTopicId()))
             .findFirst()
             .ifPresent(topic::setValue);
        difficulty.setValue(question.getDifficulty());
        marks.setText(trimNumber(question.getMarks()));
        negativeMarks.setText(trimNumber(question.getNegativeMarks()));
        stem.setText(question.getStem());
        explanation.setText(question.getExplanation() == null ? "" : question.getExplanation());
        tags.setText(String.join(", ", question.getTags()));

        if (question instanceof NumericQuestion n) {
            numericAnswer.setText(n.getAnswer() == null ? "" : trimNumber(n.getAnswer()));
            numericTolerance.setText(trimNumber(n.getTolerance()));
        }
        if (question instanceof ShortAnswerQuestion s) {
            keywords.setText(String.join("\n", s.getKeywords()));
        }
        setTypeLocked(true);
        status.setText("Editing question " + question.getId() + ".");
        refreshPreview();
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private Node buildTypeSelector() {
        // wraps instead of squeezing five buttons into one row and truncating their labels
        javafx.scene.layout.FlowPane bar = new javafx.scene.layout.FlowPane(6, 6);
        bar.getStyleClass().add("type-selector");
        for (QuestionType type : QuestionType.values()) {
            ToggleButton button = new ToggleButton(label(type));
            button.setUserData(type);
            button.setToggleGroup(typeGroup);
            button.getStyleClass().add("type-button");
            bar.getChildren().add(button);
        }
        // A click that would clear the selection is swallowed: no type at all
        // is not a state this form can render.
        typeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                typeGroup.selectToggle(old);
                return;
            }
            // A programmatic selection builds the pane synchronously below.
            // Letting it also start a morph meant two panes competing for the
            // same option rows, and the animated one winning with the rows
            // already re-parented out of it: a visibly empty options list.
            if (!selectingProgrammatically) {
                morphTo((QuestionType) now.getUserData());
            }
        });
        return bar;
    }

    private Node buildCommonFields() {
        topic.setPromptText("Topic");
        topic.setMaxWidth(Double.MAX_VALUE);
        topic.valueProperty().addListener((o, a, b) -> refreshPreview());

        difficulty.setItems(FXCollections.observableArrayList(Difficulty.values()));
        difficulty.setValue(Difficulty.MEDIUM);
        difficulty.valueProperty().addListener((o, a, b) -> refreshPreview());

        marks.setPrefWidth(70);
        marks.getStyleClass().add("field");
        marks.textProperty().addListener((o, a, b) -> refreshPreview());
        negativeMarks.setPrefWidth(70);
        negativeMarks.getStyleClass().add("field");

        stem.setPromptText("Question text");
        stem.setPrefRowCount(3);
        stem.setWrapText(true);
        stem.getStyleClass().add("field");
        stem.textProperty().addListener((o, a, b) -> refreshPreview());

        explanation.setPromptText("Explanation shown after grading (optional)");
        explanation.setPrefRowCount(2);
        explanation.setWrapText(true);
        explanation.getStyleClass().add("field");

        HBox row = new HBox(8,
            labelled("Topic", topic, Priority.ALWAYS),
            labelled("Difficulty", difficulty, Priority.NEVER),
            labelled("Marks", marks, Priority.NEVER),
            labelled("Negative", negativeMarks, Priority.NEVER));
        tags.setPromptText("Tags, comma separated (optional)");
        tags.getStyleClass().add("field");
        return new VBox(8, row, stem, explanation, tags);
    }

    private Node buildPreviewPane() {
        preview.getStyleClass().add("preview-pane");
        preview.setPadding(new Insets(14));

        Label heading = new Label("Live preview");
        heading.getStyleClass().add("section-label");
        VBox box = new VBox(6, heading, preview);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node buildActions() {
        Button save = new Button("Save question");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> save());

        Button newQuestion = new Button("New");
        newQuestion.setOnAction(e -> startNew(typeOf()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, save, newQuestion, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node labelled(String text, Node field, Priority grow) {
        Label caption = new Label(text);
        caption.getStyleClass().add("section-label");
        VBox box = new VBox(4, caption, field);
        HBox.setHgrow(box, grow);
        return box;
    }

    // ------------------------------------------------------------------
    // the morph
    // ------------------------------------------------------------------

    private void selectType(QuestionType type) {
        selectingProgrammatically = true;
        try {
            for (javafx.scene.control.Toggle toggle : typeGroup.getToggles()) {
                if (toggle.getUserData() == type) {
                    typeGroup.selectToggle(toggle);
                }
            }
        } finally {
            selectingProgrammatically = false;
        }
        typeSpecific.setOpacity(1);
        typeSpecific.getChildren().setAll(fieldsFor(type));
    }

    private QuestionType typeOf() {
        javafx.scene.control.Toggle selected = typeGroup.getSelectedToggle();
        return selected == null ? QuestionType.MCQ : (QuestionType) selected.getUserData();
    }

    /**
     * Crossfade rather than snap: the outgoing fields fade out, the incoming
     * fields fade in behind them (plan 10.3).
     */
    private void morphTo(QuestionType type) {
        if (loaded == null) {
            loadOptions(QuestionBankService.blank(type).getOptions(), type);
        }
        List<Node> incoming = fieldsFor(type);

        FadeTransition out = new FadeTransition(Duration.millis(120), typeSpecific);
        out.setFromValue(typeSpecific.getOpacity());
        out.setToValue(0);
        out.setOnFinished(e -> {
            typeSpecific.getChildren().setAll(incoming);
            FadeTransition in = new FadeTransition(Duration.millis(180), typeSpecific);
            in.setFromValue(0);
            in.setToValue(1);
            in.play();
            refreshPreview();
        });
        new ParallelTransition(out).play();
    }

    private List<Node> fieldsFor(QuestionType type) {
        return switch (type) {
            case MCQ, MSQ, TRUE_FALSE -> List.of(optionsBlock(type));
            case NUMERIC -> List.of(numericBlock());
            case SHORT_ANSWER -> List.of(keywordBlock());
        };
    }

    private Node optionsBlock(QuestionType type) {
        Label caption = new Label(type == QuestionType.MSQ
                                  ? "Options, tick every correct one"
                                  : "Options, select the correct one");
        caption.getStyleClass().add("section-label");

        Button add = new Button("Add option");
        add.setDisable(type == QuestionType.TRUE_FALSE);
        add.setOnAction(e -> {
            optionRows.getChildren().add(
                new OptionRow(new QuestionOption("", false, 0), single(type), correctGroup));
            refreshPreview();
        });

        return new VBox(8, caption, optionRows, add);
    }

    private Node numericBlock() {
        numericAnswer.setPromptText("Answer");
        numericAnswer.getStyleClass().add("field");
        numericTolerance.setPromptText("Tolerance");
        numericTolerance.getStyleClass().add("field");

        return new HBox(8,
            labelled("Correct answer", numericAnswer, Priority.ALWAYS),
            labelled("Tolerance (absolute)", numericTolerance, Priority.ALWAYS));
    }

    private Node keywordBlock() {
        keywords.setPromptText("One expected keyword per line");
        keywords.setPrefRowCount(4);
        keywords.getStyleClass().add("field");
        return labelled("Expected keywords", keywords, Priority.ALWAYS);
    }

    private void loadOptions(List<QuestionOption> options, QuestionType type) {
        optionRows.getChildren().clear();
        // Discarded radios stay in a ToggleGroup unless cleared, which keeps
        // a removed row as the group's selection and leaks every rebuild.
        correctGroup.getToggles().clear();
        for (QuestionOption option : options) {
            optionRows.getChildren().add(new OptionRow(option, single(type), correctGroup));
        }
    }

    private boolean single(QuestionType type) {
        return type != QuestionType.MSQ;
    }

    private void move(OptionRow row, int delta) {
        List<Node> rows = optionRows.getChildren();
        int from = rows.indexOf(row);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= rows.size()) {
            return;
        }
        Node neighbour = rows.get(to);
        double step = row.getHeight() + optionRows.getSpacing();
        rows.remove(from);
        rows.add(to, row);
        slideFrom(row, (from - to) * step);
        slideFrom(neighbour, (to - from) * step);
        refreshPreview();
    }

    /** Starts the node at its old visual position and eases it to where layout put it. */
    private void slideFrom(Node node, double offsetY) {
        node.setTranslateY(offsetY);
        javafx.animation.TranslateTransition t =
            new javafx.animation.TranslateTransition(Duration.millis(180), node);
        t.setToY(0);
        t.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        t.play();
    }

    private void removeRow(OptionRow row) {
        if (optionRows.getChildren().size() <= 2) {
            status.setText("A choice question needs at least two options.");
            return;
        }
        optionRows.getChildren().remove(row);
        refreshPreview();
    }

    private void setTypeLocked(boolean locked) {
        typeGroup.getToggles().forEach(t -> ((ToggleButton) t).setDisable(
            locked && t.getUserData() != typeOf()));
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    private void refreshPreview() {
        QuestionType type = typeOf();
        preview.getChildren().clear();

        Label marksChip = new Label(label(type) + "  ·  " + difficulty.getValue()
                                    + "  ·  " + marks.getText() + " marks");
        marksChip.getStyleClass().add("role-chip");

        Label stemLabel = new Label(stem.getText().isBlank()
                                    ? "The question text will appear here."
                                    : stem.getText());
        stemLabel.setWrapText(true);
        stemLabel.getStyleClass().add("preview-stem");

        preview.getChildren().addAll(marksChip, stemLabel);

        switch (type) {
            case MCQ, MSQ, TRUE_FALSE -> {
                for (Node node : optionRows.getChildren()) {
                    OptionRow row = (OptionRow) node;
                    String mark = row.correct.get() ? "● " : "○ ";
                    Label option = new Label(mark + row.text.getText());
                    option.setWrapText(true);
                    option.getStyleClass().add(row.correct.get()
                                               ? "preview-option-correct" : "preview-option");
                    preview.getChildren().add(option);
                }
            }
            case NUMERIC -> {
                String answer = numericAnswer.getText().isBlank() ? "?" : numericAnswer.getText();
                String tolerance = numericTolerance.getText().isBlank()
                                   ? "0" : numericTolerance.getText();
                Label line = new Label("Accepts " + answer + " ± " + tolerance);
                line.getStyleClass().add("preview-option-correct");
                preview.getChildren().add(line);
            }
            case SHORT_ANSWER -> {
                Label line = new Label(keywordLines().isEmpty()
                                       ? "No keywords yet"
                                       : "Keywords: " + String.join(", ", keywordLines()));
                line.setWrapText(true);
                line.getStyleClass().add("preview-option");
                preview.getChildren().add(line);
            }
        }

        // The highlight pulse from plan 10.3, as one fade on the pane. A
        // per-element diff would need the previous render kept around for a
        // flash nobody is comparing frame by frame.
        FadeTransition flash = new FadeTransition(Duration.millis(160), preview);
        flash.setFromValue(0.55);
        flash.setToValue(1);
        flash.play();
    }

    // ------------------------------------------------------------------
    // save
    // ------------------------------------------------------------------

    private void save() {
        try {
            Question question = collect();
            long id = service.save(question);
            question.setId(id);
            status.setText("Saved question " + id + ".");
            loaded = question;
            setTypeLocked(true);
            onSaved.accept(question);
        } catch (ValidationException e) {
            // Expected: the editor is where invalid questions are caught.
            status.setText(e.getMessage());
        } catch (ExamProException e) {
            AlertFactory.error("Could not save the question", e);
        }
    }

    /** Form state as a model object, carrying over what only the row knows. */
    private Question collect() {
        QuestionType type = typeOf();
        Question question = loaded != null && loaded.getType() == type
                            ? loaded
                            : QuestionBankService.blank(type);

        question.setTopicId(topic.getValue() == null ? null : topic.getValue().getId());
        question.setDifficulty(difficulty.getValue());
        question.setMarks(parse(marks.getText(), "Marks"));
        question.setNegativeMarks(parse(negativeMarks.getText(), "Negative marks"));
        question.setStem(stem.getText());
        question.setExplanation(explanation.getText().isBlank() ? null : explanation.getText());
        question.getTags().clear();
        for (String tag : tags.getText().split(",")) {
            question.getTags().add(tag); // service trims, lower-cases, de-dupes
        }

        switch (type) {
            case MCQ, MSQ, TRUE_FALSE -> {
                List<QuestionOption> collected = new ArrayList<>();
                for (Node node : optionRows.getChildren()) {
                    collected.add(((OptionRow) node).toOption());
                }
                question.getOptions().clear();
                question.getOptions().addAll(collected);
            }
            case NUMERIC -> {
                NumericQuestion n = (NumericQuestion) question;
                n.setAnswer(numericAnswer.getText().isBlank()
                            ? null : parse(numericAnswer.getText(), "Answer"));
                n.setTolerance(parse(numericTolerance.getText(), "Tolerance"));
            }
            case SHORT_ANSWER -> {
                ShortAnswerQuestion s = (ShortAnswerQuestion) question;
                s.getKeywords().clear();
                s.getKeywords().addAll(keywordLines());
            }
        }
        return question;
    }

    private List<String> keywordLines() {
        return keywords.getText().lines()
                       .map(String::trim)
                       .filter(line -> !line.isEmpty())
                       .toList();
    }

    private double parse(String text, String field) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a number.");
        }
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value)
               ? String.valueOf((long) value)
               : String.valueOf(value);
    }

    private static String label(QuestionType type) {
        return switch (type) {
            case MCQ -> "MCQ";
            case MSQ -> "MSQ";
            case TRUE_FALSE -> "True/False";
            case NUMERIC -> "Numeric";
            case SHORT_ANSWER -> "Short answer";
        };
    }

    private Button iconButton(String glyph, Runnable action) {
        Button button = new Button(glyph);
        button.getStyleClass().add("icon-button");
        button.setOnAction(e -> action.run());
        return button;
    }

    /** Wraps the editor so a long form scrolls instead of squashing. */
    public ScrollPane inScrollPane() {
        ScrollPane scroll = new ScrollPane(this);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("editor-scroll");
        return scroll;
    }
}
