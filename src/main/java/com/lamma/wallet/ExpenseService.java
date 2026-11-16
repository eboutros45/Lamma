package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared expenses (P04C): an expense split between members, collected by payment requests.
 *
 * <p><b>Nothing is charged automatically.</b> Creating an expense only records who owes what and
 * sends each participant a PENDING payment request. Each participant then pays or declines their
 * own request. Paying is one ordinary LAMMA transfer, made with the same steps as P03
 * ({@link LedgerTransfers}):
 * <ul>
 *   <li>SHARED_WALLET collection: participant's Personal Wallet → the shared wallet;</li>
 *   <li>REIMBURSEMENT: participant's Personal Wallet → the Personal Wallet of the member who paid
 *       the bill outside LAMMA. Their own share is "covered by payer"; the outside payment is never
 *       recorded as a LAMMA transaction.</li>
 * </ul>
 *
 * <p><b>Locking order</b> (the same for every operation here, and compatible with P02/P03): first
 * {@code wallets} rows in ascending id order (always including the shared wallet's row, which
 * membership changes also lock), then the {@code expenses} row, then its {@code payment_requests}.
 * All checks (membership, request status, balances) are repeated with those locks held.
 *
 * <p><b>Never collected twice:</b> a request goes PENDING → PAID in the same transaction as its
 * transfer; the transfer's {@code request_id} is derived from the payment request
 * ({@code expense-request-<id>}, UNIQUE); and the database allows only one PENDING or PAID
 * request per person per expense. A PAID request can't be changed, and a DECLINED or CANCELLED
 * one can't be paid.
 */
public class ExpenseService {

    public static final int MAX_TITLE = 80;
    public static final int MAX_NOTES = 500;

    static final String NOT_AVAILABLE_MESSAGE = "This expense isn't available. It may not exist, or you may not "
            + "be a member of its shared wallet.";
    static final String REQUEST_NOT_AVAILABLE_MESSAGE = "This payment request isn't available.";
    static final String REQUEST_CLOSED_MESSAGE =
            "This request was declined or cancelled, so it can't be paid. Ask the expense's creator to send a new one.";
    static final String ALREADY_ANSWERED_MESSAGE = "This request has already been answered.";
    static final String NOT_A_MEMBER_NOW_MESSAGE =
            "This request can't be paid because you're no longer a member of the shared wallet.";
    static final String PAYEE_GONE_MESSAGE = "This request can't be paid because the member it reimburses is no "
            + "longer in the shared wallet. Ask the expense's creator to revise it.";
    static final String EXPENSE_CANCELLED_MESSAGE = "This expense was cancelled.";
    static final String MANAGE_ONLY_MESSAGE = "Only the person who created this expense, or the wallet's Owner, can change it.";
    static final String NOTHING_TO_REVISE_MESSAGE = "Every share of this expense is already paid or covered.";
    static final String FAILED_MESSAGE = "The expense could not be saved or updated because of a database problem, "
            + "so nothing was changed and no money was moved. Check that MySQL is running and try again.";
    static final String LOAD_FAILED_MESSAGE = "Expenses could not be loaded because the database is unavailable. "
            + "Check that MySQL is running and try again.";

    public enum SplitMethod { EQUAL, CUSTOM }

    public enum Collection { SHARED_WALLET, REIMBURSEMENT }

    /** What a participant's share looks like now. */
    public enum ShareState { PAID, PENDING, DECLINED, CANCELLED, COVERED, NOTHING_OWED }

    /**
     * A new expense as entered.
     *
     * @param participants  the members sharing it (user ids)
     * @param customShares  CUSTOM only: each participant's amount (must add up to the total)
     * @param paidByUserId  REIMBURSEMENT only: the member who paid the bill
     */
    public record NewExpense(String title, BigDecimal total, String notes, List<Long> participants,
                             SplitMethod split, Map<Long, BigDecimal> customShares, Collection collection,
                             Long paidByUserId) {
    }

    /** One participant's line. */
    public record Share(long userId, String name, BigDecimal amount, ShareState state, Long openRequestId,
                        boolean stillMember) {
    }

    /** One payment request, for the history. */
    public record RequestRecord(long requestId, long payerId, String payerName, BigDecimal amount, String status,
                                LocalDateTime createdAt, LocalDateTime respondedAt, String transferId,
                                Long replacesRequestId) {
    }

    public record ExpenseSummary(long expenseId, long walletId, String title, BigDecimal total, Collection collection,
                                 SplitMethod split, String status, BigDecimal collected, BigDecimal covered,
                                 BigDecimal outstanding, String createdByName, String paidByName,
                                 LocalDateTime createdAt) {

        /** OPEN with nothing left to collect. */
        public boolean isSettled() {
            return "OPEN".equals(status) && outstanding.signum() == 0;
        }

        public boolean isCancelled() {
            return "CANCELLED".equals(status);
        }
    }

    public record ExpenseDetails(ExpenseSummary summary, String notes, long createdByUserId, Long paidByUserId,
                                 String walletName, List<Share> shares, List<RequestRecord> requests,
                                 boolean canManage) {
    }

    /** A pending request for the signed-in user. */
    public record MyRequest(long requestId, long expenseId, long walletId, String walletName, String title,
                            BigDecimal amount, Collection collection, String payToName, String requesterName,
                            LocalDateTime createdAt) {
    }

    /** Result of paying a request. {@code alreadyPaid}: it had already been paid; nothing moved now. */
    public record Payment(long requestId, BigDecimal amount, BigDecimal myBalanceAfter, String paidToName,
                          boolean alreadyPaid) {
    }

    private final Database database;
    private final Clock clock;

    public ExpenseService(Database database) {
        this(database, Clock.systemUTC());
    }

    public ExpenseService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    // ---------- splitting ----------

    /**
     * Splits the total equally in cents. Participants are ordered by user id; the remainder
     * cents go one each to the first ones, so the shares always add up exactly to the total
     * and the same input always gives the same result.
     */
    public static Map<Long, BigDecimal> equalSplit(BigDecimal total, List<Long> participants)
            throws ValidationException {
        List<Long> ordered = participants.stream().distinct().sorted().toList();
        if (ordered.isEmpty()) {
            throw new ValidationException("Choose at least one participant.");
        }
        long cents = total.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        if (cents < ordered.size()) {
            throw new ValidationException("The total is too small to split between " + ordered.size() + " people.");
        }
        long base = cents / ordered.size();
        long remainder = cents % ordered.size();
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            long c = base + (i < remainder ? 1 : 0);
            shares.put(ordered.get(i), BigDecimal.valueOf(c).movePointLeft(2).setScale(2));
        }
        return shares;
    }

    /** Validates a new expense and returns each participant's share (ordered by user id). */
    static Map<Long, BigDecimal> shares(NewExpense e) throws ValidationException {
        List<String> problems = new ArrayList<>();
        String title = e.title() == null ? "" : e.title().strip();
        if (title.isEmpty()) {
            problems.add("Expense title is required.");
        } else if (title.length() > MAX_TITLE) {
            problems.add("Expense title must be " + MAX_TITLE + " characters or fewer.");
        }
        if (e.notes() != null && e.notes().strip().length() > MAX_NOTES) {
            problems.add("Notes must be " + MAX_NOTES + " characters or fewer.");
        }
        try {
            Amounts.checkTransfer(e.total());
        } catch (ValidationException bad) {
            problems.add("Total: " + bad.getMessage());
        }
        if (e.participants() == null || e.participants().isEmpty()) {
            problems.add("Choose at least one participant.");
        } else if (e.participants().stream().distinct().count() != e.participants().size()) {
            problems.add("Each participant can be chosen only once.");
        }
        if (e.split() == null) {
            problems.add("Choose how to split the expense.");
        }
        if (e.collection() == null) {
            problems.add("Choose how the money is collected.");
        } else if (e.collection() == Collection.REIMBURSEMENT && e.paidByUserId() == null) {
            problems.add("Choose who paid the bill.");
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        if (e.split() == SplitMethod.EQUAL) {
            return equalSplit(e.total(), e.participants());
        }
        Map<Long, BigDecimal> custom = new TreeMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (long id : e.participants().stream().sorted().toList()) {
            BigDecimal amount = e.customShares() == null ? null : e.customShares().get(id);
            if (amount == null) {
                throw new ValidationException("Enter an amount for every participant.");
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                throw new ValidationException("Amounts can have at most 2 decimal places (cents).");
            }
            if (amount.compareTo(Amounts.MIN_AMOUNT) < 0) {
                throw new ValidationException("Each participant's share must be at least $0.01.");
            }
            custom.put(id, amount.setScale(2, RoundingMode.UNNECESSARY));
            sum = sum.add(amount);
        }
        if (e.customShares().size() != custom.size()) {
            throw new ValidationException("Amounts were given for people who aren't participants.");
        }
        if (sum.compareTo(e.total()) != 0) {
            throw new ValidationException("The shares add up to " + Money.formatUsd(sum) + ", but the total is "
                    + Money.formatUsd(e.total()) + ". They must match exactly.");
        }
        return custom;
    }

    // ---------- creating ----------

    /** Any current member creates an expense; participants and payer must be current members. */
    public long create(long userId, long walletId, NewExpense input) throws ValidationException, DatabaseException {
        Map<Long, BigDecimal> shares = shares(input);
        BigDecimal total = input.total().setScale(2, RoundingMode.UNNECESSARY);
        return inTransaction(FAILED_MESSAGE, conn -> {
            LedgerTransfers.Locked shared = LedgerTransfers.lockAscending(conn, walletId).get(walletId);
            if (shared != null && "COMPLETED".equals(shared.status())
                    && LedgerTransfers.lockedRoleAnyStatus(conn, userId, walletId) != null) {
                throw new ValidationException(GiftDeliveryService.COMPLETED_MESSAGE);
            }
            if (shared == null || !shared.isActive("SHARED") || LedgerTransfers.lockedRole(conn, userId, walletId) == null) {
                throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
            }
            for (long participant : shares.keySet()) {
                if (LedgerTransfers.lockedRole(conn, participant, walletId) == null) {
                    throw new ValidationException("Only current members of the wallet can be participants.");
                }
            }
            boolean reimbursement = input.collection() == Collection.REIMBURSEMENT;
            Long payer = reimbursement ? input.paidByUserId() : null;
            Long payerWallet = null;
            if (reimbursement) {
                if (LedgerTransfers.lockedRole(conn, payer, walletId) == null) {
                    throw new ValidationException("The person who paid must be a current member of the wallet.");
                }
                payerWallet = LedgerTransfers.personalWalletId(conn, payer);
            }
            LocalDateTime now = now();
            long expenseId = insertExpense(conn, walletId, userId, input, total, payer, now);
            for (Map.Entry<Long, BigDecimal> s : shares.entrySet()) {
                boolean covered = reimbursement && s.getKey().equals(payer);
                insertShare(conn, expenseId, s.getKey(), s.getValue(), covered);
                if (!covered) {
                    insertRequest(conn, expenseId, userId, s.getKey(), reimbursement ? payerWallet : walletId,
                            s.getValue(), null, now);
                }
            }
            return expenseId;
        });
    }

    // ---------- reading ----------

    /** The wallet's expenses, newest first (current members only). */
    public List<ExpenseSummary> list(long userId, long walletId) throws ValidationException, DatabaseException {
        try (Connection conn = connect(LOAD_FAILED_MESSAGE)) {
            requireMember(conn, userId, walletId, SharedWalletService.NOT_AVAILABLE_MESSAGE);
            List<ExpenseSummary> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SUMMARY_SELECT
                    + " WHERE e.shared_wallet_id = ? ORDER BY e.expense_id DESC")) {
                ps.setLong(1, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(readSummary(rs));
                    }
                }
            }
            return list;
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    /** One expense with its shares and request history (current members of its wallet only). */
    public ExpenseDetails details(long userId, long expenseId) throws ValidationException, DatabaseException {
        try (Connection conn = connect(LOAD_FAILED_MESSAGE)) {
            ExpenseSummary summary;
            String notes;
            long createdBy;
            Long paidBy;
            String walletName;
            try (PreparedStatement ps = conn.prepareStatement(SUMMARY_SELECT + " WHERE e.expense_id = ?")) {
                ps.setLong(1, expenseId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ValidationException(NOT_AVAILABLE_MESSAGE);
                    }
                    summary = readSummary(rs);
                    notes = rs.getString("notes");
                    createdBy = rs.getLong("created_by_user_id");
                    long p = rs.getLong("paid_by_user_id");
                    paidBy = rs.wasNull() ? null : p;
                    walletName = rs.getString("wallet_name");
                }
            }
            MemberRole role = roleOf(conn, userId, summary.walletId());
            if (role == null) {
                throw new ValidationException(NOT_AVAILABLE_MESSAGE);
            }
            List<RequestRecord> requests = requests(conn, expenseId, false);
            List<Share> shares = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT s.user_id, CONCAT(u.first_name, ' ', u.last_name),"
                    + " s.amount, s.covered_by_payer, (SELECT 1 FROM shared_wallet_members m WHERE m.user_id = s.user_id"
                    + " AND m.shared_wallet_id = ?) FROM expense_shares s JOIN users u ON u.user_id = s.user_id"
                    + " WHERE s.expense_id = ? ORDER BY u.first_name, u.last_name, s.user_id")) {
                ps.setLong(1, summary.walletId());
                ps.setLong(2, expenseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        BigDecimal amount = rs.getBigDecimal(3);
                        boolean covered = rs.getBoolean(4);
                        boolean member = rs.getObject(5) != null;
                        RequestRecord latest = latestRequest(requests, id);
                        String latestStatus = latest == null ? "" : latest.status();
                        ShareState state = covered ? ShareState.COVERED
                                : "PAID".equals(latestStatus) ? ShareState.PAID
                                : amount.signum() == 0 ? ShareState.NOTHING_OWED
                                : "PENDING".equals(latestStatus) ? ShareState.PENDING
                                : "DECLINED".equals(latestStatus) ? ShareState.DECLINED
                                : ShareState.CANCELLED; // the expense was cancelled before it was paid
                        Long open = latest != null && "PENDING".equals(latest.status()) ? latest.requestId() : null;
                        shares.add(new Share(id, rs.getString(2), amount, state, open, member));
                    }
                }
            }
            boolean canManage = createdBy == userId || role == MemberRole.OWNER;
            return new ExpenseDetails(summary, notes, createdBy, paidBy, walletName, shares, requests, canManage);
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    /** The user's PENDING requests in wallets they are still a member of, oldest first. */
    public List<MyRequest> myPendingRequests(long userId) throws DatabaseException {
        String sql = "SELECT r.request_id, e.expense_id, e.shared_wallet_id, w.name, e.title, r.amount,"
                + " e.collection_method, CONCAT(pu.first_name, ' ', pu.last_name), CONCAT(ru.first_name, ' ', ru.last_name),"
                + " r.created_at"
                + " FROM payment_requests r JOIN expenses e ON e.expense_id = r.expense_id"
                + " JOIN wallets w ON w.wallet_id = e.shared_wallet_id"
                + " JOIN shared_wallet_members m ON m.shared_wallet_id = e.shared_wallet_id AND m.user_id = r.payer_id"
                + " JOIN users ru ON ru.user_id = r.requester_id"
                + " LEFT JOIN users pu ON pu.user_id = e.paid_by_user_id"
                + " WHERE r.payer_id = ? AND r.status = 'PENDING' AND e.status = 'OPEN'"
                + " ORDER BY r.request_id";
        try (Connection conn = connect(LOAD_FAILED_MESSAGE); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            List<MyRequest> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Collection c = Collection.valueOf(rs.getString(7));
                    list.add(new MyRequest(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                            rs.getBigDecimal(6), c, c == Collection.REIMBURSEMENT ? rs.getString(8) : rs.getString(4),
                            rs.getString(9), rs.getObject(10, LocalDateTime.class)));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    // ---------- answering a request ----------

    /**
     * The payer pays their own PENDING request: one transfer from their Personal Wallet to the
     * request's destination, and the request becomes PAID, in one transaction. Paying a request
     * that is already PAID returns {@code alreadyPaid} and moves nothing.
     */
    public Payment pay(long userId, long requestId) throws ValidationException, DatabaseException {
        return inTransaction(FAILED_MESSAGE, conn -> {
            // Where the money would go (read first, re-checked below with the locks held).
            RequestRow seen = requestRow(conn, requestId, false);
            if (seen == null || seen.payerId != userId) {
                throw new ValidationException(REQUEST_NOT_AVAILABLE_MESSAGE);
            }
            Long myWallet = LedgerTransfers.personalWalletId(conn, userId);
            if (myWallet == null) {
                throw new ValidationException(REQUEST_NOT_AVAILABLE_MESSAGE);
            }
            // 1. Wallets, lowest id first: mine, the destination, and the shared wallet.
            Map<Long, LedgerTransfers.Locked> locked = LedgerTransfers.lockAscending(conn, myWallet,
                    seen.destinationWalletId, seen.walletId);
            // 2. The expense, then the request, with their current values.
            ExpenseRow expense = expenseRow(conn, seen.expenseId, true);
            RequestRow request = requestRow(conn, requestId, true);
            if (request.status.equals("PAID")) {
                return new Payment(requestId, request.amount, null, payeeName(conn, expense, request), true);
            }
            if (!request.status.equals("PENDING")) {
                throw new ValidationException(REQUEST_CLOSED_MESSAGE);
            }
            if (!"OPEN".equals(expense.status)) {
                throw new ValidationException(EXPENSE_CANCELLED_MESSAGE);
            }
            // 3. Still allowed: the payer (and, for a reimbursement, the payee) are current members.
            if (LedgerTransfers.lockedRole(conn, userId, expense.walletId) == null) {
                throw new ValidationException(NOT_A_MEMBER_NOW_MESSAGE);
            }
            boolean reimbursement = Collection.REIMBURSEMENT.name().equals(expense.collection);
            if (reimbursement && LedgerTransfers.lockedRole(conn, expense.paidBy, expense.walletId) == null) {
                throw new ValidationException(PAYEE_GONE_MESSAGE);
            }
            LedgerTransfers.Locked from = locked.get(myWallet);
            LedgerTransfers.Locked to = locked.get(request.destinationWalletId);
            if (from == null || !from.isActive("PERSONAL") || to == null
                    || !to.isActive(reimbursement ? "PERSONAL" : "SHARED")) {
                throw new ValidationException(REQUEST_NOT_AVAILABLE_MESSAGE);
            }
            // 4. Balance.
            if (from.balance().compareTo(request.amount) < 0) {
                throw new ValidationException("Insufficient funds. Your available balance is "
                        + Money.formatUsd(from.balance()) + ". The request stays open; add money and try again.");
            }
            // 5. Move the money (request_id derived from the request: it can never be posted twice).
            String myName = LedgerTransfers.nameOf(conn, userId);
            String payeeName = payeeName(conn, expense, request);
            LedgerTransfers.Posted posted = LedgerTransfers.post(conn, from, to, request.amount,
                    reimbursement ? WalletTransaction.KIND_REIMBURSEMENT : WalletTransaction.KIND_EXPENSE_PAYMENT,
                    userId, myName, payeeName, myName, transferRequestId(requestId), now(), this::insertTransaction);
            // 6. ... and mark the request PAID in the same transaction.
            int changed = update(conn, "UPDATE payment_requests SET status = 'PAID', transaction_id = ?, responded_at = ?"
                    + " WHERE request_id = ? AND status = 'PENDING'", posted.out().transactionId(), now(), requestId);
            if (changed != 1) {
                throw new SQLException("Payment request " + requestId + " changed during payment");
            }
            return new Payment(requestId, request.amount, posted.out().balanceAfter(), payeeName, false);
        });
    }

    /** The payer declines their own PENDING request. The share stays unresolved until revised. */
    public void decline(long userId, long requestId) throws ValidationException, DatabaseException {
        inTransaction(FAILED_MESSAGE, conn -> {
            RequestRow seen = requestRow(conn, requestId, false);
            if (seen == null || seen.payerId != userId) {
                throw new ValidationException(REQUEST_NOT_AVAILABLE_MESSAGE);
            }
            LedgerTransfers.lockAscending(conn, seen.walletId);
            ExpenseRow expense = expenseRow(conn, seen.expenseId, true);
            RequestRow request = requestRow(conn, requestId, true);
            if (!"PENDING".equals(request.status)) {
                throw new ValidationException(ALREADY_ANSWERED_MESSAGE);
            }
            if (!"OPEN".equals(expense.status)) {
                throw new ValidationException(EXPENSE_CANCELLED_MESSAGE);
            }
            if (LedgerTransfers.lockedRole(conn, userId, expense.walletId) == null) {
                throw new ValidationException(REQUEST_NOT_AVAILABLE_MESSAGE);
            }
            update(conn, "UPDATE payment_requests SET status = 'DECLINED', responded_at = ? WHERE request_id = ?",
                    now(), requestId);
            return null;
        });
    }

    // ---------- revising and cancelling ----------

    /**
     * The creator (or the Owner) re-allocates the expense's <em>unresolved</em> shares: every share
     * that isn't paid or covered, plus, for a reimbursement, the payer's covered share (the payer can
     * absorb more). {@code newAmounts} must give exactly those people a new amount (0 allowed), and
     * they must add up to the same total as before, so the expense stays consistent. Paid shares
     * can't be touched.
     *
     * <p>For each person: an unchanged amount with a PENDING request is left as it is; otherwise
     * their PENDING request (if any) is CANCELLED and, if they now owe something, a new PENDING
     * request replaces it. Declined requests stay DECLINED in the history.
     */
    public void revise(long userId, long expenseId, Map<Long, BigDecimal> newAmounts)
            throws ValidationException, DatabaseException {
        inTransaction(FAILED_MESSAGE, conn -> {
            ExpenseRow seen = expenseRow(conn, expenseId, false);
            if (seen == null) {
                throw new ValidationException(NOT_AVAILABLE_MESSAGE);
            }
            LedgerTransfers.lockAscending(conn, seen.walletId);
            ExpenseRow expense = expenseRow(conn, expenseId, true);
            requireManager(conn, userId, expense);
            if (!"OPEN".equals(expense.status)) {
                throw new ValidationException(EXPENSE_CANCELLED_MESSAGE);
            }
            boolean reimbursement = Collection.REIMBURSEMENT.name().equals(expense.collection);
            List<RequestRecord> requests = requests(conn, expenseId, true);
            Map<Long, BigDecimal> current = new TreeMap<>();
            Map<Long, Boolean> coveredFlags = new TreeMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, amount, covered_by_payer FROM expense_shares WHERE expense_id = ? FOR UPDATE")) {
                ps.setLong(1, expenseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        RequestRecord latest = latestRequest(requests, id);
                        boolean covered = rs.getBoolean(3);
                        boolean paid = latest != null && "PAID".equals(latest.status());
                        if (!paid) {
                            current.put(id, rs.getBigDecimal(2));
                            coveredFlags.put(id, covered);
                        }
                    }
                }
            }
            if (reimbursement && !current.containsKey(expense.paidBy)) {
                current.put(expense.paidBy, BigDecimal.ZERO.setScale(2)); // the payer may absorb a share
                coveredFlags.put(expense.paidBy, true);
            }
            boolean owedSomewhere = current.entrySet().stream()
                    .anyMatch(e -> !coveredFlags.get(e.getKey()) && e.getValue().signum() > 0);
            if (!owedSomewhere) {
                throw new ValidationException(NOTHING_TO_REVISE_MESSAGE); // only paid, covered or $0.00 shares left
            }
            if (!newAmounts.keySet().equals(current.keySet())) {
                throw new ValidationException("Give a new amount for each unpaid share, and only for those. "
                        + "Paid shares can't be changed.");
            }
            BigDecimal before = BigDecimal.ZERO;
            BigDecimal after = BigDecimal.ZERO;
            for (long id : current.keySet()) {
                BigDecimal amount = newAmounts.get(id);
                if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
                    throw new ValidationException("Each amount must be $0.00 or more, in dollars and cents.");
                }
                before = before.add(current.get(id));
                after = after.add(amount);
            }
            if (after.compareTo(before) != 0) {
                throw new ValidationException("The revised shares add up to " + Money.formatUsd(after)
                        + ", but the unpaid part of the expense is " + Money.formatUsd(before)
                        + ". They must match exactly.");
            }
            LocalDateTime now = now();
            Long payerWallet = reimbursement ? LedgerTransfers.personalWalletId(conn, expense.paidBy) : null;
            for (long id : current.keySet()) {
                BigDecimal amount = newAmounts.get(id).setScale(2, RoundingMode.UNNECESSARY);
                boolean covered = coveredFlags.get(id);
                if (covered) { // the reimbursement payer's own part: no request
                    upsertShare(conn, expenseId, id, amount, true);
                    continue;
                }
                // Someone who left or was removed can't be asked to pay (even an unchanged amount).
                if (amount.signum() > 0 && LedgerTransfers.lockedRole(conn, id, expense.walletId) == null) {
                    throw new ValidationException(nameOf(conn, id) + " is no longer a member of the wallet, so "
                            + "they can't be asked to pay. Set their share to $0.00.");
                }
                RequestRecord latest = latestRequest(requests, id);
                boolean pending = latest != null && "PENDING".equals(latest.status());
                if (pending && latest.amount().compareTo(amount) == 0) {
                    continue; // unchanged and still waiting: keep the request
                }
                if (pending) {
                    update(conn, "UPDATE payment_requests SET status = 'CANCELLED', responded_at = ?"
                            + " WHERE request_id = ? AND status = 'PENDING'", now, latest.requestId());
                }
                upsertShare(conn, expenseId, id, amount, false);
                if (amount.signum() > 0) {
                    insertRequest(conn, expenseId, userId, id, reimbursement ? payerWallet : expense.walletId, amount,
                            latest == null ? null : latest.requestId(), now);
                }
            }
            return null;
        });
    }

    /**
     * The creator (or the Owner) cancels the expense: its PENDING requests are CANCELLED. Money
     * already paid stays where it is (it was a real transfer); nothing is refunded automatically.
     */
    public void cancel(long userId, long expenseId) throws ValidationException, DatabaseException {
        inTransaction(FAILED_MESSAGE, conn -> {
            ExpenseRow seen = expenseRow(conn, expenseId, false);
            if (seen == null) {
                throw new ValidationException(NOT_AVAILABLE_MESSAGE);
            }
            LedgerTransfers.lockAscending(conn, seen.walletId);
            ExpenseRow expense = expenseRow(conn, expenseId, true);
            requireManager(conn, userId, expense);
            if (!"OPEN".equals(expense.status)) {
                throw new ValidationException(EXPENSE_CANCELLED_MESSAGE);
            }
            requests(conn, expenseId, true); // lock the requests
            LocalDateTime now = now();
            update(conn, "UPDATE payment_requests SET status = 'CANCELLED', responded_at = ?"
                    + " WHERE expense_id = ? AND status = 'PENDING'", now, expenseId);
            update(conn, "UPDATE expenses SET status = 'CANCELLED', cancelled_at = ? WHERE expense_id = ?", now, expenseId);
            return null;
        });
    }

    /**
     * Inserts one transfer row of a payment. Protected only so a test can simulate a failure and
     * check that the payment is rolled back and the request stays PENDING.
     */
    protected long insertTransaction(Connection conn, WalletTransaction t, String requestId, long initiatedBy)
            throws SQLException {
        return TransactionRecords.insert(conn, t, requestId, initiatedBy);
    }

    /** The transfer's request id for a payment request: unique per request, so it can't be paid twice. */
    static String transferRequestId(long paymentRequestId) {
        return TRANSFER_PREFIX + paymentRequestId;
    }

    /** Prefix of the transfer ids of expense payments (see {@link #transferRequestId}). */
    public static final String TRANSFER_PREFIX = "expense-request-";

    // ---------- helpers ----------

    private static final String SUMMARY_SELECT = "SELECT e.expense_id, e.shared_wallet_id, e.title, e.total_amount,"
            + " e.collection_method, e.split_method, e.status, e.notes, e.created_by_user_id, e.paid_by_user_id,"
            + " e.created_at, w.name AS wallet_name,"
            + " CONCAT(cu.first_name, ' ', cu.last_name) AS created_by_name,"
            + " CONCAT(pu.first_name, ' ', pu.last_name) AS paid_by_name,"
            + " (SELECT COALESCE(SUM(r.amount), 0) FROM payment_requests r WHERE r.expense_id = e.expense_id"
            + "   AND r.status = 'PAID') AS collected,"
            + " (SELECT COALESCE(SUM(s.amount), 0) FROM expense_shares s WHERE s.expense_id = e.expense_id"
            + "   AND s.covered_by_payer) AS covered"
            + " FROM expenses e JOIN wallets w ON w.wallet_id = e.shared_wallet_id"
            + " JOIN users cu ON cu.user_id = e.created_by_user_id"
            + " LEFT JOIN users pu ON pu.user_id = e.paid_by_user_id";

    private static ExpenseSummary readSummary(ResultSet rs) throws SQLException {
        BigDecimal total = rs.getBigDecimal("total_amount");
        BigDecimal collected = rs.getBigDecimal("collected").setScale(2);
        BigDecimal covered = rs.getBigDecimal("covered").setScale(2);
        String status = rs.getString("status");
        BigDecimal outstanding = "CANCELLED".equals(status) ? BigDecimal.ZERO.setScale(2)
                : total.subtract(collected).subtract(covered).max(BigDecimal.ZERO);
        return new ExpenseSummary(rs.getLong("expense_id"), rs.getLong("shared_wallet_id"), rs.getString("title"), total,
                Collection.valueOf(rs.getString("collection_method")), SplitMethod.valueOf(rs.getString("split_method")),
                status, collected, covered, outstanding, rs.getString("created_by_name"), rs.getString("paid_by_name"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private record ExpenseRow(long expenseId, long walletId, long createdBy, Long paidBy, String collection,
                              String status) {
    }

    private record RequestRow(long requestId, long expenseId, long walletId, long payerId, long destinationWalletId,
                              BigDecimal amount, String status) {
    }

    private static ExpenseRow expenseRow(Connection conn, long expenseId, boolean lock) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT expense_id, shared_wallet_id, created_by_user_id,"
                + " paid_by_user_id, collection_method, status FROM expenses WHERE expense_id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, expenseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long paid = rs.getLong(4);
                Long paidBy = rs.wasNull() ? null : paid;
                return new ExpenseRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), paidBy, rs.getString(5),
                        rs.getString(6));
            }
        }
    }

    private static RequestRow requestRow(Connection conn, long requestId, boolean lock) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT r.request_id, r.expense_id, e.shared_wallet_id,"
                + " r.payer_id, r.destination_wallet_id, r.amount, r.status FROM payment_requests r"
                + " JOIN expenses e ON e.expense_id = r.expense_id WHERE r.request_id = ?"
                + (lock ? " FOR UPDATE OF r" : ""))) {
            ps.setLong(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new RequestRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getBigDecimal(6), rs.getString(7)) : null;
            }
        }
    }

    /** All requests of an expense, oldest first (locked when {@code lock}). */
    private static List<RequestRecord> requests(Connection conn, long expenseId, boolean lock) throws SQLException {
        String sql = "SELECT r.request_id, r.payer_id, CONCAT(u.first_name, ' ', u.last_name), r.amount, r.status,"
                + " r.created_at, r.responded_at, t.transfer_id, r.replaces_request_id FROM payment_requests r"
                + " JOIN users u ON u.user_id = r.payer_id"
                + " LEFT JOIN wallet_transactions t ON t.transaction_id = r.transaction_id"
                + " WHERE r.expense_id = ? ORDER BY r.request_id" + (lock ? " FOR UPDATE OF r" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, expenseId);
            List<RequestRecord> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long replacesId = rs.getLong(9);
                    Long replaces = rs.wasNull() ? null : replacesId; // checked right after reading column 9
                    list.add(new RequestRecord(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4),
                            rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class),
                            rs.getString(8), replaces));
                }
            }
            return list;
        }
    }

    private static RequestRecord latestRequest(List<RequestRecord> requests, long payerId) {
        RequestRecord latest = null;
        for (RequestRecord r : requests) {
            if (r.payerId() == payerId) {
                latest = r;
            }
        }
        return latest;
    }

    private static String payeeName(Connection conn, ExpenseRow expense, RequestRow request) throws SQLException {
        if (expense.paidBy != null) {
            return LedgerTransfers.nameOf(conn, expense.paidBy);
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM wallets WHERE wallet_id = ?")) {
            ps.setLong(1, request.destinationWalletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String nameOf(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CONCAT(first_name, ' ', last_name) FROM users WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void requireManager(Connection conn, long userId, ExpenseRow expense)
            throws SQLException, ValidationException {
        MemberRole role = LedgerTransfers.lockedRole(conn, userId, expense.walletId);
        if (role == null) {
            throw new ValidationException(NOT_AVAILABLE_MESSAGE);
        }
        if (expense.createdBy != userId && role != MemberRole.OWNER) {
            throw new ValidationException(MANAGE_ONLY_MESSAGE);
        }
    }

    private static MemberRole roleOf(Connection conn, long userId, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT member_role FROM shared_wallet_members"
                + " WHERE user_id = ? AND shared_wallet_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MemberRole.valueOf(rs.getString(1)) : null;
            }
        }
    }

    private static void requireMember(Connection conn, long userId, long walletId, String message)
            throws SQLException, ValidationException {
        if (roleOf(conn, userId, walletId) == null) {
            throw new ValidationException(message);
        }
    }

    private static long insertExpense(Connection conn, long walletId, long userId, NewExpense input, BigDecimal total,
                                      Long payer, LocalDateTime now) throws SQLException {
        String sql = "INSERT INTO expenses (shared_wallet_id, created_by_user_id, title, total_amount, notes, split_method,"
                + " collection_method, paid_by_user_id, status, expense_date, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, walletId);
            ps.setLong(2, userId);
            ps.setString(3, input.title().strip());
            ps.setBigDecimal(4, total);
            String notes = input.notes() == null || input.notes().isBlank() ? null : input.notes().strip();
            ps.setString(5, notes);
            ps.setString(6, input.split().name());
            ps.setString(7, input.collection().name());
            if (payer == null) {
                ps.setNull(8, Types.BIGINT);
            } else {
                ps.setLong(8, payer);
            }
            ps.setObject(9, LocalDate.from(now));
            ps.setObject(10, now);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static void insertShare(Connection conn, long expenseId, long userId, BigDecimal amount, boolean covered)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO expense_shares (expense_id, user_id, amount,"
                + " covered_by_payer) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, expenseId);
            ps.setLong(2, userId);
            ps.setBigDecimal(3, amount);
            ps.setBoolean(4, covered);
            ps.executeUpdate();
        }
    }

    private static void upsertShare(Connection conn, long expenseId, long userId, BigDecimal amount, boolean covered)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO expense_shares (expense_id, user_id, amount,"
                + " covered_by_payer) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)")) {
            ps.setLong(1, expenseId);
            ps.setLong(2, userId);
            ps.setBigDecimal(3, amount);
            ps.setBoolean(4, covered);
            ps.executeUpdate();
        }
    }

    private static void insertRequest(Connection conn, long expenseId, long requesterId, long payerId,
                                      long destinationWalletId, BigDecimal amount, Long replaces, LocalDateTime now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO payment_requests (expense_id, requester_id,"
                + " payer_id, destination_wallet_id, amount, status, replaces_request_id, created_at)"
                + " VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)")) {
            ps.setLong(1, expenseId);
            ps.setLong(2, requesterId);
            ps.setLong(3, payerId);
            ps.setLong(4, destinationWalletId);
            ps.setBigDecimal(5, amount);
            if (replaces == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, replaces);
            }
            ps.setObject(7, now);
            ps.executeUpdate();
        }
    }

    private static int update(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            return ps.executeUpdate();
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The database did not return the new row's id.");
            }
            return keys.getLong(1);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private interface Work<T> {
        T run(Connection conn) throws SQLException, ValidationException;
    }

    private <T> T inTransaction(String failure, Work<T> work) throws ValidationException, DatabaseException {
        Connection conn = connect(failure);
        try (conn) {
            conn.setAutoCommit(false);
            // READ COMMITTED: the duplicate-request check (a locking read of a request id that
            // usually doesn't exist yet) then takes no gap lock, so transfers on unrelated wallets
            // can't deadlock on the unique request_id index. Every decision is still made on rows
            // locked with FOR UPDATE / FOR SHARE, which always read the latest committed data.
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException | ValidationException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(failure, e);
        }
    }

    private Connection connect(String message) throws DatabaseException {
        try {
            return database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(message, e);
        }
    }
}
