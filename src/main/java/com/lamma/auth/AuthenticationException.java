package com.lamma.auth;

/**
 * Sign-in failed. The message is deliberately the same whether the phone number is
 * unknown, the PIN is wrong, or the account is temporarily locked, so the screen never
 * reveals which phone numbers have accounts.
 */
public class AuthenticationException extends Exception {

    public AuthenticationException(String message) {
        super(message);
    }
}
