package com.lamma.auth;

/** PIN format rule shared by registration and sign-in. */
public final class Pins {

    private Pins() {
    }

    /** Exactly six ASCII digits 0-9. */
    public static boolean isValidFormat(String pin) {
        return pin != null && pin.matches("[0-9]{6}");
    }
}
