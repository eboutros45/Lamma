package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.ThemeSettings.Mode;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.WalletService;
import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Adaptive navigation, Back behaviour, the new Wallets / history / Profile screens and the
 * theme switch, driven through the real JavaFX views with fake services (no MySQL).
 */
class AppShellNavigationTest {

    private static final AuthenticatedUser LINA = new AuthenticatedUser(1, "Lina", "Haddad", "96170123456");

    private Preferences themeNode;
    private ThemeSettings theme;
    private AppNavigator navigator;
    private final List<WalletTransaction> history = new ArrayList<>();

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
        themeNode = Preferences.userRoot().node("com/lamma/test-shell-" + UUID.randomUUID());
        theme = new ThemeSettings(themeNode, () -> false);
        for (int i = 1; i <= 12; i++) {
            history.add(0, new WalletTransaction(i, 101, WalletTransaction.TYPE_CASH_TOP_UP, WalletTransaction.CREDIT,
                    new BigDecimal("1.00"), new BigDecimal(i + ".00"), WalletTransaction.STATUS_COMPLETED,
                    LocalDateTime.of(2026, 11, 20, 10, i)));
        }
        WalletService wallets = new WalletService(null) {
            @Override
            public PersonalWallet loadPersonalWallet(long userId) {
                return new PersonalWallet(101, userId, new BigDecimal("12.00"));
            }

            @Override
            public List<WalletTransaction> loadRecentTransactions(long userId, int limit) {
                return history.subList(0, Math.min(limit, history.size()));
            }
        };
        com.lamma.cards.PaymentCardService cards = new com.lamma.cards.PaymentCardService(null) {
            @Override
            public List<com.lamma.cards.SavedCard> listCards(long userId) {
                return List.of();
            }
        };
        com.lamma.wallet.SharedWalletService sharedWallets = new com.lamma.wallet.SharedWalletService(null) {
            @Override
            public List<com.lamma.wallet.SharedWallet.Summary> listForMember(long userId) {
                return List.of();
            }
        };
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), null, null, wallets, null, cards, null, theme);
            n.useSharedWalletService(sharedWallets);
            n.useMembershipService(new com.lamma.wallet.SharedWalletMembershipService(null) {
                @Override
                public List<IncomingInvitation> incomingInvitations(long userId) {
                    return List.of();
                }
            });
            return n;
        });
        onFx(() -> {
            navigator.signedIn(new AuthenticatedUser(1, "Lina", "Haddad", "96170123456"));
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (themeNode != null) {
            themeNode.removeNode();
        }
    }

    // ---------- adaptive navigation ----------

    @Test
    void wideWindowUsesSidebarAndCompactUsesBottomBarWithHysteresis() throws Exception {
        AppShell shell = navigator.shell();
        assertTrue(onFx(shell::isWide), "default 1040px window is wide");
        assertNotNull(onFx(shell::getLeft));
        assertNull(onFx(shell::getBottom));

        resize(500);
        assertFalse(onFx(shell::isWide));
        assertNotNull(onFx(shell::getBottom));
        assertNull(onFx(shell::getLeft));

        resize(840); // inside the dead band: stays compact
        assertFalse(onFx(shell::isWide));
        resize(AppShell.WIDE_AT);
        assertTrue(onFx(shell::isWide));
        resize(830); // inside the dead band: stays wide
        assertTrue(onFx(shell::isWide));
        resize(AppShell.COMPACT_BELOW - 1);
        assertFalse(onFx(shell::isWide));
    }

    @Test
    void resizingKeepsTheCurrentScreenAndItsState() throws Exception {
        onFx(() -> {
            navigator.showSendMoney();
            return null;
        });
        Parent before = onFx(navigator::currentScreen);
        Node phone = lookup("#recipientPhoneField");
        onFx(() -> {
            ((javafx.scene.control.TextInputControl) phone).setText("70 123 456");
            return null;
        });

        resize(500);
        resize(1000);

        assertSame(before, onFx(navigator::currentScreen), "same screen instance, not rebuilt");
        assertEquals("70 123 456", onFx(() -> ((javafx.scene.control.TextInputControl) phone).getText()));
    }

    @Test
    void navigationDestinationsOpenTheirScreens() throws Exception {
        nav("#navWallets");
        assertInstanceOf(WalletsView.class, onFx(navigator::currentScreen));
        assertTrue(onFx(() -> ((ToggleButton) navigator.windowRoot().lookup("#navWallets")).isSelected()));
        nav("#navCards");
        assertInstanceOf(PaymentMethodsView.class, onFx(navigator::currentScreen));
        nav("#navProfile");
        assertInstanceOf(ProfileView.class, onFx(navigator::currentScreen));
        nav("#navHome");
        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
        assertTrue(onFx(() -> ((ToggleButton) navigator.windowRoot().lookup("#navHome")).isSelected()));
    }

    @Test
    void destinationsRequireASession() throws Exception {
        onFx(() -> {
            navigator.signOut();
            for (AppShell.Destination d : AppShell.Destination.values()) {
                navigator.navigateTo(d);
            }
            navigator.showWalletDetail();
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
    }

    @Test
    void backReturnsToWhereTheActionWasOpened() throws Exception {
        nav("#navWallets");
        onFx(() -> {
            navigator.showSendMoney();
            return null;
        });
        assertEquals("Send Money", text(navigator.windowRoot(), "#flowTitle"));
        clickIn(navigator.windowRoot(), "#flowBackButton");
        assertInstanceOf(WalletsView.class, onFx(navigator::currentScreen));

        nav("#navHome");
        click("#receiveMoneyButton");
        assertInstanceOf(ReceiveView.class, onFx(navigator::currentScreen));
        click("#cashTopUpButton");
        assertInstanceOf(CashTopUpView.class, onFx(navigator::currentScreen));
        clickIn(navigator.windowRoot(), "#flowBackButton");
        assertInstanceOf(ReceiveView.class, onFx(navigator::currentScreen), "cash top-up -> Add Money");
        clickIn(navigator.windowRoot(), "#flowBackButton");
        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen), "Add Money -> Home");
    }

    @Test
    void homeShowsRecentActivityAndViewAllOpensFullHistory() throws Exception {
        waitUntil(() -> "$12.00".equals(text(navigator.currentScreen(), "#balanceLabel")));
        VBox recent = (VBox) lookup("#activityList");
        assertEquals(DashboardView.RECENT_ACTIVITY_LIMIT, (int) onFx(() -> recent.getChildren().size()));

        click("#viewAllActivityButton");
        assertInstanceOf(WalletDetailView.class, onFx(navigator::currentScreen));
        waitUntil(() -> "$12.00".equals(text(navigator.currentScreen(), "#detailBalanceLabel")));
        VBox all = (VBox) lookup("#historyList");
        assertEquals(12, (int) onFx(() -> all.getChildren().size()));
        clickIn(navigator.windowRoot(), "#flowBackButton");
        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
    }

    @Test
    void walletsListsThePersonalWalletAndTheSharedWalletSection() throws Exception {
        nav("#navWallets");
        waitUntil(() -> "$12.00".equals(text(navigator.currentScreen(), "#walletsBalanceLabel")));
        // Shared Wallet P01 replaced the placeholder note with the real (here empty) list.
        waitUntil(() -> lookup("#noSharedWalletsLabel") != null);
        assertNotNull(lookup("#createSharedWalletButton"));
        click("#personalWalletTile");
        assertInstanceOf(WalletDetailView.class, onFx(navigator::currentScreen));
        clickIn(navigator.windowRoot(), "#flowBackButton");
        assertInstanceOf(WalletsView.class, onFx(navigator::currentScreen));
    }

    // ---------- profile and theme ----------

    @Test
    void profileShowsAccountAndThemeSwitchAppliesAndPersists() throws Exception {
        nav("#navProfile");
        assertEquals("Lina Haddad", text(navigator.currentScreen(), "#profileNameLabel"));
        assertEquals("+961 70 123 456", text(navigator.currentScreen(), "#profilePhoneLabel"));
        ToggleButton system = (ToggleButton) lookup("#themeSystem");
        assertTrue(onFx(system::isSelected), "default System");
        assertTrue(stylesheets().endsWith("theme-light.css"), "system is light in this test");

        click("#themeDark");
        assertTrue(stylesheets().endsWith("theme-dark.css"), "applied immediately");
        assertEquals(Mode.DARK, new ThemeSettings(themeNode, () -> false).mode(), "saved");

        click("#themeLight");
        assertTrue(stylesheets().endsWith("theme-light.css"));
        assertEquals(Mode.LIGHT, new ThemeSettings(themeNode, () -> false).mode());

        nav("#navHome"); // the theme stays when moving around
        assertTrue(stylesheets().endsWith("theme-light.css"));
    }

    @Test
    void profileSignOutEndsTheSession() throws Exception {
        nav("#navProfile");
        click("#profileSignOutButton");
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        assertFalse(onFx(() -> navigator.session().isSignedIn()));
        assertNull(onFx(() -> navigator.windowRoot().lookup("#navHome")), "no navigation when signed out");
    }

    // ---------- helpers ----------

    private String stylesheets() throws Exception {
        return onFx(() -> String.join(",", navigator.windowRoot().getScene().getStylesheets()));
    }

    private void resize(double width) throws Exception {
        onFx(() -> {
            navigator.shell().updateLayout(width);
            return null;
        });
    }

    private void nav(String id) throws Exception {
        clickIn(navigator.windowRoot(), id);
    }

    private Node lookup(String id) {
        try {
            return onFx(() -> {
                buildSkins();
                return navigator.currentScreen().lookup(id);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void click(String id) throws Exception {
        Node node = lookup(id);
        assertNotNull(node, "no node " + id);
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private void clickIn(Parent root, String id) throws Exception {
        Node node = onFx(() -> root.lookup(id));
        assertNotNull(node, "no node " + id);
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private String text(Parent root, String id) {
        try {
            return onFx(() -> {
                buildSkins();
                Node n = root.lookup(id);
                return n instanceof Label l ? l.getText() : null;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The window is never shown, so controls' internal parts (scroll pane content, button
     * graphics) only exist after CSS and layout run on the whole window. Twice, because a
     * scroll pane's content is added during the first pass.
     */
    private void buildSkins() {
        Parent root = navigator.windowRoot();
        for (int i = 0; i < 2; i++) {
            root.applyCss();
            root.layout();
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void waitUntil(Supplier<Boolean> condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within 5 seconds");
    }
}
