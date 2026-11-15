package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.MemberRole;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.SharedWallet;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.WalletService;
import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared-wallet screens through the real JavaFX views with a fake service (no MySQL). */
class SharedWalletFlowTest {

    private static final AuthenticatedUser LINA = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
    private static final AuthenticatedUser RAMI = new AuthenticatedUser(2, "Rami", "Khoury", "96170123456");

    private final FakeShared shared = new FakeShared();
    private AppNavigator navigator;

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
        WalletService wallets = new WalletService(null) {
            @Override
            public PersonalWallet loadPersonalWallet(long userId) {
                return new PersonalWallet(100 + userId, userId, new BigDecimal("50.00"));
            }

            @Override
            public List<WalletTransaction> loadRecentTransactions(long userId, int limit) {
                return List.of();
            }
        };
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), null, null, wallets);
            n.useSharedWalletService(shared);
            n.useMembershipService(new com.lamma.wallet.SharedWalletMembershipService(null) {
                @Override
                public List<IncomingInvitation> incomingInvitations(long userId) {
                    return List.of();
                }

                @Override
                public List<JoinRequest> pendingJoinRequests(long ownerId, long walletId) {
                    return List.of();
                }

                @Override
                public List<OutgoingInvitation> pendingInvitations(long ownerId, long walletId) {
                    return List.of();
                }
            });
            return n;
        });
    }

    @Test
    void sharedWalletScreensRequireASession() throws Exception {
        onFx(() -> {
            navigator.showCreateSharedWallet();
            navigator.showSharedWalletDetail(1);
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
    }

    @Test
    void emptyStateOffersCreateSharedWallet() throws Exception {
        openWallets(LINA);
        waitUntil(() -> lookup("#noSharedWalletsLabel") != null);
        assertNotNull(lookup("#createSharedWalletButton"));
        Node newButton = lookup("#newSharedWalletButton");
        assertFalse(onFx(newButton::isVisible), "header action hidden when empty");
    }

    @Test
    void createValidatesThenOpensDetailsWithCreatorAsOwnerAndListsTheWallet() throws Exception {
        openWallets(LINA);
        waitUntil(() -> lookup("#createSharedWalletButton") != null);
        click("#createSharedWalletButton");
        assertInstanceOf(CreateSharedWalletView.class, onFx(navigator::currentScreen));

        type("#sharedTargetField", "-5");
        click("#createSharedWalletSubmit");
        String errors = text("#createSharedWalletMessage");
        assertTrue(errors.contains("Wallet name is required."), errors);
        assertTrue(errors.contains("Target amount"), errors);
        assertEquals(0, shared.creates.get(), "nothing submitted for invalid input");

        type("#sharedNameField", "Beirut Trip");
        type("#sharedPurposeField", "Summer trip");
        type("#sharedDescriptionField", "Hotel and food");
        type("#sharedTargetField", "1500");
        click("#createSharedWalletSubmit");

        waitUntil(() -> lookup("#sharedWalletName") != null);
        assertInstanceOf(SharedWalletDetailView.class, onFx(navigator::currentScreen));
        assertEquals("Beirut Trip", text("#sharedWalletName"));
        assertEquals("$0.00", text("#sharedWalletBalance"));
        assertEquals("You: Owner", text("#myRoleBadge"));
        assertEquals("Summer trip", text("#sharedWalletPurpose"));
        assertEquals("Hotel and food", text("#sharedWalletDescription"));
        assertEquals("$0.00 of $1,500.00 (0%)", text("#targetLabel"));
        assertEquals("Owner", text("#memberRole-1"));
        assertTrue(screenText().contains("Lina Haddad (you)"));
        // Shared Wallet P02 added the Owner's membership actions; still nothing for money or expenses.
        assertEquals(List.of("inviteMemberButton", "shareQrButton"), buttonsOnScreen());

        clickIn(navigator.windowRoot(), "#flowBackButton");
        assertInstanceOf(WalletsView.class, onFx(navigator::currentScreen));
        waitUntil(() -> lookup("#sharedWallet-1") != null);
        String tile = textUnder(lookup("#sharedWallet-1"));
        assertTrue(tile.contains("Beirut Trip") && tile.contains("Summer trip") && tile.contains("$0.00")
                && tile.contains("of $1,500.00 target"), tile);
        Node newButton = lookup("#newSharedWalletButton");
        assertTrue(onFx(newButton::isVisible));

        click("#sharedWallet-1");
        waitUntil(() -> "Beirut Trip".equals(text("#sharedWalletName")));
    }

    @Test
    void doubleClickingCreateMakesOneWallet() throws Exception {
        openCreateForm();
        type("#sharedNameField", "Rent");
        shared.blockCreate = new CountDownLatch(1);
        click("#createSharedWalletSubmit");
        click("#createSharedWalletSubmit");
        click("#createSharedWalletSubmit");
        shared.blockCreate.countDown();
        waitUntil(() -> lookup("#sharedWalletName") != null);
        assertEquals(1, shared.creates.get());
    }

    @Test
    void createFailureShowsErrorAndCanBeRetried() throws Exception {
        openCreateForm();
        type("#sharedNameField", "Rent");
        shared.failNextCreate = true;
        click("#createSharedWalletSubmit");
        waitUntil(() -> text("#createSharedWalletMessage").contains("could not be created"));
        assertInstanceOf(CreateSharedWalletView.class, onFx(navigator::currentScreen));
        Node submit = lookup("#createSharedWalletSubmit");
        assertFalse(onFx(submit::isDisabled));

        click("#createSharedWalletSubmit");
        waitUntil(() -> lookup("#sharedWalletName") != null);
    }

    @Test
    void nonMemberSeesNotAvailableAndNoWalletData() throws Exception {
        shared.createFor(1, "Lina's Secret Wallet");
        signIn(RAMI);
        onFx(() -> {
            navigator.showSharedWalletDetail(1);
            return null;
        });
        waitUntil(() -> lookup("#sharedWalletMessage") != null);
        assertEquals(SharedWalletService.NOT_AVAILABLE_MESSAGE, text("#sharedWalletMessage"));
        assertNull(lookup("#retrySharedWalletButton"), "retrying would not help");
        assertFalse(screenText().contains("Lina's Secret Wallet"));
    }

    @Test
    void loadErrorShowsRetryWhichRecovers() throws Exception {
        shared.createFor(1, "Trip");
        signIn(LINA);
        shared.failNextDetails = true;
        onFx(() -> {
            navigator.showSharedWalletDetail(1);
            return null;
        });
        waitUntil(() -> lookup("#retrySharedWalletButton") != null);
        click("#retrySharedWalletButton");
        waitUntil(() -> "Trip".equals(text("#sharedWalletName")));
    }

    @Test
    void previousUsersWalletListNeverAppearsAfterSwitchingAccounts() throws Exception {
        shared.createFor(1, "Lina's Secret Wallet");
        signIn(LINA);
        shared.blockList = new CountDownLatch(1);
        onFx(() -> {
            navigator.showWallets(); // Lina's shared list is now loading (blocked)
            navigator.signOut();
            navigator.signedIn(fresh(RAMI));
            navigator.showWallets();
            return null;
        });
        shared.blockList.countDown();
        waitUntil(() -> lookup("#noSharedWalletsLabel") != null);
        Thread.sleep(300);
        assertFalse(screenText().contains("Lina's Secret Wallet"));
    }

    // ---------- helpers ----------

    private static AuthenticatedUser fresh(AuthenticatedUser u) {
        return new AuthenticatedUser(u.userId(), u.firstName(), u.lastName(), u.phoneNumber());
    }

    private void signIn(AuthenticatedUser user) throws Exception {
        onFx(() -> {
            navigator.signedIn(fresh(user));
            return null;
        });
    }

    private void openWallets(AuthenticatedUser user) throws Exception {
        signIn(user);
        clickIn(navigator.windowRoot(), "#navWallets");
        assertInstanceOf(WalletsView.class, onFx(navigator::currentScreen));
    }

    private void openCreateForm() throws Exception {
        signIn(LINA);
        onFx(() -> {
            navigator.showCreateSharedWallet();
            return null;
        });
    }

    private List<String> buttonsOnScreen() throws Exception {
        return onFx(() -> {
            buildSkins();
            List<String> ids = new ArrayList<>();
            collectButtons(navigator.currentScreen(), ids);
            return ids;
        });
    }

    private static void collectButtons(Node node, List<String> ids) {
        if (node instanceof ButtonBase b) {
            ids.add(String.valueOf(b.getId()));
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collectButtons(child, ids);
            }
        }
    }

    private void buildSkins() {
        Parent root = navigator.windowRoot();
        for (int i = 0; i < 2; i++) {
            root.applyCss();
            root.layout();
        }
    }

    private String screenText() {
        try {
            return onFx(() -> {
                buildSkins();
                StringBuilder sb = new StringBuilder("|");
                collect(navigator.currentScreen(), sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String textUnder(Node node) {
        try {
            return onFx(() -> {
                StringBuilder sb = new StringBuilder("|");
                collect(node, sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('|');
        }
        if (node instanceof Labeled l && l.getGraphic() != null) {
            collect(l.getGraphic(), sb);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
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

    private void type(String id, String value) throws Exception {
        Node node = lookup(id);
        onFx(() -> {
            ((TextInputControl) node).setText(value);
            return null;
        });
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

    private String text(String id) {
        Node node = lookup(id);
        try {
            return node == null ? "" : onFx(() -> node instanceof Label label ? label.getText() : "");
        } catch (Exception e) {
            throw new IllegalStateException(e);
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

    /** In-memory shared wallets with membership checks, like the real service. */
    private static final class FakeShared extends SharedWalletService {
        private final Map<Long, SharedWallet.Summary> wallets = new ConcurrentHashMap<>();
        private final Map<Long, Long> ownerOf = new ConcurrentHashMap<>();
        private final AtomicLong nextId = new AtomicLong(1);
        final AtomicInteger creates = new AtomicInteger();
        volatile boolean failNextCreate;
        volatile boolean failNextDetails;
        volatile CountDownLatch blockCreate;
        volatile CountDownLatch blockList;

        FakeShared() {
            super(null);
        }

        long createFor(long userId, String name) {
            long id = nextId.getAndIncrement();
            wallets.put(id, new SharedWallet.Summary(id, name, new BigDecimal("0.00"), null, null, null, MemberRole.OWNER));
            ownerOf.put(id, userId);
            return id;
        }

        @Override
        public long create(long creatorUserId, NewSharedWallet input) throws DatabaseException {
            creates.incrementAndGet();
            await(blockCreate);
            if (failNextCreate) {
                failNextCreate = false;
                throw new DatabaseException("The shared wallet could not be created because of a database problem.");
            }
            long id = nextId.getAndIncrement();
            wallets.put(id, new SharedWallet.Summary(id, input.name(), new BigDecimal("0.00"), input.purpose(),
                    input.description(), input.targetAmount(), MemberRole.OWNER));
            ownerOf.put(id, creatorUserId);
            return id;
        }

        @Override
        public List<SharedWallet.Summary> listForMember(long userId) {
            if (userId == 1) {
                await(blockList);
            }
            return wallets.values().stream().filter(w -> ownerOf.get(w.walletId()) == userId).toList();
        }

        @Override
        public SharedWallet.Details details(long userId, long walletId) throws ValidationException, DatabaseException {
            if (failNextDetails) {
                failNextDetails = false;
                throw new DatabaseException("Your shared wallets could not be loaded because the database is unavailable.");
            }
            SharedWallet.Summary w = wallets.get(walletId);
            if (w == null || ownerOf.get(walletId) != userId) {
                throw new ValidationException(NOT_AVAILABLE_MESSAGE);
            }
            return new SharedWallet.Details(w, List.of(new SharedWallet.Member(userId,
                    userId == 1 ? "Lina" : "Rami", userId == 1 ? "Haddad" : "Khoury", MemberRole.OWNER,
                    LocalDateTime.of(2026, 11, 22, 8, 30))));
        }

        private static void await(CountDownLatch latch) {
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
