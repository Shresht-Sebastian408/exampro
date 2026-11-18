package com.exampro.ui;

import com.exampro.config.ConnectionPool;
import com.exampro.service.AuthService;
import com.exampro.ui.util.AlertFactory;
import com.exampro.ui.util.SceneRouter;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class ExamProApp extends Application {

    @Override
    public void start(Stage stage) {
        // Plan section 8: a stray exception on the FX thread shows a dialog
        // instead of freezing the window with nothing in the log.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            if (Platform.isFxApplicationThread()) {
                AlertFactory.error("Something went wrong", error);
            } else {
                Platform.runLater(() -> AlertFactory.error("Something went wrong", error));
            }
        });

        // One AuthService for the process: it holds the current session, so
        // a second instance would mean a second idea of who is signed in.
        new SceneRouter(stage, new AuthService());

        // Dialogs are separate windows with their own scene, so the main stylesheet does not
        // reach them. Theme every one in one place instead of at each of the call sites.
        String css = ExamProApp.class.getResource("/css/app.css").toExternalForm();
        javafx.stage.Window.getWindows().addListener((javafx.collections.ListChangeListener<javafx.stage.Window>) c -> {
            while (c.next()) {
                for (javafx.stage.Window w : c.getAddedSubList()) {
                    javafx.scene.Scene sc = w.getScene();
                    if (sc != null && sc.getRoot() instanceof javafx.scene.control.DialogPane
                            && !sc.getStylesheets().contains(css)) {
                        sc.getStylesheets().add(css);
                    }
                }
            }
        });
        stage.show();
    }

    @Override
    public void stop() {
        // Closes the H2 file cleanly, which matters for an embedded database.
        ConnectionPool.shutdown();
    }
}
