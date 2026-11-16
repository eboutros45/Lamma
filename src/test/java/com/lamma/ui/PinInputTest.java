package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The six-box PIN entry: typing, Backspace, paste, completion, masking and cleanup. Needs a display. */
class PinInputTest {

    private PinInput pin;
    private StackPane host;
    private final List<String> completed = new ArrayList<>();

    private static boolean displayAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("win")
                || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    @BeforeAll
    static void startJavaFx() {
        if (!displayAvailable()) {
            return;
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // started by another test class
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(displayAvailable(), "JavaFX UI tests need a display (macOS/Windows, or DISPLAY on Linux)");
        completed.clear();
        pin = onFx(() -> {
            PinInput p = new PinInput("pinField", "PIN");
            p.setOnComplete(completed::add);
            host = new StackPane(p);
            Stage stage = new Stage();
            stage.setScene(new Scene(host, 400, 200));
            stage.show();
            p.focusInput();
            return p;
        });
    }

    @Test
    void typedDigitsFillTheBoxesAndOtherKeysAreIgnored() throws Exception {
        typeKeys("1a2 3-");
        assertEquals("123", value());
        assertEquals(3, filledBoxes());
        assertEquals(List.of(), completed);
        typeKeys("4567"); // only two more fit
        assertEquals("123456", value());
        assertEquals(6, filledBoxes());
        flush();
        assertEquals(List.of("123456"), completed, "submitted once, when the sixth digit arrives");
    }

    @Test
    void backspaceRemovesThePreviousDigitAndTheCaretStaysAtTheEnd() throws Exception {
        typeKeys("1234");
        press(KeyCode.BACK_SPACE);
        assertEquals("123", value());
        onFx(() -> {
            pin.field().positionCaret(0); // e.g. Home or a click: the caret goes back to the end
            return null;
        });
        flush();
        typeKeys("9");
        assertEquals("1239", value(), "a digit always goes into the next box");
        press(KeyCode.BACK_SPACE);
        press(KeyCode.BACK_SPACE);
        assertEquals("12", value());
    }

    @Test
    void pastingDistributesDigitsAcrossTheBoxes() throws Exception {
        paste("12 34-56");
        assertEquals("123456", value());
        flush();
        assertEquals(List.of("123456"), completed);
        onFx(() -> {
            pin.clear();
            return null;
        });
        typeKeys("12");
        paste("7a8");
        assertEquals("1278", value(), "a partial paste continues where the boxes are");
        paste("9876543210");
        assertEquals("987654", value(), "a whole PIN replaces what was there; extra digits are dropped");
        flush();
        assertEquals(List.of("123456", "987654"), completed, "completed again after being cleared and refilled");
    }

    @Test
    void digitsAreNeverShownAndTheStateIsClearedWhenTheScreenIsLeft() throws Exception {
        typeKeys("4821");
        String shown = onFx(() -> texts(pin));
        assertFalse(shown.matches(".*[0-9].*"), "no digit is ever displayed: " + shown);
        assertEquals("••••", shown.replace("|", ""));
        assertEquals("PIN, 4 of 6 digits entered", onFx(() -> pin.field().getAccessibleText()));
        onFx(() -> {
            pin.showError(true);
            return null;
        });
        typeKeys("5");
        assertFalse(onFx(() -> pin.lookup(".pin-cell").getStyleClass().contains("error")), "typing clears the red boxes");
        onFx(() -> {
            host.getChildren().clear(); // the screen is left
            return null;
        });
        assertEquals("", value(), "the PIN is forgotten");
    }

    // ---------- helpers ----------

    private void typeKeys(String keys) throws Exception {
        for (char c : keys.toCharArray()) {
            onFx(() -> {
                pin.field().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(c), String.valueOf(c),
                        KeyCode.UNDEFINED, false, false, false, false));
                return null;
            });
        }
    }

    private void press(KeyCode code) throws Exception {
        onFx(() -> {
            pin.field().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
    }

    /** What a paste does: replace the selection (the caret is at the end) with the clipboard text. */
    private void paste(String text) throws Exception {
        onFx(() -> {
            pin.field().replaceSelection(text);
            return null;
        });
    }

    private String value() throws Exception {
        return onFx(() -> pin.getPin());
    }

    private long filledBoxes() throws Exception {
        return onFx(() -> pin.lookupAll(".pin-cell").stream().filter(n -> n.getStyleClass().contains("filled")).count());
    }

    private static void flush() throws Exception {
        onFx(() -> null); // let queued completion callbacks run
        onFx(() -> null);
    }

    private static String texts(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled l && l.getText() != null && !l.getText().isEmpty()) {
            sb.append(l.getText()).append('|');
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
