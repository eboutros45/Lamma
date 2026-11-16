package com.lamma.ui;

import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * A six-digit PIN entry: six large, separated boxes showing "•" for each digit entered.
 *
 * <p>Underneath is one real {@link PasswordField} that takes the keyboard input, so it behaves
 * like a normal field for the keyboard, focus traversal (Tab), screen readers and paste, without
 * one field per digit:
 * <ul>
 *   <li>only digits are accepted, at most {@link #LENGTH};</li>
 *   <li>the caret always stays at the end, so typing fills the next box ("advances") and
 *       Backspace removes the previous digit;</li>
 *   <li>pasting "12 34 56" (or any text) keeps its digits and fills the boxes; pasting six or more
 *       digits replaces what was there;</li>
 *   <li>when the sixth digit arrives, {@link #setOnComplete} is told once (sign-in submits then);</li>
 *   <li>the digits are never shown, can't be copied out (a password field), and are cleared when
 *       the screen is left.</li>
 * </ul>
 */
final class PinInput extends StackPane {

    static final int LENGTH = 6;

    private final PasswordField field = new PasswordField();
    private final StackPane[] cells = new StackPane[LENGTH];
    private final Label[] dots = new Label[LENGTH];
    private final String name;
    private Consumer<String> onComplete = pin -> { };

    /**
     * @param fieldId the id of the underlying field (e.g. "pinField")
     * @param name    what is entered, for screen readers (e.g. "PIN", "Confirm PIN")
     */
    PinInput(String fieldId, String name) {
        this.name = name;
        getStyleClass().add("pin-input");
        setId(fieldId + "Boxes");
        HBox boxes = new HBox(8);
        boxes.setAlignment(Pos.CENTER);
        for (int i = 0; i < LENGTH; i++) {
            dots[i] = new Label();
            dots[i].getStyleClass().add("pin-dot");
            cells[i] = new StackPane(dots[i]);
            cells[i].getStyleClass().add("pin-cell");
            cells[i].setMouseTransparent(true);
            boxes.getChildren().add(cells[i]);
        }
        boxes.setMouseTransparent(true);

        field.setId(fieldId);
        field.getStyleClass().add("pin-hidden-field");
        field.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        field.setTextFormatter(new TextFormatter<String>(change -> {
            String digits = change.getText().replaceAll("[^0-9]", "");
            if (digits.length() >= LENGTH) { // a whole PIN pasted: it replaces what was there
                change.setRange(0, change.getControlText().length());
                change.setText(digits.substring(0, LENGTH));
            } else {
                change.setText(digits);
                int over = change.getControlNewText().length() - LENGTH;
                if (over > 0) {
                    change.setText(digits.substring(0, Math.max(0, digits.length() - over)));
                }
            }
            int end = change.getControlNewText().length(); // keep the caret at the end
            change.setCaretPosition(end);
            change.setAnchor(end);
            return change;
        }));
        field.caretPositionProperty().addListener((obs, before, now) -> {
            if (now.intValue() != field.getLength()) {
                Platform.runLater(() -> field.positionCaret(field.getLength()));
            }
        });
        field.textProperty().addListener((obs, before, now) -> {
            refresh();
            if (now.length() == LENGTH && (before == null || before.length() < LENGTH)) {
                Platform.runLater(() -> {
                    if (field.getLength() == LENGTH) {
                        onComplete.accept(field.getText());
                    }
                });
            }
        });
        field.focusedProperty().addListener((obs, was, now) -> refresh());
        // Leaving the screen: forget the digits.
        sceneProperty().addListener((obs, before, now) -> {
            if (now == null) {
                field.clear();
            }
        });

        getChildren().addAll(boxes, field);
        setMaxWidth(USE_PREF_SIZE);
        refresh();
    }

    /** Called once each time the sixth digit is entered, with the PIN. */
    void setOnComplete(Consumer<String> onComplete) {
        this.onComplete = onComplete;
    }

    String getPin() {
        return field.getText();
    }

    boolean isComplete() {
        return field.getLength() == LENGTH;
    }

    /** Removes every digit. */
    void clear() {
        field.clear();
    }

    /** Marks the boxes red (e.g. after an incorrect PIN) until the next digit is typed. */
    void showError(boolean error) {
        for (StackPane cell : cells) {
            if (error) {
                if (!cell.getStyleClass().contains("error")) {
                    cell.getStyleClass().add("error");
                }
            } else {
                cell.getStyleClass().remove("error");
            }
        }
    }

    void focusInput() {
        field.requestFocus();
        field.positionCaret(field.getLength());
    }

    void setInputDisabled(boolean disabled) {
        field.setDisable(disabled);
        setOpacity(disabled ? 0.6 : 1);
    }

    /** The real input field (for focus handling and tests). */
    PasswordField field() {
        return field;
    }

    private void refresh() {
        int length = field.getLength();
        for (int i = 0; i < LENGTH; i++) {
            dots[i].setText(i < length ? "•" : "");
            setClass(cells[i], "filled", i < length);
            setClass(cells[i], "active", field.isFocused() && (i == length || (length == LENGTH && i == LENGTH - 1)));
        }
        if (length > 0) {
            showError(false);
        }
        field.setAccessibleText(name + ", " + length + " of " + LENGTH + " digits entered");
    }

    private static void setClass(StackPane cell, String styleClass, boolean on) {
        if (on && !cell.getStyleClass().contains(styleClass)) {
            cell.getStyleClass().add(styleClass);
        } else if (!on) {
            cell.getStyleClass().remove(styleClass);
        }
    }
}
