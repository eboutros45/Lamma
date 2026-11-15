package com.lamma.contacts;

import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The signed-in user's own contact book. Every query is limited to the session's user id, so
 * nobody can see or delete another user's contacts. A contact is only a name and a phone number:
 * it is not a LAMMA account and grants nothing. Recipient screens use it to fill in the phone
 * field, which is then looked up and validated as usual.
 */
public class ContactService {

    public static final int MAX_NAME_LENGTH = 100;
    static final String FAILED_MESSAGE =
            "Your contacts could not be saved or loaded because the database is unavailable. "
            + "Check that MySQL is running and try again.";
    static final String NOT_FOUND_MESSAGE = "This contact no longer exists.";

    /** One saved contact; {@code phoneNumber} is normalized (digits, country code included). */
    public record Contact(long contactId, String name, String phoneNumber) {
    }

    /**
     * What an import did.
     *
     * @param added      contacts saved
     * @param duplicates entries skipped because the number is already saved, or repeated in the file
     * @param invalid    entries (or parts of the file) that couldn't be read, with the reason
     */
    public record ImportReport(int added, List<String> duplicates, List<String> invalid) {
    }

    private final Database database;
    private final Clock clock;

    public ContactService(Database database) {
        this(database, Clock.systemUTC());
    }

    public ContactService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /** All of the user's contacts, by name. */
    public List<Contact> list(long userId) throws DatabaseException {
        return query("SELECT contact_id, display_name, phone_number FROM contacts WHERE owner_user_id = ?"
                + " ORDER BY display_name, contact_id", userId);
    }

    /**
     * Contacts whose name contains the text (any case), or whose number contains its digits.
     * An empty search returns nothing.
     */
    public List<Contact> search(long userId, String text, int limit) throws DatabaseException {
        List<Contact> matches = new ArrayList<>();
        for (Contact c : list(userId)) {
            if (matches.size() >= limit) {
                break;
            }
            if (matches(c, text)) {
                matches.add(c);
            }
        }
        return matches;
    }

    /** The matching rule used by search and by the suggestions under phone fields. */
    public static boolean matches(Contact c, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String needle = text.strip().toLowerCase(Locale.ROOT);
        if (c.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String digits = needle.replaceAll("[^0-9]", "");
        if (digits.length() < 2) {
            return false;
        }
        // Compare with the stored number and with the local form (e.g. 03123456 for 9613123456).
        String formatted = PhoneNumbers.format(c.phoneNumber()).replaceAll("[^0-9]", "");
        String local = c.phoneNumber().startsWith(PhoneNumbers.LEBANON_COUNTRY_CODE)
                ? "0" + c.phoneNumber().substring(PhoneNumbers.LEBANON_COUNTRY_CODE.length()) : c.phoneNumber();
        String digitsNoZero = digits.startsWith("0") ? digits.substring(1) : digits;
        return c.phoneNumber().contains(digits) || formatted.contains(digits) || local.contains(digits)
                || c.phoneNumber().contains(digitsNoZero);
    }

    /** Deletes one of the user's own contacts. */
    public void delete(long userId, long contactId) throws ValidationException, DatabaseException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM contacts WHERE contact_id = ? AND owner_user_id = ?")) {
            ps.setLong(1, contactId);
            ps.setLong(2, userId);
            if (ps.executeUpdate() == 0) {
                throw new ValidationException(NOT_FOUND_MESSAGE); // missing, or someone else's
            }
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    /**
     * Imports a CSV or vCard file's content. Valid new entries are saved together in one
     * transaction; numbers already saved, or repeated within the file, are skipped (the existing
     * name is kept); invalid entries are reported and not saved.
     */
    public ImportReport importFile(long userId, String fileName, String content)
            throws ValidationException, DatabaseException {
        if (content == null || content.isBlank()) {
            throw new ValidationException("The file is empty.");
        }
        if (content.length() > ContactFiles.MAX_BYTES) {
            throw new ValidationException("The file is too large. Contact files can be up to 1 MB.");
        }
        ContactFiles.Parsed parsed = ContactFiles.parse(fileName, content);
        List<String> invalid = new ArrayList<>();
        for (ContactFiles.Problem p : parsed.problems()) {
            invalid.add(capitalize(p.where()) + ": " + p.message());
        }
        if (parsed.entries().isEmpty() && invalid.isEmpty()) {
            throw new ValidationException("No contacts were found in this file. Use a CSV file with name and "
                    + "phone columns, or a vCard (.vcf) file.");
        }

        Connection conn = connect();
        try (conn) {
            conn.setAutoCommit(false);
            try {
                Set<String> known = new HashSet<>();
                for (Contact c : query(conn, "SELECT contact_id, display_name, phone_number FROM contacts"
                        + " WHERE owner_user_id = ? FOR UPDATE", userId)) {
                    known.add(c.phoneNumber());
                }
                Set<String> inFile = new HashSet<>();
                List<String> duplicates = new ArrayList<>();
                int added = 0;
                LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
                for (ContactFiles.Entry e : parsed.entries()) {
                    String label = capitalize(e.where()) + (e.name().isEmpty() ? "" : " (" + e.name() + ")");
                    if (e.name().isEmpty()) {
                        invalid.add(label + ": no name.");
                        continue;
                    }
                    if (e.name().length() > MAX_NAME_LENGTH) {
                        invalid.add(label + ": the name is longer than " + MAX_NAME_LENGTH + " characters.");
                        continue;
                    }
                    String phone;
                    try {
                        phone = PhoneNumbers.normalize(e.phone());
                    } catch (ValidationException bad) {
                        invalid.add(label + ": " + (e.phone().isEmpty() ? "no phone number." : bad.getMessage()));
                        continue;
                    }
                    if (known.contains(phone)) {
                        duplicates.add(e.name() + " (" + PhoneNumbers.format(phone) + ") is already in your contacts.");
                        continue;
                    }
                    if (!inFile.add(phone)) {
                        duplicates.add(e.name() + " (" + PhoneNumbers.format(phone) + ") appears more than once in the file.");
                        continue;
                    }
                    insert(conn, userId, e.name(), phone, now);
                    added++;
                }
                conn.commit();
                return new ImportReport(added, duplicates, invalid);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    // ---------- helpers ----------

    private static void insert(Connection conn, long userId, String name, String phone, LocalDateTime now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO contacts (owner_user_id, display_name,"
                + " phone_number, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setObject(4, now);
            ps.executeUpdate();
        }
    }

    private List<Contact> query(String sql, long userId) throws DatabaseException {
        try (Connection conn = connect()) {
            return query(conn, sql, userId);
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    private static List<Contact> query(Connection conn, String sql, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            List<Contact> contacts = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contacts.add(new Contact(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
            return contacts;
        }
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private Connection connect() throws DatabaseException {
        try {
            return database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }
}
