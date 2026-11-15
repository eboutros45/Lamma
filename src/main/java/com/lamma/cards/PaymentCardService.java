package com.lamma.cards;

import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved cards of the signed-in user (Payment Methods).
 *
 * <p>Every query is limited by {@code user_id = ?} with the id from the session, so a card id
 * coming from the screen can only ever reach the user's own cards. Only {@link CardMetadata}
 * is stored: never a full card number or CVV.
 */
public class PaymentCardService {

    static final int MAX_NICKNAME_LENGTH = 40;

    public static final String NOT_FOUND_MESSAGE =
            "This card is no longer saved. It may have been removed.";
    static final String UNAVAILABLE_MESSAGE =
            "Your saved cards could not be loaded or changed because the database is unavailable. "
            + "Check that MySQL is running and try again.";

    private static final int ER_DUP_ENTRY = 1062;

    private final Database database;

    public PaymentCardService(Database database) {
        this.database = database;
    }

    public List<SavedCard> listCards(long userId) throws DatabaseException {
        String sql = "SELECT card_id, nickname, cardholder_name, brand, last4, exp_month, exp_year"
                + " FROM payment_cards WHERE user_id = ? ORDER BY card_id";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            List<SavedCard> cards = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cards.add(read(rs));
                }
            }
            return cards;
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    public SavedCard addCard(long userId, String nickname, CardMetadata card)
            throws ValidationException, DatabaseException {
        String name = checkNickname(nickname);
        String sql = "INSERT INTO payment_cards"
                + " (user_id, nickname, cardholder_name, brand, last4, exp_month, exp_year)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, card.cardholderName());
            ps.setString(4, card.brand().name());
            ps.setString(5, card.last4());
            ps.setInt(6, card.expMonth());
            ps.setInt(7, card.expYear());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new SavedCard(keys.getLong(1), name, card);
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY && String.valueOf(e.getMessage()).contains("uq_payment_cards_same_card")) {
                throw new ValidationException("This card (" + card.masked() + ", expires " + card.expiry()
                        + ") is already saved in your Payment Methods.");
            }
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    /** Changes only the nickname; the card's details are untouched. */
    public void renameCard(long userId, long cardId, String nickname) throws ValidationException, DatabaseException {
        String name = checkNickname(nickname);
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE payment_cards SET nickname = ? WHERE card_id = ? AND user_id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, cardId);
            ps.setLong(3, userId);
            if (ps.executeUpdate() == 0) {
                throw new ValidationException(NOT_FOUND_MESSAGE);
            }
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    /** Deletes the saved card. Past top-ups keep their own copy of the brand and last four digits. */
    public void removeCard(long userId, long cardId) throws ValidationException, DatabaseException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM payment_cards WHERE card_id = ? AND user_id = ?")) {
            ps.setLong(1, cardId);
            ps.setLong(2, userId);
            if (ps.executeUpdate() == 0) {
                throw new ValidationException(NOT_FOUND_MESSAGE);
            }
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    /** Reads one of the user's cards inside an existing transaction (used by card top-ups). */
    public static SavedCard findOwnedCard(Connection conn, long userId, long cardId) throws SQLException {
        String sql = "SELECT card_id, nickname, cardholder_name, brand, last4, exp_month, exp_year"
                + " FROM payment_cards WHERE card_id = ? AND user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cardId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    public static String checkNickname(String nickname) throws ValidationException {
        String name = nickname == null ? "" : nickname.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new ValidationException("Card nickname is required, for example “Personal Visa”.");
        }
        if (name.length() > MAX_NICKNAME_LENGTH) {
            throw new ValidationException("Card nickname must be " + MAX_NICKNAME_LENGTH + " characters or fewer.");
        }
        return name;
    }

    private Connection connect() throws DatabaseException {
        try {
            return database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    private static SavedCard read(ResultSet rs) throws SQLException {
        return new SavedCard(rs.getLong("card_id"), rs.getString("nickname"), new CardMetadata(
                rs.getString("cardholder_name"), CardBrand.fromStored(rs.getString("brand")),
                rs.getString("last4"), rs.getInt("exp_month"), rs.getInt("exp_year")));
    }
}
