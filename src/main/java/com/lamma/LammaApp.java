package com.lamma;

import com.lamma.ui.AppNavigator;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * LAMMA desktop application entry point.
 *
 * <p>Flow: startup screen (database check) -> Sign In <-> Sign Up -> signed-in app (Home, Wallets, Cards, Profile).
 * Screens live in {@code com.lamma.ui}; database and sign-in logic live in
 * {@code com.lamma.db} and {@code com.lamma.auth}.
 */
public class LammaApp extends Application {

    static final double MIN_WIDTH = 380;
    static final double MIN_HEIGHT = 600;

    @Override
    public void start(Stage stage) {
        new AppNavigator(stage);
        // Open at the default size, but never larger than the screen.
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(AppNavigator.DEFAULT_WIDTH, screen.getWidth()));
        stage.setHeight(Math.min(AppNavigator.DEFAULT_HEIGHT, screen.getHeight()));
        // Small enough for the compact (bottom navigation) layout; every screen fits or scrolls.
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
