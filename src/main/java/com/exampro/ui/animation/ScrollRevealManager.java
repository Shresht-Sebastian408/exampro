package com.exampro.ui.animation;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;

/**
 * Fires a callback the first time a node scrolls past 75 percent of the
 * viewport height. Used by the dashboard and results screens, where
 * sections reveal as they come into view.
 */
public final class ScrollRevealManager {

    private static final double TRIGGER = 0.75;

    private ScrollRevealManager() {}

    public static void register(ScrollPane pane, Node target, Runnable onReveal) {
        boolean[] fired = { false };
        pane.vvalueProperty().addListener((obs, old, now) -> {
            if (fired[0]) {
                return;
            }
            Bounds node = target.localToScene(target.getBoundsInLocal());
            Bounds viewport = pane.localToScene(pane.getBoundsInLocal());
            if (node.getMinY() < viewport.getMinY() + viewport.getHeight() * TRIGGER) {
                fired[0] = true;
                onReveal.run();
            }
        });
    }
}
