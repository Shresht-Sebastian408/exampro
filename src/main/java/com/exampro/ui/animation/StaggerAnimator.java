package com.exampro.ui.animation;

import com.exampro.ui.animation.FadeSlideIn.Direction;
import javafx.scene.Node;

import java.util.List;

/** The same entrance down a list, each node a fixed gap behind the last. */
public final class StaggerAnimator {

    private StaggerAnimator() {}

    public static void play(List<? extends Node> nodes, double gapMs) {
        play(nodes, Direction.UP, gapMs);
    }

    public static void play(List<? extends Node> nodes, Direction dir, double gapMs) {
        for (int i = 0; i < nodes.size(); i++) {
            FadeSlideIn.play(nodes.get(i), dir, i * gapMs);
        }
    }
}
