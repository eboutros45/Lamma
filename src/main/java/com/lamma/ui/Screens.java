package com.lamma.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Small helpers so every screen is built the same way. */
final class Screens {

    private Screens() {
    }

    static VBox screen() {
        VBox box = new VBox();
        box.getStyleClass().add("screen");
        return box;
    }

    static Label appTitle() {
        Label label = new Label("LAMMA");
        label.getStyleClass().add("app-title");
        return label;
    }

    static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("screen-title");
        return label;
    }

    static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        return label;
    }

    /** A label for showing errors or confirmations; starts empty. */
    static Label message(String id) {
        Label label = new Label();
        label.setId(id);
        label.setWrapText(true);
        label.setMaxWidth(420);
        label.setMinHeight(Region.USE_PREF_SIZE); // show every line instead of cutting it off with "..."
        return label;
    }

    static void showError(Label label, String text) {
        label.getStyleClass().removeAll("message-error", "message-success");
        label.getStyleClass().add("message-error");
        label.setText(text);
    }

    static void showInfo(Label label, String text) {
        label.getStyleClass().removeAll("message-error", "message-success");
        label.setText(text);
    }

    static void showSuccess(Label label, String text) {
        label.getStyleClass().removeAll("message-error", "message-success");
        label.getStyleClass().add("message-success");
        label.setText(text);
    }

    static Button primaryButton(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("primary-button");
        button.setDefaultButton(true); // Enter key presses it
        return button;
    }

    static Button linkButton(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("link-button");
        return button;
    }
}
