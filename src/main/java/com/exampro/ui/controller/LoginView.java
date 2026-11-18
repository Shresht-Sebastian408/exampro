package com.exampro.ui.controller;

import com.exampro.config.schema.DataSeeder;
import com.exampro.exception.AuthenticationException;
import com.exampro.exception.ExamProException;
import com.exampro.exception.ValidationException;
import com.exampro.model.entity.User;
import com.exampro.service.AuthService;
import com.exampro.ui.animation.FadeSlideIn;
import com.exampro.ui.animation.StaggerAnimator;
import com.exampro.ui.animation.TiltEffect;
import com.exampro.ui.component.ParticleField;
import com.exampro.ui.util.AlertFactory;
import com.exampro.ui.util.SceneRouter;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Sign-in screen: particle field behind, one card in front.
 *
 * The card tilts toward the cursor and the fields arrive staggered, both
 * from the shared animation utilities rather than hand-rolled here.
 */
public class LoginView extends StackPane implements SceneRouter.Disposable {

    private final AuthService auth;
    private final SceneRouter router;

    private final ParticleField particles = new ParticleField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label message = new Label();

    public LoginView(AuthService auth, SceneRouter router) {
        this.auth = auth;
        this.router = router;

        getStyleClass().add("login-root");
        getChildren().addAll(particles, buildCard());

        // The particle pane is mouse-transparent, so the cursor is read here
        // on the root and translated into the field's own coordinates.
        addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            Point2D local = particles.sceneToLocal(e.getSceneX(), e.getSceneY());
            particles.trackCursor(local.getX(), local.getY());
        });
        addEventFilter(MouseEvent.MOUSE_EXITED, e -> particles.releaseCursor());
    }

    @Override
    public void dispose() {
        particles.stop();
    }

    private VBox buildCard() {
        Label brand = new Label("ExamPro");
        brand.getStyleClass().add("brand");
        Label tagline = new Label("Exam proctoring and question bank");
        tagline.getStyleClass().add("tagline");

        username.setPromptText("Username");
        username.getStyleClass().add("field");
        password.setPromptText("Password");
        password.getStyleClass().add("field");

        Button signIn = new Button("Sign in");
        signIn.getStyleClass().add("primary-button");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setOnAction(e -> attemptLogin());

        message.getStyleClass().add("form-message");
        message.setWrapText(true);

        Label hint = new Label("Demo accounts: admin / " + DataSeeder.DEFAULT_ADMIN_PASSWORD
                               + "  ·  faculty1 / " + DataSeeder.DEFAULT_FACULTY_PASSWORD
                               + "  ·  student01 / " + DataSeeder.DEFAULT_STUDENT_PASSWORD);
        hint.getStyleClass().add("status-label");
        hint.setWrapText(true);

        VBox card = new VBox(12, brand, tagline, username, password, signIn, message, hint);
        card.getStyleClass().add("login-card");
        card.setPadding(new Insets(32, 34, 28, 34));
        card.setMaxSize(400, VBox.USE_PREF_SIZE);
        card.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(card, Pos.CENTER);

        TiltEffect.attach(card, 5);
        FadeSlideIn.play(card, FadeSlideIn.Direction.UP, 0);
        StaggerAnimator.play(List.of(username, password, signIn), 60);

        return card;
    }

    private void attemptLogin() {
        try {
            User user = auth.login(username.getText(), password.getText()).user();
            message.setText("");
            password.clear();
            router.showShell();
            System.out.println("Signed in: " + user.getUsername() + " " + user.getRoles());
        } catch (ValidationException | AuthenticationException e) {
            // Expected on a bad attempt. A sentence on the card, no dialog:
            // a modal for a typo'd password is a worse screen than this.
            int left = auth.attemptsLeft(username.getText());
            message.setText(left > 0 && left <= 2
                            ? e.getMessage() + " " + left + " attempt(s) left."
                            : e.getMessage());
            password.clear();
        } catch (ExamProException e) {
            // Anything else is a real fault, database down included.
            AlertFactory.error("Could not sign in", e);
        }
    }
}
