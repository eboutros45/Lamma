package com.lamma.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Asks the operating system whether it uses a dark appearance (for the "System" theme).
 *
 * <p>JavaFX 22+ answers this itself ({@code Platform.getPreferences().getColorScheme()});
 * LAMMA uses JavaFX 21 (Java 17 compatible), so that API is tried through reflection and,
 * when absent, the OS setting is read directly: the Windows registry, macOS
 * {@code defaults}, or GNOME {@code gsettings}. Any failure means "light".
 */
final class SystemTheme {

    private SystemTheme() {
    }

    static boolean isDark() {
        Boolean fromJavaFx = fromJavaFxPreferences();
        if (fromJavaFx != null) {
            return fromJavaFx;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return windowsIsDark(run(List.of("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme")));
            }
            if (os.contains("mac")) {
                return macIsDark(run(List.of("defaults", "read", "-g", "AppleInterfaceStyle")));
            }
            return gnomeIsDark(run(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")));
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }

    /** "AppsUseLightTheme    REG_DWORD    0x0" means dark. */
    static boolean windowsIsDark(String output) {
        return output.contains("AppsUseLightTheme") && output.trim().endsWith("0x0");
    }

    /** Prints "Dark" in dark mode; fails (no output) in light mode. */
    static boolean macIsDark(String output) {
        return output.trim().equalsIgnoreCase("Dark");
    }

    /** Prints e.g. 'prefer-dark' or 'default'. */
    static boolean gnomeIsDark(String output) {
        return output.toLowerCase(Locale.ROOT).contains("dark");
    }

    private static Boolean fromJavaFxPreferences() {
        try {
            Object prefs = Class.forName("javafx.application.Platform").getMethod("getPreferences").invoke(null);
            Object scheme = prefs.getClass().getMethod("getColorScheme").invoke(prefs);
            return "DARK".equals(String.valueOf(scheme));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null; // JavaFX 21: not available
        }
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream in = process.getInputStream()) {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
