package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.cards.PaymentCardService;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.InvitationCodes;
import com.lamma.wallet.SharedWalletMembershipService;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.TransferService;
import com.lamma.wallet.WalletService;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Both sides of the membership workflows through the real screens and real MySQL, with three
 * users. Needs a display AND the LAMMA_TEST_DB_* settings; skipped otherwise.
 */
class SharedWalletMembershipFlowTest {

    private Database database;
    private SharedWalletService shared;
    private AuthenticatedUser lina;
    private AuthenticatedUser rami;
    private AuthenticatedUser nour;
    private long trip;
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
        database = TestDatabase.freshSchema(); // skips the test without LAMMA_TEST_DB_*
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = user(registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId(), "Lina", "Haddad");
        rami = user(registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId(), "Rami", "Khoury");
        nour = user(registration.registerPersonal("Nour", "Saad", "+1 514 555 0100", "333333", "333333").userId(), "Nour", "Saad");
        shared = new SharedWalletService(database);
        trip = shared.create(lina.userId(), SharedWalletService.validate("Beirut Trip", "Summer", null, "900"));
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), null, null, new WalletService(database),
                    new TopUpService(database), new PaymentCardService(database), new TransferService(database));
            n.useSharedWalletService(shared);
            n.useMembershipService(new SharedWalletMembershipService(database));
            n.useFundsService(new com.lamma.wallet.SharedWalletFundsService(database));
            return n;
        });
    }

    @Test
    void inviteAcceptPromoteAndLeaveFromBothSides() throws Exception {
        // Owner invites Rami by phone.
        signIn(lina);
        openWallet();
        click("#inviteMemberButton");
        assertInstanceOf(InviteMemberView.class, onFx(navigator::currentScreen));
        type("#invitePhoneField", "+961 70 123 456");
        click("#sendInvitationButton");
        waitUntil(() -> text("#inviteMessage").startsWith("Invitation sent to Rami Khoury"));
        type("#invitePhoneField", "70 123 456");
        click("#sendInvitationButton");
        waitUntil(() -> text("#inviteMessage").contains("already has a pending invitation"));
        click("#backToWalletButton");
        waitUntil(() -> lookup("#pendingInvitationsList") != null);
        assertTrue(textUnder("#pendingInvitationsList").contains("Rami Khoury"));

        // Rami: not a member yet; accepts from Wallets -> Invitations.
        switchTo(rami);
        assertNotAMember(rami);
        nav("#navWallets");
        waitUntil(() -> lookup("#invitationsSection") != null && textUnder("#invitationsSection").contains("Invitations (1)"));
        assertTrue(textUnder("#invitationsSection").contains("Lina Haddad invited you to “Beirut Trip”"));
        clickFirst("acceptInvitation-");
        waitUntil(() -> "You: Contributor".equals(text("#myRoleBadge")));
        assertNull(lookup("#inviteMemberButton"), "no Owner tools for a Contributor");
        assertNull(lookup("#shareQrButton"));
        assertNull(lookup("#changeRole-" + lina.userId()));
        assertNotNull(lookup("#leaveWalletButton"));
        assertEquals("Owner", text("#memberRole-" + lina.userId()));

        // Owner promotes Rami (with confirmation).
        switchTo(lina);
        openWallet();
        click("#changeRole-" + rami.userId());
        assertTrue(screenText().contains("Make Rami a Spender?"));
        click("#cancelMemberAction");
        assertEquals("Contributor", text("#memberRole-" + rami.userId()), "cancelled: nothing changed");
        click("#changeRole-" + rami.userId());
        click("#confirmMemberAction");
        waitUntil(() -> "Spender".equals(text("#memberRole-" + rami.userId())));
        assertEquals("Rami is now a Spender.", text("#memberActionMessage"));

        // Rami sees the new role, then leaves (with confirmation) and loses access.
        switchTo(rami);
        openWallet();
        assertEquals("You: Spender", text("#myRoleBadge"));
        click("#leaveWalletButton");
        click("#confirmMemberAction");
        waitUntil(() -> {
            try {
                return onFx(navigator::currentScreen) instanceof WalletsView;
            } catch (Exception e) {
                return false;
            }
        });
        assertNotAMember(rami);
    }

    @Test
    void qrInvitationJoinRequestApprovalAndRemoval() throws Exception {
        signIn(lina);
        openWallet();
        click("#shareQrButton");
        waitUntil(() -> lookup("#enableQrButton") != null);
        click("#enableQrButton");
        waitUntil(() -> lookup("#qrImage") != null);
        TextInputControl codeText = (TextInputControl) lookup("#inviteCodeText");
        String payload = onFx(codeText::getText);
        assertTrue(payload.startsWith(InvitationCodes.PREFIX), payload);
        assertEquals(payload, decode(((ImageView) lookup("#qrImage")).getImage()), "the QR contains the invitation");

        // Nour opens the invitation and asks to join: no access yet.
        switchTo(nour);
        nav("#navWallets");
        waitUntil(() -> lookup("#joinWithCodeButton") != null);
        click("#joinWithCodeButton");
        type("#inviteCodeField", payload);
        click("#checkInviteCodeButton");
        waitUntil(() -> "Beirut Trip".equals(text("#invitePreviewWallet")));
        assertTrue(textUnder(null).contains("Owner: Lina Haddad"));
        click("#requestToJoinButton");
        waitUntil(() -> text("#joinMessage").startsWith("Request sent."));
        assertNotAMember(nour);
        type("#inviteCodeField", payload);
        click("#checkInviteCodeButton");
        waitUntil(() -> text("#joinMessage").contains("already asked to join"));

        // Owner approves; Nour becomes a Contributor.
        switchTo(lina);
        openWallet();
        waitUntil(() -> lookup("#joinRequestsList") != null);
        assertTrue(textUnder("#joinRequestsList").contains("Nour Saad"));
        clickFirst("approveRequest-");
        waitUntil(() -> "Contributor".equals(text("#memberRole-" + nour.userId())));
        assertNull(lookup("#joinRequestsList"), "request handled");
        assertEquals("Contributor", shared.details(nour.userId(), trip).wallet().myRole().displayName());

        // Owner removes Nour (with confirmation): access ends immediately.
        click("#removeMember-" + nour.userId());
        assertTrue(screenText().contains("Remove Nour Saad from this wallet?"));
        click("#confirmMemberAction");
        waitUntil(() -> lookup("#memberRole-" + nour.userId()) == null);
        assertNotAMember(nour);
    }

    @Test
    void declineAndTurnedOffCodeAreHandled() throws Exception {
        SharedWalletMembershipService members = new SharedWalletMembershipService(database);
        members.inviteByPhone(lina.userId(), trip, "70 123 456");
        String code = members.newInviteCode(lina.userId(), trip);
        members.turnOffInviteCode(lina.userId(), trip);

        signIn(rami);
        nav("#navWallets");
        waitUntil(() -> findByIdPrefix("declineInvitation-") != null);
        clickFirst("declineInvitation-");
        waitUntil(() -> textUnder("#invitationsSection").contains("declined"));
        assertNotAMember(rami);

        click("#joinWithCodeButton");
        type("#inviteCodeField", InvitationCodes.payload(code));
        click("#checkInviteCodeButton");
        waitUntil(() -> text("#joinMessage").contains("isn't valid"));
        assertNull(lookup("#requestToJoinButton"));
    }

    // ---------- helpers ----------

    private static AuthenticatedUser user(long id, String first, String last) {
        return new AuthenticatedUser(id, first, last, "");
    }

    private void assertNotAMember(AuthenticatedUser u) {
        ValidationException e = assertThrows(ValidationException.class, () -> shared.details(u.userId(), trip));
        assertEquals(SharedWalletService.NOT_AVAILABLE_MESSAGE, e.getMessage());
    }

    private static String decode(Image image) throws Exception {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] argb = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, argb))))
                .getText();
    }

    private void signIn(AuthenticatedUser u) throws Exception {
        onFx(() -> {
            navigator.signedIn(new AuthenticatedUser(u.userId(), u.firstName(), u.lastName(), u.phoneNumber()));
            return null;
        });
    }

    private void switchTo(AuthenticatedUser u) throws Exception {
        onFx(() -> {
            navigator.signOut();
            return null;
        });
        signIn(u);
    }

    private void openWallet() throws Exception {
        onFx(() -> {
            navigator.showSharedWalletDetail(trip);
            return null;
        });
        waitUntil(() -> lookup("#sharedWalletName") != null);
    }

    private void nav(String id) throws Exception {
        Node node = onFx(() -> navigator.windowRoot().lookup(id));
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private void buildSkins() {
        Parent root = navigator.windowRoot();
        for (int i = 0; i < 2; i++) {
            root.applyCss();
            root.layout();
        }
    }

    private Node lookup(String selector) {
        try {
            return onFx(() -> {
                buildSkins();
                return navigator.currentScreen().lookup(selector);
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

    /** Clicks the first button whose id starts with the prefix (ids end with a database id). */
    private void clickFirst(String idPrefix) throws Exception {
        Node node = findByIdPrefix(idPrefix);
        assertNotNull(node, "no node with id starting " + idPrefix);
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private Node findByIdPrefix(String prefix) {
        try {
            return onFx(() -> {
                buildSkins();
                return find(navigator.currentScreen(), prefix);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Node find(Node node, String prefix) {
        if (node.getId() != null && node.getId().startsWith(prefix)) {
            return node;
        }
        if (node instanceof Labeled l && l.getGraphic() != null) {
            Node found = find(l.getGraphic(), prefix);
            if (found != null) {
                return found;
            }
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node found = find(child, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void type(String id, String value) throws Exception {
        Node node = lookup(id);
        onFx(() -> {
            ((TextInputControl) node).setText(value);
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

    private String screenText() {
        return textUnder(null);
    }

    private String textUnder(String id) {
        try {
            return onFx(() -> {
                buildSkins();
                Node node = id == null ? navigator.currentScreen() : navigator.currentScreen().lookup(id);
                StringBuilder sb = new StringBuilder("|");
                collect(node, sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node == null) {
            return;
        }
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
