package com.lamma.auth;

/** The signed-in person. Holds no PIN or PIN hash. */
public record AuthenticatedUser(long userId, String firstName, String lastName, String phoneNumber) {
}
