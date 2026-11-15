package com.lamma.db;

/**
 * A database problem described in plain language, safe to show to the user.
 * The original technical exception (if any) is kept as the cause for logging.
 */
public class DatabaseException extends Exception {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
