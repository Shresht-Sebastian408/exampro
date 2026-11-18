package com.exampro.ui.animation;

import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Slow looping breathe. Returns the transition so the caller can stop it,
 * because an indefinite animation on a discarded node keeps a timer alive.
 */
public final class PulseEffect {

    private PulseEffect() {}

    public static ScaleTransition attach(Node node, Duration period) {
        ScaleTransition pulse = new ScaleTransition(period.divide(2), node);
        pulse.setFromX(1);
        pulse.setFromY(1);
        pulse.setToX(1.18);
        pulse.setToY(1.18);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(ScaleTransition.INDEFINITE);
        pulse.play();
        return pulse;
    }
}
