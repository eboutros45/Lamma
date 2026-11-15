package com.lamma.ui;

import com.lamma.auth.AuthenticationException;
import com.lamma.auth.ValidationException;
import com.lamma.db.DatabaseException;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;

/**
 * Runs database work off the JavaFX thread (so the window never freezes) and hands the
 * result, or a user-friendly error message, back to the JavaFX thread.
 */
final class BackgroundWork {

    private BackgroundWork() {
    }

    static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<String> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> onError.accept(describe(task.getException())));
        Thread thread = new Thread(task, "lamma-background");
        thread.setDaemon(true);
        thread.start();
    }

    /** Our own exceptions already carry readable messages; anything else is unexpected. */
    static String describe(Throwable error) {
        if (error instanceof ValidationException
                || error instanceof AuthenticationException
                || error instanceof DatabaseException) {
            if (error.getCause() != null) {
                System.err.println(error.getMessage());
                error.getCause().printStackTrace();
            }
            return error.getMessage();
        }
        System.err.println("Unexpected error:");
        if (error != null) {
            error.printStackTrace();
        }
        return "Something went wrong. Please try again. (" + error + ")";
    }
}
