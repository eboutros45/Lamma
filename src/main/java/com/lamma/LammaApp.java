package com.lamma;

import com.lamma.db.DatabaseException;
import com.lamma.db.DatabaseStatus;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

/**
 * LAMMA desktop application entry point.
 *
 * <p>P01 shell: shows the application window and the database connection status.
 * The database check runs on a background thread so the window never freezes,
 * and any failure is shown as a message instead of crashing the application.
 */
public class LammaApp extends Application {

    private final Label statusLabel = new Label();
    private final Button checkButton = new Button("Check database connection");

    @Override
    public void start(Stage stage) {
        Label title = new Label("LAMMA");
        title.setFont(Font.font("System", FontWeight.BOLD, 36));

        Label subtitle = new Label("Simulated digital wallet — Alpha 1");

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(440);
        statusLabel.setAlignment(Pos.CENTER);

        checkButton.setOnAction(event -> checkDatabase());

        VBox root = new VBox(16, title, subtitle, statusLabel, checkButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(32));

        stage.setTitle("LAMMA");
        stage.setScene(new Scene(root, 520, 360));
        stage.show();

        checkDatabase();
    }

    private void checkDatabase() {
        checkButton.setDisable(true);
        showStatus("Connecting to MySQL…", "#555555");

        Task<DatabaseStatus.Report> task = new Task<>() {
            @Override
            protected DatabaseStatus.Report call() throws DatabaseException {
                return DatabaseStatus.checkAndMigrate();
            }
        };
        task.setOnSucceeded(event -> {
            showStatus(task.getValue().summary(), "#1b7f3b");
            checkButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            String message = (error instanceof DatabaseException)
                    ? error.getMessage()
                    : "Unexpected error while checking the database: " + error;
            System.err.println("Database check failed: " + message);
            if (error != null && error.getCause() != null) {
                error.getCause().printStackTrace();
            }
            showStatus("Database unavailable. " + message, "#b00020");
            checkButton.setDisable(false);
        });

        Thread worker = new Thread(task, "database-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void showStatus(String text, String color) {
        statusLabel.setText(text);
        statusLabel.setStyle("-fx-text-fill: " + color + ";");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
