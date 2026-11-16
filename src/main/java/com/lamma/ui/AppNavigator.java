package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AccountService;
import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.UserSession;
import com.lamma.cards.PaymentCardService;
import com.lamma.db.Database;
import com.lamma.ui.AppShell.Destination;
import com.lamma.contacts.ContactService;
import com.lamma.wallet.ExpenseService;
import com.lamma.wallet.GiftDeliveryService;
import com.lamma.wallet.SharedWalletFundsService;
import com.lamma.wallet.SharedWalletMembershipService;
import com.lamma.wallet.SharedWalletService;
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
    private SharedWalletService sharedWalletService;
    private SharedWalletMembershipService membershipService;
    private SharedWalletFundsService fundsService;
    private AccountService accountService;
    private ContactService contactService;
    private ExpenseService expenseService;
    private GiftDeliveryService giftService;

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
        this.sharedWalletService = new SharedWalletService(database);
        this.membershipService = new SharedWalletMembershipService(database);
        this.fundsService = new SharedWalletFundsService(database);
        this.accountService = new AccountService(database, pinHasher);
        this.contactService = new ContactService(database);
        this.expenseService = new ExpenseService(database);
        this.giftService = new GiftDeliveryService(database);
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
        showProfile("");
    }

    /** Profile, with an optional success message (e.g. after changing a setting). */
    void showProfile(String notice) {
        if (requireSession()) {
            showDestination(new ProfileView(this, user(), notice), Destination.PROFILE, true);
        }
    }

    /** Edit name, change phone number or change PIN; Back returns to Profile. */
    void showAccountSettings(AccountSettingsView.Mode mode) {
        if (requireSession()) {
            showFlow(new AccountSettingsView(this, user(), mode), mode.title(), () -> showProfile(""), true);
        }
    }

    /** The user's contact book (import, search, delete); Back returns to Profile. */
    void showContacts() {
        if (requireSession()) {
            showFlow(new ContactsView(this, user()), "Contacts", () -> showProfile(""), true);
        }
    }

    /**
     * The signed-in user changed their own name or phone number: the same sign-in continues with
     * the new details. Ignored if that sign-in has ended meanwhile.
     */
    boolean accountUpdated(AuthenticatedUser before, AuthenticatedUser updated) {
        return session.replace(before, updated);
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

    /** New shared wallet; Back returns to Wallets. */
    void showCreateSharedWallet() {
        if (requireSession()) {
            showFlow(new CreateSharedWalletView(this, user()), "New Shared Wallet", this::showWallets, true);
        }
    }

    /** One shared wallet (members only; the service checks membership); Back returns to Wallets. */
    void showSharedWalletDetail(long walletId) {
        if (requireSession()) {
            showFlow(new SharedWalletDetailView(this, user(), walletId), "Shared Wallet", this::showWallets, true);
        }
    }

    /** Owner invites a member by phone; Back returns to the wallet. */
    void showInviteMember(long walletId) {
        if (requireSession()) {
            showFlow(new InviteMemberView(this, user(), walletId), "Invite Member",
                    () -> showSharedWalletDetail(walletId), true);
        }
    }

    /** Owner's QR invitation; Back returns to the wallet. */
    void showQrInvite(long walletId) {
        if (requireSession()) {
            showFlow(new QrInviteView(this, user(), walletId), "QR Invitation",
                    () -> showSharedWalletDetail(walletId), true);
        }
    }

    /** Contribute, Withdraw or Send Money for a shared wallet; Back returns to the wallet. */
    void showSharedTransfer(long walletId, SharedTransferView.Mode mode) {
        if (requireSession()) {
            showFlow(new SharedTransferView(this, user(), walletId, mode), mode.title(),
                    () -> showSharedWalletDetail(walletId), true);
        }
    }

    /** A shared wallet's full transaction history; Back returns to the wallet. */
    void showSharedWalletHistory(long walletId) {
        if (requireSession()) {
            showFlow(new SharedWalletHistoryView(this, user(), walletId), "Shared Wallet History",
                    () -> showSharedWalletDetail(walletId), false);
        }
    }

    /** Send Gift (Owner of a gift wallet); Back returns to the wallet. */
    void showSendGift(long walletId) {
        if (requireSession()) {
            showFlow(new GiftDeliveryView(this, user(), walletId), "Send Gift", () -> showSharedWalletDetail(walletId), true);
        }
    }

    /** New shared expense; Back returns to the wallet. */
    void showCreateExpense(long walletId) {
        if (requireSession()) {
            showFlow(new CreateExpenseView(this, user(), walletId), "New Expense", () -> showSharedWalletDetail(walletId), true);
        }
    }

    /** One expense (with an optional success message); Back returns to its wallet. */
    void showExpense(long expenseId, long walletId, String notice) {
        if (requireSession()) {
            showFlow(new ExpenseDetailView(this, user(), expenseId, walletId, notice), "Expense",
                    () -> showSharedWalletDetail(walletId), true);
        }
    }

    /** Revise an expense's unpaid shares; Back returns to the expense. */
    void showReviseExpense(long expenseId, long walletId) {
        if (requireSession()) {
            showFlow(new ReviseExpenseView(this, user(), expenseId, walletId), "Revise Expense",
                    () -> showExpense(expenseId, walletId, ""), true);
        }
    }

    /** Open an invitation code to ask to join; Back returns to Wallets. */
    void showJoinWallet() {
        if (requireSession()) {
            showFlow(new JoinWalletView(this, user()), "Join a Shared Wallet", this::showWallets, true);
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

    SharedWalletService sharedWalletService() {
        return sharedWalletService;
    }

    /** For automated UI tests: supplies the shared-wallet service. */
    void useSharedWalletService(SharedWalletService service) {
        this.sharedWalletService = service;
    }

    SharedWalletMembershipService membershipService() {
        return membershipService;
    }

    /** For automated UI tests: supplies the membership service. */
    void useMembershipService(SharedWalletMembershipService service) {
        this.membershipService = service;
    }

    SharedWalletFundsService fundsService() {
        return fundsService;
    }

    /** For automated UI tests: supplies the shared-wallet money service. */
    void useFundsService(SharedWalletFundsService service) {
        this.fundsService = service;
    }

    AccountService accountService() {
        return accountService;
    }

    /** For automated UI tests: supplies the account settings service. */
    void useAccountService(AccountService service) {
        this.accountService = service;
    }

    ContactService contactService() {
        return contactService;
    }

    /** For automated UI tests: supplies the contact book service. */
    void useContactService(ContactService service) {
        this.contactService = service;
    }

    GiftDeliveryService giftService() {
        return giftService;
    }

    /** For automated UI tests: supplies the gift delivery service. */
    void useGiftService(GiftDeliveryService service) {
        this.giftService = service;
    }

    ExpenseService expenseService() {
        return expenseService;
    }

    /** For automated UI tests: supplies the shared expense service. */
    void useExpenseService(ExpenseService service) {
        this.expenseService = service;
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
