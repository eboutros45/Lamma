-- V008: Shared wallet financial operations (Shared Wallet P03).
--
-- Shared-wallet money movements reuse the existing transfer rows (V005): every
-- operation is ONE logical transfer written as TWO rows in one database transaction,
--   * TRANSFER_OUT (DEBIT)  on the wallet the money leaves
--   * TRANSFER_IN  (CREDIT) on the wallet the money enters
-- linked by the same transfer_id and pointing at each other via counterparty_wallet_id.
--
-- Two columns say what kind of transfer it was and who started it:
--   * transfer_kind
--       NULL          personal Send Money (unchanged; existing rows keep NULL)
--       CONTRIBUTION  member's personal wallet -> shared wallet
--       WITHDRAWAL    shared wallet -> the withdrawing member's own personal wallet
--       SHARED_SEND   shared wallet -> a registered user's personal wallet
--   * initiated_by_user_id  the member who started it (required for the three kinds
--     above), so the shared wallet's history can say who contributed, withdrew or sent.
-- Both rows of one transfer carry the same kind and initiator.
-- Existing rows are not changed.
ALTER TABLE wallet_transactions
    ADD COLUMN transfer_kind VARCHAR(20) NULL AFTER counterparty_wallet_id,
    ADD COLUMN initiated_by_user_id BIGINT UNSIGNED NULL AFTER transfer_kind,
    ADD CONSTRAINT fk_wallet_transactions_initiated_by
        FOREIGN KEY (initiated_by_user_id) REFERENCES users (user_id),
    ADD CONSTRAINT chk_wallet_transactions_transfer_kind
        CHECK (transfer_kind IS NULL OR transfer_kind IN ('CONTRIBUTION', 'WITHDRAWAL', 'SHARED_SEND')),
    ADD CONSTRAINT chk_wallet_transactions_kind_details CHECK (
        (transfer_kind IS NULL AND initiated_by_user_id IS NULL)
        OR (transfer_kind IS NOT NULL
            AND initiated_by_user_id IS NOT NULL
            AND transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN')));
