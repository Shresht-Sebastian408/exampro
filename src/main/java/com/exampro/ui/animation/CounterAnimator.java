package com.exampro.ui.animation;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.Label;
import javafx.util.Duration;

/** Counts a stat label from one number to another instead of snapping. */
public final class CounterAnimator {

    private CounterAnimator() {}

    public static void animate(Label label, int from, int to, Duration d) {
        IntegerProperty value = new SimpleIntegerProperty(from);
        value.addListener((obs, old, now) -> label.setText(String.valueOf(now.intValue())));
        label.setText(String.valueOf(from));

        Timeline timeline = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(value, from)),
            new KeyFrame(d, new KeyValue(value, to)));
        timeline.play();
    }
}
