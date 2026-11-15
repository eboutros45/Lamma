package com.lamma.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javafx.scene.Scene;

/**
 * The theme choice (System, Light or Dark) and applying it to the window.
 *
 * <p>The choice is saved with {@link Preferences} (built into Java: the registry on Windows,
 * a plist on macOS, a file under ~/.java on Linux), so it survives restarts. It is a
 * per-computer, per-OS-user setting and is not stored in MySQL.
 */
final class ThemeSettings {

    enum Mode { SYSTEM, LIGHT, DARK }

    static final String KEY = "theme";
    private static final String BASE = "lamma.css";

    private final Preferences preferences;
    private final BooleanSupplier systemIsDark;
    private final List<Scene> scenes = new ArrayList<>();
    private Mode mode;
    private Boolean systemDarkCache;

    ThemeSettings(Preferences preferences, BooleanSupplier systemIsDark) {
        this.preferences = preferences;
        this.systemIsDark = systemIsDark;
        this.mode = read(preferences);
    }

    /** The real application settings node. */
    static ThemeSettings forApplication() {
        return new ThemeSettings(Preferences.userRoot().node("com/lamma/app"), SystemTheme::isDark);
    }

    Mode mode() {
        return mode;
    }

    boolean isDark() {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemDark();
        };
    }

    /** Changes the theme, saves it and restyles every attached window immediately. */
    void setMode(Mode newMode) {
        mode = newMode;
        if (newMode == Mode.SYSTEM) {
            systemDarkCache = null; // re-read the OS setting
        }
        try {
            preferences.put(KEY, newMode.name());
            preferences.flush();
        } catch (BackingStoreException | SecurityException | IllegalStateException e) {
            System.err.println("Theme preference could not be saved: " + e.getMessage());
        }
        scenes.forEach(this::apply);
    }

    void attach(Scene scene) {
        scenes.add(scene);
        apply(scene);
    }

    String themeStylesheet() {
        return isDark() ? "theme-dark.css" : "theme-light.css";
    }

    private void apply(Scene scene) {
        scene.getStylesheets().setAll(
                ThemeSettings.class.getResource(BASE).toExternalForm(),
                ThemeSettings.class.getResource(themeStylesheet()).toExternalForm());
    }

    private boolean systemDark() {
        if (systemDarkCache == null) {
            systemDarkCache = systemIsDark.getAsBoolean();
        }
        return systemDarkCache;
    }

    private static Mode read(Preferences preferences) {
        try {
            return Mode.valueOf(preferences.get(KEY, Mode.SYSTEM.name()));
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            return Mode.SYSTEM;
        }
    }
}
