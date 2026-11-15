package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.ui.ThemeSettings.Mode;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Theme choice and persistence (no display needed; uses a throwaway preferences node). */
class ThemeSettingsTest {

    private Preferences node;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("com/lamma/test-theme-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void defaultsToSystem() {
        assertEquals(Mode.SYSTEM, new ThemeSettings(node, () -> false).mode());
    }

    @Test
    void choiceIsSavedAndReadBackAfterRestart() {
        new ThemeSettings(node, () -> false).setMode(Mode.DARK);
        assertEquals(Mode.DARK, new ThemeSettings(node, () -> false).mode());
        new ThemeSettings(node, () -> false).setMode(Mode.LIGHT);
        assertEquals(Mode.LIGHT, new ThemeSettings(node, () -> true).mode());
    }

    @Test
    void unknownStoredValueFallsBackToSystem() {
        node.put(ThemeSettings.KEY, "PURPLE");
        assertEquals(Mode.SYSTEM, new ThemeSettings(node, () -> false).mode());
    }

    @Test
    void darkOrLightFollowsModeAndSystem() {
        ThemeSettings followsDarkSystem = new ThemeSettings(node, () -> true);
        assertTrue(followsDarkSystem.isDark());
        assertEquals("theme-dark.css", followsDarkSystem.themeStylesheet());
        followsDarkSystem.setMode(Mode.LIGHT);
        assertFalse(followsDarkSystem.isDark());
        assertEquals("theme-light.css", followsDarkSystem.themeStylesheet());

        ThemeSettings followsLightSystem = new ThemeSettings(node, () -> false);
        followsLightSystem.setMode(Mode.SYSTEM);
        assertFalse(followsLightSystem.isDark());
        followsLightSystem.setMode(Mode.DARK);
        assertTrue(followsLightSystem.isDark());
    }

    @Test
    void systemSettingParsers() {
        assertTrue(SystemTheme.windowsIsDark(
                "\r\nHKEY_CURRENT_USER\\...\\Personalize\r\n    AppsUseLightTheme    REG_DWORD    0x0\r\n"));
        assertFalse(SystemTheme.windowsIsDark("    AppsUseLightTheme    REG_DWORD    0x1\r\n"));
        assertFalse(SystemTheme.windowsIsDark("ERROR: The system was unable to find the specified registry key"));
        assertTrue(SystemTheme.macIsDark("Dark\n"));
        assertFalse(SystemTheme.macIsDark("The domain/default pair of (kCFPreferencesAnyApplication, "
                + "AppleInterfaceStyle) does not exist"));
        assertTrue(SystemTheme.gnomeIsDark("'prefer-dark'\n"));
        assertFalse(SystemTheme.gnomeIsDark("'default'\n"));
    }
}
