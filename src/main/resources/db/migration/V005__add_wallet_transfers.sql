-- V005: Alpha 2 P03 - wallet-to-wallet transfers (Send Money).
--
-- A transfer is written as TWO rows in one database transaction:
--   * TRANSFER_OUT (DEBIT)  on the sender's wallet
--   * TRANSFER_IN  (CREDIT) on the recipient's wallet
-- Both rows carry the same transfer_id (the sender's request id) and point at the
-- other wallet through counterparty_wallet_id, so each side can show who the
-- money went to or came from. Names are read from users through that wallet;
-- they are not copied into the transaction.
-- UNIQUE (transfer_id, transaction_type) allows exactly one row per side.
ALTER TABLE wallet_transactions
    ADD COLUMN transfer_id VARCHAR(36) NULL AFTER card_last4,
    ADD COLUMN counterparty_wallet_id BIGINT UNSIGNED NULL AFTER transfer_id,
    ADD CONSTRAINT fk_wallet_transactions_counterparty
        FOREIGN KEY (counterparty_wallet_id) REFERENCES wallets (wallet_id),
    ADD CONSTRAINT uq_wallet_transactions_transfer_side UNIQUE (transfer_id, transaction_type),
    DROP CHECK chk_wallet_transactions_type,
    ADD CONSTRAINT chk_wallet_transactions_type
        CHECK (transaction_type IN ('CASH_TOP_UP', 'CARD_TOP_UP', 'TRANSFER_OUT', 'TRANSFER_IN')),
    ADD CONSTRAINT chk_wallet_transactions_transfer_direction CHECK (
        (transaction_type <> 'TRANSFER_OUT' OR direction = 'DEBIT')
        AND (transaction_type <> 'TRANSFER_IN' OR direction = 'CREDIT')),
    ADD CONSTRAINT chk_wallet_transactions_transfer_details CHECK (
        (transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN')
            AND transfer_id IS NOT NULL
            AND counterparty_wallet_id IS NOT NULL
            AND counterparty_wallet_id <> wallet_id)
        OR (transaction_type NOT IN ('TRANSFER_OUT', 'TRANSFER_IN')
            AND transfer_id IS NULL
            AND counterparty_wallet_id IS NULL));
