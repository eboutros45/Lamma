package com.lamma;

import com.lamma.ui.AppNavigator;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * LAMMA desktop application entry point.
 *
 * <p>Flow: startup screen (database check) -> Sign In <-> Sign Up -> signed-in placeholder.
 * Screens live in {@code com.lamma.ui}; database and sign-in logic live in
 * {@code com.lamma.db} and {@code com.lamma.auth}.
 */
public class LammaApp extends Application {

    @Override
    public void start(Stage stage) {
        new AppNavigator(stage);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
