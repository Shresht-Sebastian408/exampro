package com.exampro.ui.controller;

import com.exampro.dao.ExamDao;
import com.exampro.dao.impl.ExamDaoImpl;
import com.exampro.exception.ExamProException;
import com.exampro.model.entity.Exam;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.Severity;
import com.exampro.service.MonitorService;
import com.exampro.service.MonitorService.FeedItem;
import com.exampro.service.MonitorService.Tile;
import com.exampro.ui.animation.FadeSlideIn;
import com.exampro.ui.util.AlertFactory;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Faculty live monitor for one exam: a grid of student tiles, a feed of
 * events, and a timeline strip for whichever tile is selected.
 *
 * It polls every two seconds. ponytail: the poll runs on the FX thread, which
 * is fine for one local database and a classroom of tiles; move it to a
 * background task if the query ever takes noticeable time.
 *
 * Tiles are reordered so serious events float to the front, and the move is
 * animated: each tile slides from where it was to where it is now, rather
 * than jumping.
 */
public class LiveMonitorView extends BorderPane {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int FEED_LIMIT = 60;

    /** The visible half of one tile; the data comes from {@link Tile}. */
    private static final class TileNode extends VBox {
        final Label name = new Label();
        final Label state = new Label();
        final ProgressBar meter = new ProgressBar();
        final Label detail = new Label();
        long attemptId;

        TileNode() {
            super(6);
            name.getStyleClass().add("heading");
            state.getStyleClass().add("role-chip");
            detail.getStyleClass().add("status-label");
            detail.setWrapText(true);
            meter.setMaxWidth(Double.MAX_VALUE);
            getStyleClass().add("monitor-tile");
            setPadding(new Insets(12));
            setPrefWidth(210);
            getChildren().addAll(name, state, meter, detail);
        }

        void show(Tile t) {
            attemptId = t.attempt().getId();
            name.setText(t.student());
            state.setText(t.attempt().getStatus().name().replace('_', ' '));
            double score = t.integrity().score();
            meter.setProgress(score / 100);
            meter.setStyle("-fx-accent: " + (score >= 80 ? "#3fae6a" : score >= 50 ? "#e0a030" : "#d9534f") + ";");
            ProctorEvent last = t.events().isEmpty() ? null : t.events().get(t.events().size() - 1);
            detail.setText("Integrity " + Math.round(score) + "/100  ·  " + t.events().size() + " event(s)"
                           + (last == null ? "" : "\nLast: " + last.getType()));
            setOpacity(t.live() ? 1 : 0.6);
        }
    }

    private final ExamDao examDao = new ExamDaoImpl();
    private final MonitorService service = new MonitorService();

    private final ComboBox<Exam> examPicker = new ComboBox<>();
    private final FlowPane grid = new FlowPane(12, 12);
    private final VBox feed = new VBox(4);
    private final VBox timelineBox = new VBox(6);
    private final Label status = new Label();

    private final Map<Long, TileNode> nodes = new HashMap<>();
    private final Map<Long, LocalDateTime> lastHigh = new HashMap<>();
    private final Set<Long> seenEvents = new HashSet<>();
    private Map<Long, Tile> tiles = Map.of();
    private boolean firstLoad = true;
    private Long selected;

    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh()));

    public LiveMonitorView() {
        Label heading = new Label("Live monitor");
        heading.getStyleClass().add("heading");
        examPicker.setPromptText("Choose an exam");
        examPicker.setPrefWidth(300);
        examPicker.valueProperty().addListener((o, a, b) -> reset());
        HBox bar = new HBox(12, heading, examPicker, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-row");
        setTop(bar);

        grid.setPadding(new Insets(6));
        ScrollPane gridScroll = new ScrollPane(grid);
        gridScroll.setFitToWidth(true);
        gridScroll.getStyleClass().add("editor-scroll");
        setCenter(gridScroll);

        Label feedTitle = new Label("Event feed");
        feedTitle.getStyleClass().add("section-label");
        ScrollPane feedScroll = new ScrollPane(feed);
        feedScroll.setFitToWidth(true);
        feedScroll.getStyleClass().add("editor-scroll");
        VBox right = new VBox(6, feedTitle, feedScroll);
        right.setPrefWidth(280);
        right.setPadding(new Insets(0, 0, 0, 12));
        VBox.setVgrow(feedScroll, javafx.scene.layout.Priority.ALWAYS);
        setRight(right);

        timelineBox.setPadding(new Insets(10, 0, 0, 0));
        setBottom(timelineBox);

        poll.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                poll.stop();
            }
        });

        try {
            examPicker.setItems(FXCollections.observableArrayList(examDao.findAll()));
        } catch (ExamProException e) {
            AlertFactory.error("Could not load exams", e);
        }
    }

    private void reset() {
        grid.getChildren().clear();
        feed.getChildren().clear();
        timelineBox.getChildren().clear();
        nodes.clear();
        lastHigh.clear();
        seenEvents.clear();
        selected = null;
        firstLoad = true;
        poll.playFromStart();
        refresh();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        Exam exam = examPicker.getValue();
        if (exam == null) {
            return;
        }
        try {
            MonitorService.Snapshot snap = service.snapshot(exam.getId(), FEED_LIMIT);
            updateTiles(snap.tiles());
            updateFeed(snap.feed());
            if (selected != null && tiles.containsKey(selected)) {
                drawTimeline(tiles.get(selected));
            }
            long live = snap.tiles().stream().filter(Tile::live).count();
            status.setText(live + " live of " + snap.tiles().size() + " attempt(s)  ·  updated "
                           + LocalDateTime.now().format(CLOCK));
            firstLoad = false;
        } catch (ExamProException e) {
            status.setText("Could not refresh: " + e.getMessage());
        }
    }

    private void updateTiles(List<Tile> ordered) {
        Map<Long, Tile> byId = new HashMap<>();
        List<Node> newOrder = new ArrayList<>();
        for (Tile t : ordered) {
            byId.put(t.attempt().getId(), t);
            TileNode node = nodes.computeIfAbsent(t.attempt().getId(), id -> {
                TileNode n = new TileNode();
                n.setOnMouseClicked(e -> select(id));
                return n;
            });
            node.show(t);
            newOrder.add(node);
            flashIfNewAlert(t, node);
        }
        tiles = byId;
        boolean arriving = firstLoad && grid.getChildren().isEmpty() && !newOrder.isEmpty();

        // Where each tile is now, so it can slide to where it is going.
        Map<Node, double[]> before = new HashMap<>();
        for (Node n : grid.getChildren()) {
            before.put(n, new double[] {n.getLayoutX(), n.getLayoutY()});
        }
        if (!newOrder.equals(grid.getChildren())) {
            grid.getChildren().setAll(newOrder);
            grid.applyCss();
            grid.layout();
            for (Node n : newOrder) {
                double[] was = before.get(n);
                if (was == null || firstLoad) {
                    continue;
                }
                double dx = was[0] - n.getLayoutX();
                double dy = was[1] - n.getLayoutY();
                if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                    TranslateTransition slide = new TranslateTransition(Duration.millis(450), n);
                    slide.setFromX(dx);
                    slide.setFromY(dy);
                    slide.setToX(0);
                    slide.setToY(0);
                    slide.play();
                }
            }
        }
        if (arriving) {
            com.exampro.ui.animation.StaggerAnimator.play(newOrder, 60);
        }
    }

    /** A tile that has just seen a new serious event flashes and carries a red border for a few seconds. */
    private void flashIfNewAlert(Tile t, TileNode node) {
        LocalDateTime high = t.lastHighAt();
        LocalDateTime before = lastHigh.put(t.attempt().getId(), high);
        if (high != null && !firstLoad && (before == null || high.isAfter(before))) {
            FadeTransition flash = new FadeTransition(Duration.millis(180), node);
            flash.setFromValue(0.25);
            flash.setToValue(1);
            flash.setCycleCount(6);
            flash.setAutoReverse(true);
            flash.play();
            node.getStyleClass().add("tile-alert");
            PauseTransition clear = new PauseTransition(Duration.seconds(4));
            clear.setOnFinished(e -> node.getStyleClass().remove("tile-alert"));
            clear.play();
        }
    }

    private void updateFeed(List<FeedItem> items) {
        // items are newest first; add oldest-of-the-new first so the newest ends up on top.
        for (int i = items.size() - 1; i >= 0; i--) {
            FeedItem item = items.get(i);
            if (!seenEvents.add(item.event().getId())) {
                continue;
            }
            ProctorEvent e = item.event();
            Label line = new Label(e.getOccurredAt().format(CLOCK) + "  " + item.student()
                                   + "\n" + e.getType() + "  (" + e.getSeverity() + ")");
            line.setWrapText(true);
            line.getStyleClass().add(e.getSeverity().ordinal() >= Severity.HIGH.ordinal()
                                     ? "feed-high" : "feed-line");
            feed.getChildren().add(0, line);
            if (!firstLoad) {
                FadeSlideIn.play(line, FadeSlideIn.Direction.RIGHT, 0);
            }
        }
        while (feed.getChildren().size() > FEED_LIMIT) {
            feed.getChildren().remove(feed.getChildren().size() - 1);
        }
    }

    private void select(long attemptId) {
        selected = attemptId;
        grid.getChildren().forEach(n -> n.getStyleClass().remove("tile-selected"));
        TileNode node = nodes.get(attemptId);
        if (node != null) {
            node.getStyleClass().add("tile-selected");
        }
        drawTimeline(tiles.get(attemptId));
    }

    // ------------------------------------------------------------------
    // per-student timeline strip
    // ------------------------------------------------------------------

    private void drawTimeline(Tile t) {
        timelineBox.getChildren().clear();
        if (t == null) {
            return;
        }
        Label title = new Label(t.student() + " - timeline");
        title.getStyleClass().add("section-label");

        Pane strip = new Pane();
        strip.setPrefHeight(34);
        strip.setMinHeight(34);
        strip.getStyleClass().add("timeline-strip");

        LocalDateTime from = t.attempt().getStartedAt();
        LocalDateTime to = t.attempt().isFinished() && t.attempt().getSubmittedAt() != null
                           ? t.attempt().getSubmittedAt()
                           : (LocalDateTime.now().isBefore(t.attempt().getExpiresAt())
                              ? t.attempt().getExpiresAt() : LocalDateTime.now());
        long span = Math.max(1, java.time.Duration.between(from, to).toMillis());

        Line axis = new Line();
        axis.setStartY(17);
        axis.setEndY(17);
        axis.setStroke(Color.web("#2b3a55"));
        axis.endXProperty().bind(strip.widthProperty());
        strip.getChildren().add(axis);

        for (ProctorEvent e : t.events()) {
            double fraction = Math.max(0, Math.min(1,
                (double) java.time.Duration.between(from, e.getOccurredAt()).toMillis() / span));
            Circle mark = new Circle(e.getSeverity().ordinal() >= Severity.HIGH.ordinal() ? 7 : 5,
                                     colourOf(e.getSeverity()));
            mark.setCenterY(17);
            mark.centerXProperty().bind(strip.widthProperty().multiply(fraction));
            Tooltip tip = new Tooltip(e.getOccurredAt().format(CLOCK) + "  " + e.getType()
                + " (" + e.getSeverity() + ")"
                + (e.getDurationMs() == null ? "" : "  " + e.getDurationMs() / 1000.0 + "s")
                + (e.getMetadata() == null ? "" : "  " + e.getMetadata()));
            tip.setShowDelay(Duration.millis(80));
            Tooltip.install(mark, tip);
            // Marks grow on hover so the target is easy to hit.
            mark.setOnMouseEntered(ev -> { mark.setScaleX(1.6); mark.setScaleY(1.6); });
            mark.setOnMouseExited(ev -> { mark.setScaleX(1); mark.setScaleY(1); });
            strip.getChildren().add(mark);
        }
        timelineBox.getChildren().addAll(title, strip);
    }

    private static Color colourOf(Severity s) {
        return switch (s) {
            case INFO -> Color.web("#6b7a99");
            case LOW -> Color.web("#5b8def");
            case MEDIUM -> Color.web("#e0a030");
            case HIGH, CRITICAL -> Color.web("#d9534f");
        };
    }
}
