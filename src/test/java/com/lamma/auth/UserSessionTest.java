package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserSessionTest {

    @Test
    void startsSignedOut() {
        assertFalse(new UserSession().isSignedIn());
    }

    @Test
    void signOutClearsTheAuthenticatedUser() {
        UserSession session = new UserSession();
        AuthenticatedUser user = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");

        session.signIn(user);
        assertTrue(session.isSignedIn());
        assertEquals(user, session.currentUser().orElseThrow());

        session.signOut();
        assertFalse(session.isSignedIn());
        assertTrue(session.currentUser().isEmpty());
    }

    @Test
    void isCurrentOnlyForTheActiveSignIn() {
        UserSession session = new UserSession();
        AuthenticatedUser first = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
        AuthenticatedUser other = new AuthenticatedUser(2, "Rami", "Khoury", "96170123456");
        AuthenticatedUser firstAgain = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");

        assertFalse(session.isCurrent(first));
        session.signIn(first);
        assertTrue(session.isCurrent(first));
        assertFalse(session.isCurrent(null));

        session.signOut();
        assertFalse(session.isCurrent(first), "stale after sign out");

        session.signIn(other);
        assertFalse(session.isCurrent(first), "stale after switching account");

        session.signOut();
        session.signIn(firstAgain);
        assertFalse(session.isCurrent(first), "an earlier sign-in of the same person is also stale");
        assertTrue(session.isCurrent(firstAgain));
    }

    @Test
    void replaceKeepsTheSignInWithUpdatedDetailsOnlyForTheCurrentUser() {
        UserSession session = new UserSession();
        AuthenticatedUser lina = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
        AuthenticatedUser renamed = new AuthenticatedUser(1, "Lina-Maria", "Haddad", "9613123456");
        assertFalse(session.replace(lina, renamed), "nobody signed in");
        session.signIn(lina);
        assertFalse(session.replace(lina, new AuthenticatedUser(2, "Rami", "Khoury", "96170123456")),
                "can't become someone else");
        assertTrue(session.replace(lina, renamed));
        assertTrue(session.isCurrent(renamed));
        assertFalse(session.isCurrent(lina), "views built for the old details are stale");
        assertFalse(session.replace(lina, renamed), "a stale sign-in can't replace the current one");
    }
}
