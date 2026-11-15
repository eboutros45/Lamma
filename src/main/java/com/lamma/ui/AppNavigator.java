package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.UserSession;
import com.lamma.cards.PaymentCardService;
import com.lamma.db.Database;
import com.lamma.ui.AppShell.Destination;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.TransferService;
import com.lamma.wallet.WalletService;
import java.util.prefs.Preferences;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Owns the window and decides what it shows. Also holds the services, the in-memory
 * {@link UserSession} and the theme settings that the screens share.
 *
 * <p>Signed-out screens (start-up, Sign In, Sign Up) are shown on their own, centred.
 * Signed-in screens are shown inside {@link AppShell} with the navigation. Either way,
 * {@link #currentScreen()} is the screen itself, which the screens use to discard late
 * background results ({@link #isShowing}).
 */
public final class AppNavigator {

    public static final double DEFAULT_WIDTH = 1040;
    public static final double DEFAULT_HEIGHT = 700;

    private final Scene scene;
    private final ThemeSettings theme;
    private final AppShell shell = new AppShell(this::navigateTo);
    private final UserSession session = new UserSession();
    private Parent current;
    private Destination destination = Destination.HOME;
    private RegistrationService registrationService;
    private AuthenticationService authenticationService;
    private WalletService walletService;
    private TopUpService topUpService;
    private PaymentCardService paymentCardService;
    private TransferService transferService;

    /** Normal start-up: shows the startup screen, which connects to the database. */
    public AppNavigator(Stage stage) {
        this.theme = ThemeSettings.forApplication();
        this.scene = createScene(stage);
        showAuth(new StartupView(this));
    }

    /** For automated UI tests: services are supplied directly and no database check runs. */
    AppNavigator(Stage stage, RegistrationService registration, AuthenticationService authentication,
                 WalletService wallets) {
        this(stage, registration, authentication, wallets, null);
    }

    /** For automated UI tests, including the top-up screens. */
    AppNavigator(Stage stage, RegistrationService registration, AuthenticationService authentication,
                 WalletService wallets, TopUpService topUps) {
        this(stage, registration, authentication, wallets, topUps, null);
    }

    /** For automated UI tests, including the card screens. */
    AppNavigator(Stage stage, RegistrationService registration, AuthenticationService authentication,
                 WalletService wallets, TopUpService topUps, PaymentCardService cards) {
        this(stage, registration, authentication, wallets, topUps, cards, null);
    }

    /** For automated UI tests, including Send Money. Theme settings use a separate test node. */
    AppNavigator(Stage stage, RegistrationService registration, AuthenticationService authentication,
                 WalletService wallets, TopUpService topUps, PaymentCardService cards, TransferService transfers) {
        this(stage, registration, authentication, wallets, topUps, cards, transfers,
                new ThemeSettings(Preferences.userRoot().node("com/lamma/ui-tests"), () -> false));
    }

    /** For automated UI tests that also control the theme settings. */
    AppNavigator(Stage stage, RegistrationService registration, AuthenticationService authentication,
                 WalletService wallets, TopUpService topUps, PaymentCardService cards, TransferService transfers,
                 ThemeSettings themeSettings) {
        this.theme = themeSettings;
        this.scene = createScene(stage);
        this.registrationService = registration;
        this.authenticationService = authentication;
        this.walletService = wallets;
        this.topUpService = topUps;
        this.paymentCardService = cards;
        this.transferService = transfers;
        showSignIn("", "");
    }

    private Scene createScene(Stage stage) {
        Scene newScene = new Scene(new StackPane(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        theme.attach(newScene);
        newScene.widthProperty().addListener((obs, old, width) -> shell.updateLayout(width.doubleValue()));
        stage.setTitle("LAMMA");
        stage.setScene(newScene);
        return newScene;
    }

    /** Called by the startup screen once the database is reachable and up to date. */
    void databaseReady(Database database) {
        PinHasher pinHasher = new PinHasher();
        this.registrationService = new RegistrationService(database, pinHasher);
        this.authenticationService = new AuthenticationService(database, pinHasher);
        this.walletService = new WalletService(database);
        this.topUpService = new TopUpService(database);
        this.paymentCardService = new PaymentCardService(database);
        this.transferService = new TransferService(database);
        showSignIn("", "");
    }

    // ---------- signed-out screens ----------

    void showSignIn(String phone, String notice) {
        showAuth(new SignInView(this, phone, notice));
    }

    void showSignUpChoice() {
        showAuth(new SignUpChoiceView(this));
    }

    void showPersonalSignUp() {
        showAuth(new PersonalSignUpView(this));
    }

    /** Starts a session for the user who just authenticated and opens their dashboard. */
    void signedIn(AuthenticatedUser user) {
        session.signIn(user);
        destination = Destination.HOME;
        showDashboard();
    }

    void signOut() {
        session.signOut();
        showSignIn("", "You have signed out.");
    }

    // ---------- destinations (signed-in only; otherwise Sign In) ----------

    void navigateTo(Destination target) {
        switch (target) {
            case HOME -> showDashboard();
            case WALLETS -> showWallets();
            case CARDS -> showPaymentMethods("");
            case PROFILE -> showProfile();
        }
    }

    void showDashboard() {
        if (requireSession()) {
            showDestination(new DashboardView(this, user()), Destination.HOME, false);
        }
    }

    void showWallets() {
        if (requireSession()) {
            showDestination(new WalletsView(this, user()), Destination.WALLETS, true);
        }
    }

    /** Saved cards (Cards destination). */
    void showPaymentMethods(String notice) {
        if (requireSession()) {
            showDestination(new PaymentMethodsView(this, user(), notice), Destination.CARDS, false);
        }
    }

    void showProfile() {
        if (requireSession()) {
            showDestination(new ProfileView(this, user()), Destination.PROFILE, true);
        }
    }

    // ---------- actions (shown with a Back button) ----------

    /** Personal wallet details and full history; Back returns to where it was opened from. */
    void showWalletDetail() {
        if (requireSession()) {
            Destination from = destination;
            showFlow(new WalletDetailView(this, user()), "Personal Wallet", () -> navigateTo(from), false);
        }
    }

    /** Add Money: cash or card top-up. */
    void showReceive() {
        if (requireSession()) {
            Destination from = destination;
            showFlow(new ReceiveView(this), "Add Money", () -> navigateTo(from), true);
        }
    }

    void showCashTopUp() {
        if (requireSession()) {
            showFlow(new CashTopUpView(this, user()), "Cash Top-Up", this::showReceive, true);
        }
    }

    void showCardTopUp() {
        if (requireSession()) {
            showFlow(new CardTopUpView(this, user()), "Card Top-Up", this::showReceive, true);
        }
    }

    void showAddCard() {
        if (requireSession()) {
            showFlow(new AddCardView(this, user()), "Add Card", () -> showPaymentMethods(""), true);
        }
    }

    void showSendMoney() {
        if (requireSession()) {
            Destination from = destination;
            showFlow(new SendMoneyView(this, user()), "Send Money", () -> navigateTo(from), true);
        }
    }

    // ---------- shared state ----------

    /** True if {@code screen} is the screen currently shown. */
    boolean isShowing(Parent screen) {
        return current == screen;
    }

    /** The screen currently shown (inside the shell when signed in). */
    Parent currentScreen() {
        return current;
    }

    /** The whole window content, including navigation (tests and screenshots). */
    Parent windowRoot() {
        return scene.getRoot();
    }

    AppShell shell() {
        return shell;
    }

    ThemeSettings themeSettings() {
        return theme;
    }

    UserSession session() {
        return session;
    }

    RegistrationService registrationService() {
        return registrationService;
    }

    AuthenticationService authenticationService() {
        return authenticationService;
    }

    WalletService walletService() {
        return walletService;
    }

    TopUpService topUpService() {
        return topUpService;
    }

    PaymentCardService paymentCardService() {
        return paymentCardService;
    }

    TransferService transferService() {
        return transferService;
    }

    // ---------- internals ----------

    private boolean requireSession() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return false;
        }
        return true;
    }

    private AuthenticatedUser user() {
        return session.currentUser().orElseThrow();
    }

    /** Signed-out screens: centred on the page, scrollable if the window is short. */
    private void showAuth(Parent screen) {
        VBox centre = new VBox(screen);
        centre.setAlignment(Pos.CENTER);
        centre.setStyle("-fx-padding: 24;");
        ScrollPane scroll = new ScrollPane(centre);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("activity-scroll");
        StackPane frame = new StackPane(scroll);
        frame.getStyleClass().add("auth-frame");
        current = screen;
        scene.setRoot(frame);
        AppShell.fadeIn(screen);
    }

    private void showDestination(Parent screen, Destination target, boolean scroll) {
        destination = target;
        enterShell();
        current = screen;
        shell.show(screen, target, null, null, scroll);
    }

    private void showFlow(Parent screen, String title, Runnable back, boolean scroll) {
        enterShell();
        current = screen;
        shell.show(screen, destination, title, back, scroll);
    }

    private void enterShell() {
        if (scene.getRoot() != shell) {
            scene.setRoot(shell);
            shell.updateLayout(scene.getWidth());
        }
    }
}
