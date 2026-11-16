-- V010: Shared expenses and payment requests (P04C).
--
-- Follows the LDM entities EXPENSE and PAYMENT_REQUEST, plus one table the LDM does not
-- have (expense_shares) because an expense is split between several members and the
-- split can be revised after a decline.
--
-- How the money moves: an expense never spends the shared balance and never charges
-- anyone by itself. Each participant gets a payment request; paying it is a normal
-- LAMMA transfer (the two-row TRANSFER_OUT / TRANSFER_IN pair of V005/V008):
--   * collection_method SHARED_WALLET:  participant's personal wallet -> shared wallet
--                                       (transfer_kind EXPENSE_PAYMENT)
--   * collection_method REIMBURSEMENT:  participant's personal wallet -> the personal
--                                       wallet of the member who paid the bill
--                                       (transfer_kind REIMBURSEMENT)
-- The bill itself, paid outside LAMMA by paid_by_user_id, is NOT recorded as a wallet
-- transaction; that member's own share is marked covered_by_payer.

ALTER TABLE wallet_transactions
    DROP CHECK chk_wallet_transactions_transfer_kind,
    ADD CONSTRAINT chk_wallet_transactions_transfer_kind CHECK (transfer_kind IS NULL OR transfer_kind IN
        ('CONTRIBUTION', 'WITHDRAWAL', 'SHARED_SEND', 'EXPENSE_PAYMENT', 'REIMBURSEMENT'));

-- EXPENSE. LDM columns used: expense_id, shared_wallet_id, title, total_amount,
-- split_method, expense_date, notes, created_at. Not used yet: category_id,
-- billing_period, confirmed_at, transaction_id (a reimbursed bill was paid outside LAMMA,
-- and pooled money is not spent automatically).
CREATE TABLE expenses (
    expense_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shared_wallet_id    BIGINT UNSIGNED NOT NULL,
    created_by_user_id  BIGINT UNSIGNED NOT NULL,
    title               VARCHAR(80)     NOT NULL,
    total_amount        DECIMAL(15, 2)  NOT NULL,
    notes               VARCHAR(500)    NULL,
    split_method        VARCHAR(10)     NOT NULL,
    collection_method   VARCHAR(20)     NOT NULL,
    paid_by_user_id     BIGINT UNSIGNED NULL,
    status              VARCHAR(10)     NOT NULL,
    expense_date        DATE            NOT NULL,
    created_at          DATETIME        NOT NULL,
    cancelled_at        DATETIME        NULL,

    CONSTRAINT pk_expenses PRIMARY KEY (expense_id),
    CONSTRAINT fk_expenses_wallet FOREIGN KEY (shared_wallet_id) REFERENCES shared_wallets (shared_wallet_id),
    CONSTRAINT fk_expenses_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_expenses_paid_by FOREIGN KEY (paid_by_user_id) REFERENCES users (user_id),
    CONSTRAINT chk_expenses_title_not_blank CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_expenses_total_positive CHECK (total_amount > 0),
    CONSTRAINT chk_expenses_split CHECK (split_method IN ('EQUAL', 'CUSTOM')),
    CONSTRAINT chk_expenses_collection CHECK (collection_method IN ('SHARED_WALLET', 'REIMBURSEMENT')),
    CONSTRAINT chk_expenses_payer_matches_collection CHECK (
        (collection_method = 'REIMBURSEMENT' AND paid_by_user_id IS NOT NULL)
        OR (collection_method = 'SHARED_WALLET' AND paid_by_user_id IS NULL)),
    CONSTRAINT chk_expenses_status CHECK (status IN ('OPEN', 'CANCELLED')),
    CONSTRAINT chk_expenses_cancelled_at CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR (status = 'OPEN' AND cancelled_at IS NULL)),
    INDEX idx_expenses_wallet (shared_wallet_id, expense_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Who shares the expense and how much each one's part is (the current allocation).
-- The amounts always add up to expenses.total_amount. covered_by_payer marks the
-- reimbursement payer's own part (already paid by them outside LAMMA).
CREATE TABLE expense_shares (
    expense_id        BIGINT UNSIGNED NOT NULL,
    user_id           BIGINT UNSIGNED NOT NULL,
    amount            DECIMAL(15, 2)  NOT NULL,
    covered_by_payer  BOOLEAN         NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_expense_shares PRIMARY KEY (expense_id, user_id),
    CONSTRAINT fk_expense_shares_expense FOREIGN KEY (expense_id) REFERENCES expenses (expense_id),
    CONSTRAINT fk_expense_shares_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT chk_expense_shares_amount CHECK (amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- PAYMENT_REQUEST, for expense shares. LDM columns used: request_id, requester_id,
-- payer_id, destination_wallet_id, expense_id, transaction_id, amount, status,
-- created_at, responded_at. Not used: description, qr_type, qr_token, due_date,
-- expires_at (QR payment requests are out of scope).
--   status PENDING   -> waiting for the payer
--          PAID      -> paid by a LAMMA transfer; transaction_id = its TRANSFER_OUT row
--          DECLINED  -> the payer said no; the share stays unresolved until revised
--          CANCELLED -> replaced by a revised request, or the expense was cancelled
-- replaces_request_id links a revised request to the one it replaces (history kept).
-- At most one PENDING or PAID request per person per expense (UNIQUE below), so a
-- share can never be collected twice.
CREATE TABLE payment_requests (
    request_id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    expense_id             BIGINT UNSIGNED NOT NULL,
    requester_id           BIGINT UNSIGNED NOT NULL,
    payer_id               BIGINT UNSIGNED NOT NULL,
    destination_wallet_id  BIGINT UNSIGNED NOT NULL,
    amount                 DECIMAL(15, 2)  NOT NULL,
    status                 VARCHAR(10)     NOT NULL,
    transaction_id         BIGINT UNSIGNED NULL,
    replaces_request_id    BIGINT UNSIGNED NULL,
    created_at             DATETIME        NOT NULL,
    responded_at           DATETIME        NULL,
    open_payer_id          BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING', 'PAID') THEN payer_id END) STORED,

    CONSTRAINT pk_payment_requests PRIMARY KEY (request_id),
    CONSTRAINT fk_payment_requests_expense FOREIGN KEY (expense_id) REFERENCES expenses (expense_id),
    CONSTRAINT fk_payment_requests_requester FOREIGN KEY (requester_id) REFERENCES users (user_id),
    CONSTRAINT fk_payment_requests_payer FOREIGN KEY (payer_id) REFERENCES users (user_id),
    CONSTRAINT fk_payment_requests_destination FOREIGN KEY (destination_wallet_id) REFERENCES wallets (wallet_id),
    CONSTRAINT fk_payment_requests_transaction FOREIGN KEY (transaction_id)
        REFERENCES wallet_transactions (transaction_id),
    CONSTRAINT fk_payment_requests_replaces FOREIGN KEY (replaces_request_id)
        REFERENCES payment_requests (request_id),
    CONSTRAINT uq_payment_requests_transaction UNIQUE (transaction_id),
    CONSTRAINT uq_payment_requests_one_open UNIQUE (expense_id, open_payer_id),
    CONSTRAINT chk_payment_requests_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_requests_status CHECK (status IN ('PENDING', 'PAID', 'DECLINED', 'CANCELLED')),
    CONSTRAINT chk_payment_requests_paid_has_transaction CHECK (
        (status = 'PAID' AND transaction_id IS NOT NULL) OR (status <> 'PAID' AND transaction_id IS NULL)),
    CONSTRAINT chk_payment_requests_responded CHECK (
        (status = 'PENDING' AND responded_at IS NULL) OR (status <> 'PENDING' AND responded_at IS NOT NULL)),
    INDEX idx_payment_requests_payer (payer_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
