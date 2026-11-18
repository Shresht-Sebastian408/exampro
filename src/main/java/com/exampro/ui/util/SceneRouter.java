package com.exampro.ui.util;

import com.exampro.config.AppConfig;
import com.exampro.service.AuthService;
import com.exampro.ui.controller.AppShell;
import com.exampro.ui.controller.LoginView;
import javafx.animation.FadeTransition;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * One stage, one scene, swapped roots.
 *
 * Every navigation in the application goes through here, which is what
 * keeps the stylesheet, the window size and the crossfade in a single
 * place instead of repeated at each call site. Screens call
 * {@code showLogin()} or {@code showShell()} and never touch the Stage.
 */
public class SceneRouter {

    private static final Duration FADE = Duration.millis(180);

    private final Stage stage;
    private final AuthService auth;
    private final Scene scene;

    public SceneRouter(Stage stage, AuthService auth) {
        this.stage = stage;
        this.auth = auth;

        this.scene = new Scene(new LoginView(auth, this), 1060, 680);
        scene.getStylesheets().add(
            SceneRouter.class.getResource("/css/app.css").toExternalForm());

        stage.setTitle(AppConfig.APP_NAME + " " + AppConfig.APP_VERSION);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
    }

    public void showLogin() {
        swap(new LoginView(auth, this));
    }

    public void showShell() {
        swap(new AppShell(auth, this));
    }

    public Stage stage() {
        return stage;
    }

    private void swap(Parent root) {
        Parent old = scene.getRoot();
        if (old instanceof Disposable disposable) {
            disposable.dispose();
        }
        root.setOpacity(0);
        scene.setRoot(root);

        FadeTransition in = new FadeTransition(FADE, root);
        in.setFromValue(0);
        in.setToValue(1);
        in.play();
    }

    /**
     * A screen holding something that outlives its nodes, an AnimationTimer
     * above all, implements this so the router can shut it down on the way
     * out. Without it the login particle timer keeps burning a core for the
     * rest of the session.
     */
    public interface Disposable {
        void dispose();
    }
}
