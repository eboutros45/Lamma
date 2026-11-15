package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import org.junit.jupiter.api.Test;

/**
 * Form validation runs before any database access, so these tests use a database
 * that does not exist: reaching it would fail the test with a DatabaseException.
 */
class RegistrationValidationTest {

    private final RegistrationService service = new RegistrationService(
            new Database(new DatabaseConfig("jdbc:mysql://127.0.0.1:1/none?connectTimeout=500", "x", "x")),
            new PinHasher(1_000));

    private ValidationException invalid(String first, String last, String phone, String pin, String confirm) {
        return assertThrows(ValidationException.class,
                () -> service.registerPersonal(first, last, phone, pin, confirm));
    }

    @Test
    void blankFieldsAreAllReported() {
        ValidationException e = invalid(" ", "", "", "", "");
        assertTrue(e.problems().contains("First name is required."));
        assertTrue(e.problems().contains("Last name is required."));
        assertTrue(e.problems().contains("Phone number is required."));
        assertTrue(e.problems().contains("PIN is required."));
    }

    @Test
    void pinMustBeSixDigits() {
        assertTrue(invalid("Lina", "Haddad", "03 123 456", "12345", "12345")
                .problems().contains("PIN must be exactly 6 digits."));
        assertTrue(invalid("Lina", "Haddad", "03 123 456", "12345a", "12345a")
                .problems().contains("PIN must be exactly 6 digits."));
    }

    @Test
    void pinConfirmationMustMatch() {
        assertTrue(invalid("Lina", "Haddad", "03 123 456", "123456", "123465")
                .problems().contains("PIN and Confirm PIN do not match."));
    }

    @Test
    void invalidPhoneIsRejected() {
        ValidationException e = invalid("Lina", "Haddad", "3123456", "123456", "123456");
        assertTrue(e.getMessage().startsWith("Phone number is incomplete."), e.getMessage());
    }

    @Test
    void overlongNameIsRejected() {
        assertTrue(invalid("x".repeat(51), "Haddad", "03 123 456", "123456", "123456")
                .problems().contains("First name must be 50 characters or fewer."));
    }
}
