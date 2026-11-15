package com.lamma.ui;

import com.lamma.ui.Icons.Icon;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Small helpers so every screen is built from the same components (styled in lamma.css). */
final class Screens {

    private Screens() {
    }

    static VBox screen() {
        VBox box = new VBox();
        box.getStyleClass().add("screen");
        return box;
    }

    /** The LAMMA brand lockup: logo mark and name. */
    static Node appTitle() {
        StackPane mark = new StackPane(new Label("L"));
        mark.getStyleClass().add("brand-mark");
        Label name = new Label("LAMMA");
        name.getStyleClass().add("brand-name");
        HBox lockup = new HBox(10, mark, name);
        lockup.setAlignment(Pos.CENTER);
        lockup.setMaxWidth(Region.USE_PREF_SIZE);
        return lockup;
    }

    static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("screen-title");
        label.setWrapText(true);
        return label;
    }

    static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        label.setMaxWidth(520); // long hints wrap instead of being cut off with "..."
        return label;
    }

    static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("caption");
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

    /** A primary button that stretches to the width of its column (forms). */
    static Button wideButton(Button button) {
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    static Button withIcon(Button button, Icon icon) {
        button.setGraphic(Icons.icon(icon, 18));
        button.setGraphicTextGap(8);
        return button;
    }

    /** A round tinted circle holding an icon (activity rows, option tiles). */
    static StackPane iconChip(Icon icon, boolean credit) {
        StackPane chip = new StackPane(Icons.icon(icon, 20));
        chip.getStyleClass().add("icon-chip");
        if (credit) {
            chip.getStyleClass().add("credit");
        }
        return chip;
    }

    /** A round badge with the person's initials. */
    static StackPane avatar(String firstName, String lastName, boolean large) {
        String initials = initial(firstName) + initial(lastName);
        StackPane avatar = new StackPane(new Label(initials));
        avatar.getStyleClass().add("avatar");
        if (large) {
            avatar.getStyleClass().add("large");
        }
        return avatar;
    }

    /** Label above a field, for forms. */
    static VBox field(String label, Node control) {
        Label text = new Label(label);
        text.getStyleClass().add("form-label");
        VBox box = new VBox(6, text, control);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** A clickable tile: icon chip, title and description, chevron. */
    static Button optionTile(Icon icon, String title, String description, String id) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        Label text = caption(description);
        text.getStyleClass().add("hint");
        VBox texts = new VBox(2, heading, text);
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox content = new HBox(14, iconChip(icon, false), texts, spacer, Icons.icon(Icon.CHEVRON_RIGHT, 18));
        content.setAlignment(Pos.CENTER_LEFT);
        Button tile = new Button();
        tile.setId(id);
        tile.setGraphic(content);
        tile.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        tile.getStyleClass().add("option-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        content.prefWidthProperty().bind(tile.widthProperty().subtract(34));
        return tile;
    }

    /**
     * A green check that pops in. Only used on screens shown after the database has
     * confirmed the operation, so it never celebrates something that has not happened.
     */
    static Node successMark() {
        StackPane mark = new StackPane(Icons.icon(Icon.CHECK_CIRCLE, 56));
        mark.getStyleClass().add("success-badge");
        ScaleTransition pop = new ScaleTransition(Duration.millis(220), mark);
        pop.setFromX(0.6);
        pop.setFromY(0.6);
        pop.setToX(1);
        pop.setToY(1);
        FadeTransition fade = new FadeTransition(Duration.millis(220), mark);
        fade.setFromValue(0);
        fade.setToValue(1);
        pop.play();
        fade.play();
        return mark;
    }

    private static String initial(String name) {
        return name == null || name.isBlank() ? "" : name.strip().substring(0, 1).toUpperCase();
    }
}
