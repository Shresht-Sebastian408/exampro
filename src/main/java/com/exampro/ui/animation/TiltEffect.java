package com.exampro.ui.animation;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * Card tilts toward the cursor, snaps back on exit.
 *
 * Handlers are added rather than set, so attaching this does not silently
 * replace a mouse handler the screen installed for something else.
 */
public final class TiltEffect {

    private TiltEffect() {}

    public static void attach(Node node, double maxAngle) {
        Rotate aroundX = new Rotate(0, Rotate.X_AXIS);
        Rotate aroundY = new Rotate(0, Rotate.Y_AXIS);
        node.getTransforms().addAll(aroundX, aroundY);

        node.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            double w = node.getBoundsInLocal().getWidth();
            double h = node.getBoundsInLocal().getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // Pivot is re-centred every move because the bounds change with
            // layout, and a stale pivot makes the card swing instead of tilt.
            aroundX.setPivotX(w / 2);
            aroundX.setPivotY(h / 2);
            aroundY.setPivotX(w / 2);
            aroundY.setPivotY(h / 2);

            aroundY.setAngle((e.getX() / w - 0.5) * 2 * maxAngle);
            aroundX.setAngle(-(e.getY() / h - 0.5) * 2 * maxAngle);
        });

        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            Timeline back = new Timeline(new KeyFrame(Duration.millis(320),
                new KeyValue(aroundX.angleProperty(), 0,
                             Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0)),
                new KeyValue(aroundY.angleProperty(), 0,
                             Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0))));
            back.play();
        });
    }
}
