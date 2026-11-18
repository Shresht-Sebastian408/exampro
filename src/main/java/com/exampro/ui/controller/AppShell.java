package com.exampro.ui.controller;

import com.exampro.model.entity.User;
import com.exampro.model.enums.Role;
import com.exampro.service.AuthService;
import com.exampro.ui.animation.FadeSlideIn;
import com.exampro.ui.animation.PulseEffect;
import com.exampro.ui.animation.StaggerAnimator;
import com.exampro.ui.util.SceneRouter;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The application frame: sidebar on the left, one page at a time on the right.
 *
 * Navigation is a list of {@link NavItem}, each declaring which roles may
 * see it, so a student never gets a button that would throw on click. Pages
 * are built on demand, not up front, because most of them do database work.
 */
public class AppShell extends BorderPane implements SceneRouter.Disposable {

    private record NavItem(String label, Set<Role> roles, Supplier<Node> page) {}

    private final AuthService auth;
    private final SceneRouter router;
    private final StackPane content = new StackPane();
    private final List<Button> navButtons = new ArrayList<>();
    private ScaleTransition brandPulse;

    public AppShell(AuthService auth, SceneRouter router) {
        this.auth = auth;
        this.router = router;

        User user = auth.requireCurrentUser();

        setLeft(buildSidebar(user));
        setTop(buildHeader(user));
        setCenter(content);
        content.setPadding(new Insets(4, 16, 16, 16));

        if (!navButtons.isEmpty()) {
            navButtons.get(0).fire();
            StaggerAnimator.play(navButtons, FadeSlideIn.Direction.RIGHT, 45);
        }
    }

    @Override
    public void dispose() {
        if (brandPulse != null) {
            brandPulse.stop();
        }
    }

    private List<NavItem> navItems() {
        Set<Role> staff = Set.of(Role.ADMIN, Role.FACULTY);
        Set<Role> everyone = Set.of(Role.ADMIN, Role.FACULTY, Role.STUDENT);
        return List.of(
            new NavItem("Subjects", everyone, SubjectListView::new),
            new NavItem("Question bank", staff, QuestionBankView::new),
            new NavItem("Exams", staff, ExamBuilderView::new),
            new NavItem("Grading", staff, () -> new GradingView(auth.requireCurrentUser())),
            new NavItem("My exams", Set.of(Role.STUDENT),
                        () -> new StudentExamsView(auth.requireCurrentUser())),
            new NavItem("Live monitor", staff, LiveMonitorView::new),
            new NavItem("Analytics", staff, AnalyticsView::new),
            new NavItem("Users", Set.of(Role.ADMIN), () -> placeholder("User admin", "week 11")));
    }

    private VBox buildSidebar(User user) {
        Circle dot = new Circle(5, Color.web("#5b8def"));
        brandPulse = PulseEffect.attach(dot, Duration.seconds(2.4));
        Label brand = new Label("ExamPro");
        brand.getStyleClass().add("sidebar-brand");
        HBox brandRow = new HBox(8, dot, brand);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(0, 0, 18, 0));

        VBox sidebar = new VBox(4, brandRow);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(20, 14, 20, 16));
        sidebar.setPrefWidth(190);

        for (NavItem item : navItems()) {
            if (user.getRoles().stream().noneMatch(item.roles()::contains)) {
                continue;
            }
            Button button = new Button(item.label());
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> select(button, item));
            navButtons.add(button);
            sidebar.getChildren().add(button);
        }
        return sidebar;
    }

    private HBox buildHeader(User user) {
        Label who = new Label(user.getFullName());
        who.getStyleClass().add("heading");

        Label role = new Label(user.primaryRole().name());
        role.getStyleClass().add("role-chip");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button signOut = new Button("Sign out");
        signOut.setOnAction(e -> {
            auth.logout();
            router.showLogin();
        });

        HBox header = new HBox(12, who, role, spacer, signOut);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 10, 16));
        return header;
    }

    private void select(Button clicked, NavItem item) {
        navButtons.forEach(b -> b.getStyleClass().remove("nav-button-active"));
        clicked.getStyleClass().add("nav-button-active");

        Node page = item.page().get();
        content.getChildren().setAll(page);
        FadeSlideIn.play(page, FadeSlideIn.Direction.UP, 0);
    }

    /** Named pages that are not built yet, honest about when they arrive. */
    private Node placeholder(String title, String when) {
        Label heading = new Label(title);
        heading.getStyleClass().add("heading");
        Label note = new Label("Lands in " + when + " of the build order.");
        note.getStyleClass().add("status-label");

        VBox box = new VBox(8, heading, note);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("placeholder-page");
        return box;
    }
}
