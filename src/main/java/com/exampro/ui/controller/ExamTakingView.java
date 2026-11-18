package com.exampro.ui.controller;

import com.exampro.exception.AttemptAlreadySubmittedException;
import com.exampro.config.AppConfig;
import com.exampro.exception.ExamProException;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.Severity;
import com.exampro.proctor.ProctorEngine;
import com.exampro.proctor.ProctorSignals;
import com.exampro.model.entity.Response;
import com.exampro.model.question.Question;
import com.exampro.model.question.QuestionOption;
import com.exampro.service.ExamDeliveryService;
import com.exampro.ui.util.AlertFactory;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import com.exampro.ui.animation.PulseEffect;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.util.Duration;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The exam screen: palette on the left, one question in the middle, timer
 * and autosave state on top. Functional only this week, no animation.
 *
 * Threading: all UI and all answer state live on the FX thread. Two
 * scheduled ticks (clock, autosave) hop onto it with runLater; the only
 * work that leaves it is the database write for autosave, which receives
 * copies of the responses so it never reads state the UI is changing.
 *
 * Every answer change is also written synchronously to the encrypted
 * recovery cache, so a crash or a database outage between autosaves loses
 * nothing.
 */
public class ExamTakingView extends BorderPane {

    private static final int AUTOSAVE_SECONDS = 4;
    private static final int WARN_AFTER_FAILURES = 3;

    private final ExamDeliveryService service;
    private final ExamDeliveryService.Sitting sitting;
    private final Runnable onFinished;

    private final List<Question> paper;
    private final Map<Long, Response> responses;
    private final Set<Long> dirty = new java.util.HashSet<>();
    private final List<Button> paletteButtons = new ArrayList<>();

    private final Label timer = new Label();
    private long deadlineNanos;
    // timer ring: a circle whose dash offset eats the stroke as time runs out
    private static final double RING_RADIUS = 28;
    private static final double RING_LENGTH = 2 * Math.PI * RING_RADIUS;
    private final Circle ring = new Circle(RING_RADIUS);
    private final StackPane timerBox = new StackPane();
    private ScaleTransition lastMinutePulse;
    private final Label saveState = new Label("● saved");
    private final VBox questionPane = new VBox(12);
    private final Button flag = new Button("Flag for review");

    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "exam-clock");
        t.setDaemon(true);
        return t;
    });
    private int index;
    private int saveFailures;

    // proctoring
    private final ProctorSignals signals = new ProctorSignals();
    private final ProctorEngine engine;
    private final Label banner = new Label();
    private long shownAtMillis;
    private boolean shownQuestionWasBlank;

    public ExamTakingView(ExamDeliveryService service, ExamDeliveryService.Sitting sitting,
                          Runnable onFinished) {
        this.service = service;
        this.sitting = sitting;
        this.onFinished = onFinished;
        this.paper = sitting.paper();
        this.responses = new LinkedHashMap<>(sitting.responses());

        this.engine = service.proctoring().engineFor();
        engine.addListener(this::showBanner);

        banner.getStyleClass().add("proctor-banner");
        banner.setVisible(false);
        banner.setManaged(false);
        setTop(new VBox(banner, buildTopBar()));
        setLeft(buildPalette());
        ScrollPane scroll = new ScrollPane(questionPane);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("editor-scroll");
        setCenter(scroll);
        setBottom(buildNavBar());
        questionPane.setPadding(new Insets(16));

        // The countdown runs on the monotonic clock, so changing the system clock mid-exam
        // can neither add time nor take it away. The stored expiry is only read once, here.
        deadlineNanos = System.nanoTime() + java.time.Duration.between(
            LocalDateTime.now(), sitting.attempt().getExpiresAt()).toNanos();
        show(0);
        tick();
        clock.scheduleAtFixedRate(() -> Platform.runLater(this::tick), 1, 1, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> Platform.runLater(this::autosave),
                                  AUTOSAVE_SECONDS, AUTOSAVE_SECONDS, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(this::proctorTick, 1, AppConfig.PROCTOR_TICK.toSeconds(), TimeUnit.SECONDS);
        attachProctoringHooks();

        // Navigating away from the exam screen must not leave ticking threads
        // behind, and must not drop answers that were not yet autosaved.
        sceneProperty().addListener((o, was, now) -> {
            if (now == null && !finishing.get()) {
                autosave();
                clock.shutdown();
            }
        });
    }

    // ------------------------------------------------------------------
    // proctoring
    // ------------------------------------------------------------------

    /** Window focus, key and mouse activity, and clipboard keys all feed {@link ProctorSignals}. */
    private void attachProctoringHooks() {
        sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) {
                return;
            }
            scene.addEventFilter(javafx.scene.input.MouseEvent.ANY, e -> signals.activity());
            scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
            watchWindow(scene.getWindow());
            scene.windowProperty().addListener((oo, a, w) -> watchWindow(w));
        });
    }

    private void watchWindow(javafx.stage.Window window) {
        if (window != null) {
            signals.focusChanged(window.isFocused());
            window.focusedProperty().addListener((o, was, focused) -> signals.focusChanged(focused));
        }
    }

    private void onKey(KeyEvent e) {
        signals.activity();
        boolean shortcut = e.isShortcutDown();
        boolean paste = (shortcut && e.getCode() == KeyCode.V)
                        || (e.isShiftDown() && e.getCode() == KeyCode.INSERT);
        boolean copy = shortcut && (e.getCode() == KeyCode.C || e.getCode() == KeyCode.X);
        if (paste) {
            signals.pasteAttempt();
            e.consume();      // pasting is blocked, not just noted
        } else if (copy) {
            signals.copyAttempt();
        }
    }

    /** Runs on the clock thread; listeners (database writer, banner) handle their own threading. */
    private void proctorTick() {
        try {
            signals.screens(screenCount());
            engine.tick(signals.snapshot(sitting.attempt().getId(), java.time.Instant.now()));
        } catch (RuntimeException e) {
            System.err.println("Proctor tick failed: " + e);
        }
    }

    private static int screenCount() {
        try {
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length;
        } catch (UnsupportedOperationException e) {   // includes HeadlessException
            return 1;
        }
    }

    /** Slides down from the top, holds four seconds, slides back. INFO events are not shown. */
    private void showBanner(ProctorEvent event) {
        if (event.getSeverity() == Severity.INFO) {
            return;
        }
        Platform.runLater(() -> {
            if (event.getSeverity().ordinal() >= Severity.HIGH.ordinal()) {
                setStyle("-fx-border-color: #d9534f; -fx-border-width: 3;");
                PauseTransition clear = new PauseTransition(Duration.millis(700));
                clear.setOnFinished(ev -> setStyle(""));
                clear.play();
            }
            banner.setText(bannerText(event));
            banner.setVisible(true);
            banner.setManaged(true);
            TranslateTransition in = new TranslateTransition(Duration.millis(250), banner);
            in.setFromY(-40);
            in.setToY(0);
            PauseTransition hold = new PauseTransition(Duration.seconds(4));
            TranslateTransition out = new TranslateTransition(Duration.millis(250), banner);
            out.setToY(-40);
            SequentialTransition run = new SequentialTransition(in, hold, out);
            run.setOnFinished(e -> {
                banner.setVisible(false);
                banner.setManaged(false);
            });
            run.play();
        });
    }

    private static String bannerText(ProctorEvent e) {
        return switch (e.getType()) {
            case FOCUS_LOST -> "You left the exam window. This has been recorded.";
            case PASTE_ATTEMPT -> "Pasting is not allowed during the exam. This has been recorded.";
            case COPY_ATTEMPT -> "Copying is recorded during the exam.";
            case IDLE_TIMEOUT -> "No activity detected. Are you still there?";
            case MULTI_DISPLAY_DETECTED -> "More than one display detected. This has been recorded.";
            default -> "Unusual activity was recorded.";
        };
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private HBox buildTopBar() {
        Label title = new Label(sitting.exam().getTitle());
        title.getStyleClass().add("heading");
        timer.getStyleClass().add("exam-timer");
        saveState.getStyleClass().add("status-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Circle track = new Circle(RING_RADIUS);
        track.setFill(Color.TRANSPARENT);
        track.setStroke(Color.web("#232b3d"));
        track.setStrokeWidth(4);
        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(Color.web("#5b8def"));
        ring.setStrokeWidth(4);
        ring.setRotate(-90);   // start at twelve o'clock
        ring.getStrokeDashArray().setAll(RING_LENGTH, RING_LENGTH);
        timer.setStyle("-fx-font-size: 13px;");
        timerBox.getChildren().addAll(track, ring, timer);
        HBox bar = new HBox(16, title, spacer, saveState, timerBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 10, 0));
        return bar;
    }

    private VBox buildPalette() {
        FlowPane grid = new FlowPane(6, 6);
        grid.setPrefWrapLength(200);
        for (int i = 0; i < paper.size(); i++) {
            final int target = i;
            Button b = new Button(String.valueOf(i + 1));
            b.getStyleClass().add("palette-button");
            b.setOnAction(e -> show(target));
            paletteButtons.add(b);
            grid.getChildren().add(b);
        }
        Label caption = new Label("Questions");
        caption.getStyleClass().add("section-label");
        Label legend = new Label("Filled = answered   ⚑ = flagged");
        legend.getStyleClass().add("status-label");
        VBox box = new VBox(8, caption, grid, legend);
        box.setPadding(new Insets(0, 14, 0, 0));
        box.setPrefWidth(230);
        return box;
    }

    private HBox buildNavBar() {
        Button prev = new Button("Previous");
        prev.setOnAction(e -> show(index - 1));
        Button next = new Button("Next");
        next.setOnAction(e -> show(index + 1));
        flag.setOnAction(e -> {
            Response r = responseFor(paper.get(index));
            r.setFlagged(!r.isFlagged());
            changed(paper.get(index));
            refreshFlagButton();
        });
        Button submit = new Button("Submit exam");
        submit.getStyleClass().add("primary-button");
        submit.setOnAction(e -> confirmAndSubmit());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, prev, next, flag, spacer, submit);
        bar.setPadding(new Insets(10, 0, 0, 0));
        return bar;
    }

    // ------------------------------------------------------------------
    // question rendering
    // ------------------------------------------------------------------

    private void show(int target) {
        if (target < 0 || target >= paper.size()) {
            return;
        }
        int direction = Integer.signum(target - index);
        index = target;
        Question q = paper.get(index);
        Response r = responseFor(q);
        shownAtMillis = System.currentTimeMillis();
        shownQuestionWasBlank = !q.isAttempted(r);

        Label header = new Label("Question " + (index + 1) + " of " + paper.size()
                                 + "  ·  " + trim(q.getMarks()) + " mark(s)"
                                 + (q.getNegativeMarks() > 0 ? "  ·  −" + trim(q.getNegativeMarks()) + " if wrong" : ""));
        header.getStyleClass().add("role-chip");
        Label stem = new Label(q.getStem());
        stem.setWrapText(true);
        stem.getStyleClass().add("preview-stem");

        questionPane.getChildren().setAll(header, stem, answerWidget(q, r));
        if (direction != 0) {
            // Forward slides in from the right (content moves left); back is the mirror.
            questionPane.setTranslateX(direction * 40);
            questionPane.setOpacity(0);
            TranslateTransition slide = new TranslateTransition(Duration.millis(200), questionPane);
            slide.setToX(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            FadeTransition fade = new FadeTransition(Duration.millis(200), questionPane);
            fade.setToValue(1);
            slide.play();
            fade.play();
        }
        refreshFlagButton();
        refreshPalette();
    }

    private Node answerWidget(Question q, Response r) {
        switch (q.getType()) {
            case MCQ, TRUE_FALSE -> {
                ToggleGroup group = new ToggleGroup();
                VBox box = new VBox(8);
                for (QuestionOption o : q.getOptions()) {
                    RadioButton rb = new RadioButton(o.getText());
                    rb.setWrapText(true);
                    rb.setToggleGroup(group);
                    rb.setSelected(r.getSelectedOptionIds().contains(o.getId()));
                    rb.selectedProperty().addListener((obs, was, is) -> {
                        if (is) {
                            r.getSelectedOptionIds().clear();
                            r.getSelectedOptionIds().add(o.getId());
                            changed(q);
                        }
                    });
                    box.getChildren().add(rb);
                }
                return box;
            }
            case MSQ -> {
                VBox box = new VBox(8);
                box.getChildren().add(hint("Select every correct option."));
                for (QuestionOption o : q.getOptions()) {
                    CheckBox cb = new CheckBox(o.getText());
                    cb.setWrapText(true);
                    cb.setSelected(r.getSelectedOptionIds().contains(o.getId()));
                    cb.selectedProperty().addListener((obs, was, is) -> {
                        if (is) {
                            r.getSelectedOptionIds().add(o.getId());
                        } else {
                            r.getSelectedOptionIds().remove(o.getId());
                        }
                        changed(q);
                    });
                    box.getChildren().add(cb);
                }
                return box;
            }
            case NUMERIC -> {
                TextField field = new TextField(r.getNumericAnswer() == null ? "" : trim(r.getNumericAnswer()));
                field.setPromptText("Your answer (a number)");
                field.getStyleClass().add("field");
                field.setMaxWidth(260);
                field.textProperty().addListener((obs, was, now) -> {
                    try {
                        r.setNumericAnswer(now.isBlank() ? null : Double.parseDouble(now.trim()));
                        field.setStyle("");
                    } catch (NumberFormatException e) {
                        r.setNumericAnswer(null);
                        field.setStyle("-fx-border-color: #d9534f;");
                    }
                    changed(q);
                });
                return field;
            }
            default -> {
                TextArea area = new TextArea(r.getTextAnswer() == null ? "" : r.getTextAnswer());
                area.setPromptText("Type your answer");
                area.setWrapText(true);
                area.setPrefRowCount(6);
                area.getStyleClass().add("field");
                area.textProperty().addListener((obs, was, now) -> {
                    r.setTextAnswer(now);
                    changed(q);
                });
                return area;
            }
        }
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("status-label");
        return l;
    }

    private void refreshFlagButton() {
        flag.setText(responseFor(paper.get(index)).isFlagged() ? "Remove flag" : "Flag for review");
    }

    private void refreshPalette() {
        for (int i = 0; i < paletteButtons.size(); i++) {
            Question q = paper.get(i);
            Response r = responses.get(q.getId());
            Button b = paletteButtons.get(i);
            b.getStyleClass().removeAll("palette-answered", "palette-flagged", "palette-current");
            if (r != null && q.isAttempted(r)) {
                b.getStyleClass().add("palette-answered");
            }
            boolean flagged = r != null && r.isFlagged();
            if (flagged) {
                b.getStyleClass().add("palette-flagged");
            }
            if (i == index) {
                b.getStyleClass().add("palette-current");
            }
            b.setText((flagged ? "⚑" : "") + (i + 1));
            String state = b.getStyleClass().toString();
            if (b.getUserData() != null && !state.equals(b.getUserData())) {
                FadeTransition f = new FadeTransition(Duration.millis(150), b);
                f.setFromValue(0.35);
                f.setToValue(1);
                f.play();
            }
            b.setUserData(state);
        }
    }

    // ------------------------------------------------------------------
    // answers, autosave, recovery cache
    // ------------------------------------------------------------------

    private Response responseFor(Question q) {
        return responses.computeIfAbsent(q.getId(), id -> {
            Response r = new Response(id);
            r.setAttemptId(sitting.attempt().getId());
            return r;
        });
    }

    private void changed(Question q) {
        if (shownQuestionWasBlank && q.isAttempted(responses.get(q.getId()))) {
            shownQuestionWasBlank = false;   // only the first answer to a fresh question counts
            signals.answerLatency(System.currentTimeMillis() - shownAtMillis);
        }
        dirty.add(q.getId());
        service.cache().save(sitting.attempt().getId(), responses.values());
        refreshPalette();
    }

    /** FX thread: snapshot what changed, then write it off-thread. */
    private void autosave() {
        if (dirty.isEmpty() || finishing.get() || clock.isShutdown()) {
            return;
        }
        List<Response> batch = new ArrayList<>();
        for (Long id : dirty) {
            batch.add(copy(responses.get(id)));
        }
        dirty.clear();

        clock.execute(() -> {
            try {
                for (Response r : batch) {
                    service.saveResponse(r);
                }
                Platform.runLater(() -> {
                    saveFailures = 0;
                    saveState.setText("● saved");
                    saveState.setStyle("");
                    ScaleTransition blip = new ScaleTransition(Duration.millis(220), saveState);
                    blip.setToX(1.25);
                    blip.setToY(1.25);
                    blip.setCycleCount(2);
                    blip.setAutoReverse(true);
                    blip.play();
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    batch.forEach(r -> dirty.add(r.getQuestionId())); // retried on the next tick
                    // Silent while retrying; only a run of failures is worth a warning.
                    if (++saveFailures >= WARN_AFTER_FAILURES) {
                        saveState.setText("⚠ not saving to the database (kept locally)");
                        saveState.setStyle("-fx-text-fill: #e0a030;");
                    }
                });
            }
        });
    }

    private static Response copy(Response r) {
        Response c = new Response(r.getQuestionId());
        c.setAttemptId(r.getAttemptId());
        c.getSelectedOptionIds().addAll(r.getSelectedOptionIds());
        c.setTextAnswer(r.getTextAnswer());
        c.setNumericAnswer(r.getNumericAnswer());
        c.setFlagged(r.isFlagged());
        return c;
    }

    // ------------------------------------------------------------------
    // clock and submission
    // ------------------------------------------------------------------

    private void tick() {
        java.time.Duration left = java.time.Duration.ofNanos(deadlineNanos - System.nanoTime());
        if (left.isNegative() || left.isZero()) {
            timer.setText("00:00");
            finish(true);
            return;
        }
        long s = left.getSeconds();
        timer.setText(String.format("%02d:%02d", s / 60, s % 60));
        updateRing(s);
    }

    /** Ring drains with the time left; tone shifts at five minutes, and it pulses in the last minute. */
    private void updateRing(long secondsLeft) {
        long total = Math.max(1, java.time.Duration.between(
            sitting.attempt().getStartedAt(), sitting.attempt().getExpiresAt()).getSeconds());
        double fraction = Math.max(0, Math.min(1, (double) secondsLeft / total));
        ring.setStrokeDashOffset(RING_LENGTH * (1 - fraction));

        String tone = secondsLeft <= 60 ? "#d9534f" : secondsLeft <= 300 ? "#e0a030" : "#5b8def";
        ring.setStroke(Color.web(tone));
        timer.setStyle("-fx-font-size: 13px;" + (secondsLeft <= 300 ? " -fx-text-fill: " + tone + ";" : ""));

        if (secondsLeft <= 60 && lastMinutePulse == null) {
            lastMinutePulse = PulseEffect.attach(timerBox, Duration.seconds(1));
        }
    }

    private void confirmAndSubmit() {
        long unanswered = paper.stream()
            .filter(q -> !q.isAttempted(responses.get(q.getId()))).count();
        long flagged = responses.values().stream().filter(Response::isFlagged).count();

        String body = unanswered + " unanswered, " + flagged + " flagged for review.\n"
                      + "You cannot change your answers after submitting.";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Submit the exam?");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isPresent() && answer.get() == ButtonType.OK) {
            finish(false);
        }
    }

    /** The AtomicBoolean makes a double click, or a click racing the clock, submit once. */
    private void finish(boolean auto) {
        if (!finishing.compareAndSet(false, true)) {
            return;
        }
        try {
            service.submit(sitting.attempt(), responses.values(), auto);
        } catch (AttemptAlreadySubmittedException e) {
            // Already closed elsewhere; nothing more to write.
        } catch (ExamProException e) {
            // Answers are still in the recovery cache. Allow another try.
            finishing.set(false);
            AlertFactory.error("Could not submit. Your answers are safe on this computer; try again.", e);
            return;
        }
        clock.shutdown();
        AlertFactory.info(auto ? "Time is up" : "Submitted",
                          auto ? "Your answers were submitted automatically."
                               : "Your answers were submitted.");
        onFinished.run();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
