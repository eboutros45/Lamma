package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.UserSession;
import com.lamma.cards.PaymentCardService;
import com.lamma.db.Database;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.TransferService;
import com.lamma.wallet.WalletService;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Owns the window and switches between screens. Also holds the services and the
 * in-memory {@link UserSession} that the screens share.
 */
public final class AppNavigator {

    private final Scene scene;
    private final UserSession session = new UserSession();
    private RegistrationService registrationService;
    private AuthenticationService authenticationService;
    private WalletService walletService;
    private TopUpService topUpService;
    private PaymentCardService paymentCardService;
    private TransferService transferService;

    /** Normal start-up: shows the startup screen, which connects to the database. */
    public AppNavigator(Stage stage) {
        this.scene = createScene(stage);
        show(new StartupView(this));
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

    /** For automated UI tests, including Send Money. */
    AppNavigator(Stage stage, RegistrationService registration, AuthenticationService authentication,
                 WalletService wallets, TopUpService topUps, PaymentCardService cards, TransferService transfers) {
        this.scene = createScene(stage);
        this.transferService = transfers;
        this.paymentCardService = cards;
        this.registrationService = registration;
        this.authenticationService = authentication;
        this.walletService = wallets;
        this.topUpService = topUps;
        showSignIn("", "");
    }

    private static Scene createScene(Stage stage) {
        Scene scene = new Scene(new StackPane(), 600, 600);
        scene.getStylesheets().add(AppNavigator.class.getResource("lamma.css").toExternalForm());
        stage.setTitle("LAMMA");
        stage.setScene(scene);
        return scene;
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

    void showSignIn(String phone, String notice) {
        show(new SignInView(this, phone, notice));
    }

    void showSignUpChoice() {
        show(new SignUpChoiceView(this));
    }

    void showPersonalSignUp() {
        show(new PersonalSignUpView(this));
    }

    /** Starts a session for the user who just authenticated and opens their dashboard. */
    void signedIn(AuthenticatedUser user) {
        session.signIn(user);
        showDashboard();
    }

    /** Only reachable with an active session; otherwise falls back to Sign In. */
    void showDashboard() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new DashboardView(this, session.currentUser().orElseThrow()));
    }

    /** Receive Money choices (signed-in users only). */
    void showReceive() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new ReceiveView(this));
    }

    /** Cash top-up flow (signed-in users only). */
    void showCashTopUp() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new CashTopUpView(this, session.currentUser().orElseThrow()));
    }

    /** Credit card top-up flow (signed-in users only). */
    void showCardTopUp() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new CardTopUpView(this, session.currentUser().orElseThrow()));
    }

    /** Saved cards (signed-in users only). */
    void showPaymentMethods(String notice) {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new PaymentMethodsView(this, session.currentUser().orElseThrow(), notice));
    }

    /** Add a card to Payment Methods (signed-in users only). */
    void showAddCard() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new AddCardView(this, session.currentUser().orElseThrow()));
    }

    /** Send Money (signed-in users only). */
    void showSendMoney() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new SendMoneyView(this, session.currentUser().orElseThrow()));
    }

    void signOut() {
        session.signOut();
        showSignIn("", "You have signed out.");
    }

    /** True if {@code screen} is the screen currently in the window. */
    boolean isShowing(Parent screen) {
        return scene.getRoot() == screen;
    }

    Parent currentScreen() {
        return scene.getRoot();
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

    private void show(Parent screen) {
        scene.setRoot(screen);
    }
}
