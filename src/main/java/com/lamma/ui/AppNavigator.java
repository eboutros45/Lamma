package com.lamma.ui;

import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.UserSession;
import com.lamma.db.Database;
import javafx.scene.Parent;
import javafx.scene.Scene;
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

    public AppNavigator(Stage stage) {
        this.scene = new Scene(new StartupView(this), 600, 600);
        scene.getStylesheets().add(AppNavigator.class.getResource("lamma.css").toExternalForm());
        stage.setTitle("LAMMA");
        stage.setScene(scene);
    }

    /** Called by the startup screen once the database is reachable and up to date. */
    void databaseReady(Database database) {
        PinHasher pinHasher = new PinHasher();
        this.registrationService = new RegistrationService(database, pinHasher);
        this.authenticationService = new AuthenticationService(database, pinHasher);
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

    /** Only reachable with an active session; otherwise falls back to Sign In. */
    void showSignedIn() {
        if (!session.isSignedIn()) {
            showSignIn("", "");
            return;
        }
        show(new SignedInView(this, session.currentUser().orElseThrow()));
    }

    void signOut() {
        session.signOut();
        showSignIn("", "You have signed out.");
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

    private void show(Parent screen) {
        scene.setRoot(screen);
    }
}
