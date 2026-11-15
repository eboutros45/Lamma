package com.lamma.auth;

import java.util.List;

/**
 * Input that the user must correct (blank name, bad phone number, PIN mismatch, ...).
 * Each problem is a complete sentence that can be shown on screen as-is.
 */
public class ValidationException extends Exception {

    private final List<String> problems;

    public ValidationException(String problem) {
        this(List.of(problem));
    }

    public ValidationException(List<String> problems) {
        super(String.join("\n", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
