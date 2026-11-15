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
}
