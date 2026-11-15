package com.lamma.ui;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseStatus;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * First screen: connects to MySQL and applies pending migrations (the P01 check).
 * On success it moves to Sign In; on failure it explains the problem and offers a retry.
 */
final class StartupView extends VBox {

    private final AppNavigator navigator;
    private final Label status = Screens.message("startupStatus");
    private final Button retry = new Button("Try again");

    StartupView(AppNavigator navigator) {
        this.navigator = navigator;
        getStyleClass().add("auth-card");
        retry.setId("retryButton");
        retry.setOnAction(event -> connect());
        getChildren().addAll(Screens.appTitle(), Screens.hint("Simulated digital wallet"), status, retry);
        connect();
    }

    private void connect() {
        retry.setVisible(false);
        status.getStyleClass().removeAll("message-error", "message-success");
        status.setText("Connecting to MySQL…");
        BackgroundWork.run(() -> {
            Database database = new Database(DatabaseConfig.load());
            DatabaseStatus.Report report = DatabaseStatus.checkAndMigrate(database);
            System.out.println(report.summary());
            return database;
        }, navigator::databaseReady, error -> {
            Screens.showError(status, "Database unavailable. " + error);
            retry.setVisible(true);
        });
    }
}
