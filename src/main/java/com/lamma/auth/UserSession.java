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

    /**
     * True only if {@code user} is the exact sign-in that is active now. Every sign-in creates
     * a new {@link AuthenticatedUser} object, so a result loaded for an earlier sign-in (even of
     * the same person) is recognised as stale after Sign Out or an account switch.
     */
    public boolean isCurrent(AuthenticatedUser user) {
        return user != null && currentUser == user;
    }

    /**
     * After the user changes their own name or phone number: the same sign-in continues with the
     * updated details. Only applies if {@code before} is still the active sign-in (otherwise
     * nothing changes and false is returned).
     */
    public boolean replace(AuthenticatedUser before, AuthenticatedUser updated) {
        if (!isCurrent(before) || updated == null || updated.userId() != before.userId()) {
            return false;
        }
        currentUser = updated;
        return true;
    }

        public Optional<AuthenticatedUser> currentUser() {
        return Optional.ofNullable(currentUser);
    }
}
