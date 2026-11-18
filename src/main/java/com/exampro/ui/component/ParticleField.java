package com.exampro.ui.component;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Drifting particle background for the login screen.
 *
 * Particles are retained {@link Circle} nodes moved by an
 * {@link AnimationTimer}; the connecting lines are drawn on a single
 * {@link Canvas} underneath. Lines as nodes would mean allocating and
 * discarding a few hundred Line objects every frame, which is exactly the
 * kind of churn that makes a background animation stutter.
 *
 * {@link #stop()} must be called when the screen goes away. An
 * AnimationTimer keeps running after its nodes are detached, and nobody
 * notices until the laptop fan does.
 */
public class ParticleField extends Pane {

    private static final int COUNT = 70;
    private static final double LINK_DISTANCE = 130;
    private static final double CURSOR_RADIUS = 150;
    private static final double REPEL_STRENGTH = 9_000;
    private static final double MAX_SPEED = 40;

    private static final class Particle {
        final Circle node;
        double vx;
        double vy;

        Particle(Circle node, double vx, double vy) {
            this.node = node;
            this.vx = vx;
            this.vy = vy;
        }
    }

    private final List<Particle> particles = new ArrayList<>(COUNT);
    private final Canvas links = new Canvas();
    private final Random random = new Random();
    private final AnimationTimer timer;

    private double cursorX = -1_000;
    private double cursorY = -1_000;
    private long lastFrame;

    public ParticleField() {
        setMouseTransparent(true);
        links.widthProperty().bind(widthProperty());
        links.heightProperty().bind(heightProperty());
        getChildren().add(links);

        for (int i = 0; i < COUNT; i++) {
            Circle dot = new Circle(1.2 + random.nextDouble() * 2.0);
            dot.setFill(Color.web("#6f8dc4", 0.55));
            particles.add(new Particle(dot,
                (random.nextDouble() - 0.5) * MAX_SPEED,
                (random.nextDouble() - 0.5) * MAX_SPEED));
            getChildren().add(dot);
        }

        // Seeding needs the real size, which does not exist until layout.
        widthProperty().addListener((obs, old, now) -> scatterIfUnplaced());
        heightProperty().addListener((obs, old, now) -> scatterIfUnplaced());

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double dt = lastFrame == 0 ? 0.016 : Math.min((now - lastFrame) / 1e9, 0.05);
                lastFrame = now;
                step(dt);
            }
        };
        timer.start();
    }

    /** Cursor position in this pane's coordinates; the pane itself is click-through. */
    public void trackCursor(double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    public void releaseCursor() {
        cursorX = -1_000;
        cursorY = -1_000;
    }

    public void stop() {
        timer.stop();
    }

    private void scatterIfUnplaced() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        for (Particle p : particles) {
            if (p.node.getCenterX() == 0 && p.node.getCenterY() == 0) {
                p.node.setCenterX(random.nextDouble() * w);
                p.node.setCenterY(random.nextDouble() * h);
            }
        }
    }

    private void step(double dt) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        for (Particle p : particles) {
            double x = p.node.getCenterX();
            double y = p.node.getCenterY();

            double dx = x - cursorX;
            double dy = y - cursorY;
            double distance = Math.hypot(dx, dy);
            if (distance < CURSOR_RADIUS && distance > 0.5) {
                // Inversely proportional to distance: a particle right under
                // the cursor gets shoved, one at the edge barely notices.
                double push = REPEL_STRENGTH / (distance * distance);
                p.vx += dx / distance * push * dt * 60;
                p.vy += dy / distance * push * dt * 60;
            }

            // Drag, so a swept cursor does not leave everything at warp speed.
            p.vx *= 0.99;
            p.vy *= 0.99;
            double speed = Math.hypot(p.vx, p.vy);
            if (speed > MAX_SPEED * 4) {
                p.vx = p.vx / speed * MAX_SPEED * 4;
                p.vy = p.vy / speed * MAX_SPEED * 4;
            }

            x += p.vx * dt;
            y += p.vy * dt;

            if (x < 0 || x > w) {
                p.vx = -p.vx;
                x = Math.clamp(x, 0, w);
            }
            if (y < 0 || y > h) {
                p.vy = -p.vy;
                y = Math.clamp(y, 0, h);
            }

            p.node.setCenterX(x);
            p.node.setCenterY(y);
        }

        drawLinks(w, h);
    }

    // ponytail: O(n^2) pair scan, 70 particles is 2415 checks a frame and
    // costs nothing. A spatial grid is the upgrade if COUNT ever grows.
    private void drawLinks(double w, double h) {
        GraphicsContext g = links.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setLineWidth(0.7);

        for (int i = 0; i < particles.size(); i++) {
            Circle a = particles.get(i).node;
            for (int j = i + 1; j < particles.size(); j++) {
                Circle b = particles.get(j).node;
                double distance = Math.hypot(a.getCenterX() - b.getCenterX(),
                                             a.getCenterY() - b.getCenterY());
                if (distance < LINK_DISTANCE) {
                    g.setStroke(Color.web("#6f8dc4", 0.22 * (1 - distance / LINK_DISTANCE)));
                    g.strokeLine(a.getCenterX(), a.getCenterY(),
                                 b.getCenterX(), b.getCenterY());
                }
            }
        }
    }
}
