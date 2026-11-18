package com.exampro.ui.util;

import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Every dialog in the application comes from here.
 *
 * The rule from the plan is that a stack trace never reaches the user. The
 * message is a sentence they can act on; the trace goes into an expandable
 * pane so it is available when someone is actually debugging, and collapsed
 * otherwise.
 */
public final class AlertFactory {

    private AlertFactory() {}

    public static void info(String header, String message) {
        show(Alert.AlertType.INFORMATION, "ExamPro", header, message);
    }

    public static void warn(String header, String message) {
        show(Alert.AlertType.WARNING, "ExamPro", header, message);
    }

    public static void error(String header, String message) {
        show(Alert.AlertType.ERROR, "ExamPro", header, message);
    }

    public static void error(String header, Throwable cause) {
        Alert alert = base(Alert.AlertType.ERROR, "ExamPro", header,
                           cause.getMessage() == null ? cause.toString() : cause.getMessage());

        TextArea trace = new TextArea(stackTraceOf(cause));
        trace.setEditable(false);
        trace.setWrapText(false);
        VBox.setVgrow(trace, Priority.ALWAYS);

        VBox content = new VBox(6, new Label("Technical detail:"), trace);
        alert.getDialogPane().setExpandableContent(content);
        alert.showAndWait();
    }

    private static void show(Alert.AlertType type, String title, String header, String message) {
        base(type, title, header, message).showAndWait();
    }

    private static Alert base(Alert.AlertType type, String title, String header, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.setResizable(true);
        return alert;
    }

    private static String stackTraceOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        // Depth cap rather than an identity set: a self-referencing cause chain
        // would otherwise spin forever, and five frames of causes is already
        // more than any dialog needs.
        Throwable current = t;
        for (int depth = 0; current != null && depth < 5; depth++) {
            sb.append(current).append('\n');
            for (StackTraceElement el : current.getStackTrace()) {
                sb.append("    at ").append(el).append('\n');
            }
            Throwable next = current.getCause();
            if (next == null || next == current) {
                break;
            }
            sb.append("Caused by: ");
            current = next;
        }
        return sb.toString();
    }
}
