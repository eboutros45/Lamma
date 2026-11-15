package com.lamma.auth;

import java.util.Optional;

/**
 * Who is signed in right now. Kept in memory only, so it ends when the application closes;
 * nothing about the session is written to disk or to the database.
 */
public final class UserSession {

    private AuthenticatedUser currentUser;

    public void signIn(AuthenticatedUser user) {
        this.currentUser = user;
    }

    public void signOut() {
        this.currentUser = null;
    }

    public boolean isSignedIn() {
        return currentUser != null;
    }

    public Optional<AuthenticatedUser> currentUser() {
        return Optional.ofNullable(currentUser);
    }
}
