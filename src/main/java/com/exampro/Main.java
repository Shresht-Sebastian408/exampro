package com.exampro;

import com.exampro.config.ConnectionPool;
import com.exampro.config.schema.DataSeeder;
import com.exampro.config.schema.SchemaInitializer;
import com.exampro.exception.ExamProException;
import com.exampro.ui.ExamProApp;
import javafx.application.Application;

/**
 * Entry point.
 *
 * This class deliberately does not extend {@link Application}. Launching
 * JavaFX from a non-Application main class is what lets the shaded jar run
 * off a plain classpath, with no module path and no jlink step: the
 * "JavaFX runtime components are missing" error only fires when the main
 * class is itself the Application subclass.
 *
 * Schema setup and seeding run here, before the stage opens, so the UI
 * never queries a database that does not exist yet.
 */
public final class Main {

    public static void main(String[] args) {
        try {
            new SchemaInitializer().initialize();
            if (new DataSeeder().seedIfEmpty()) {
                System.out.println("Seeded demo data. Logins: admin/"
                    + DataSeeder.DEFAULT_ADMIN_PASSWORD + ", faculty1/"
                    + DataSeeder.DEFAULT_FACULTY_PASSWORD + ", student01/"
                    + DataSeeder.DEFAULT_STUDENT_PASSWORD);
            }
        } catch (ExamProException e) {
            // No stage exists yet, so there is nowhere to show a dialog.
            // Fail loudly on the console and stop rather than opening a
            // window onto a broken database.
            System.err.println("Startup failed: " + e.getMessage());
            e.printStackTrace();
            ConnectionPool.shutdown();
            System.exit(1);
        }

        Application.launch(ExamProApp.class, args);
    }

    private Main() {}
}
