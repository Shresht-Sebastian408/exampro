package com.exampro.ui.animation;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * The entrance used by every screen: fade up from slightly off position.
 *
 * One call sets the start state and plays, so a caller never has to
 * remember to pre-set opacity to zero.
 */
public final class FadeSlideIn {

    /** Where the node travels from, named by the direction it moves. */
    public enum Direction {
        UP(0, 24), DOWN(0, -24), LEFT(24, 0), RIGHT(-24, 0);

        final double dx;
        final double dy;

        Direction(double dx, double dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static final Duration SPEED = Duration.millis(320);
    private static final Interpolator EASE_OUT =
        Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0);

    private FadeSlideIn() {}

    public static void play(Node node, Direction dir, double delayMs) {
        node.setOpacity(0);
        node.setTranslateX(dir.dx);
        node.setTranslateY(dir.dy);

        FadeTransition fade = new FadeTransition(SPEED, node);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(SPEED, node);
        slide.setToX(0);
        slide.setToY(0);
        slide.setInterpolator(EASE_OUT);

        ParallelTransition both = new ParallelTransition(fade, slide);
        both.setDelay(Duration.millis(delayMs));
        both.play();
    }
}
