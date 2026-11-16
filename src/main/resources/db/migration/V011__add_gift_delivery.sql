-- V011: Gift delivery (P05).
--
-- A gift wallet (a shared wallet with a gift_recipient_id, P04B) is delivered by sending its
-- whole balance to the recipient's personal wallet. Delivery is a normal two-row transfer
-- (TRANSFER_OUT / TRANSFER_IN, transfer_kind GIFT_DELIVERY) and, in the same database
-- transaction, the wallet becomes COMPLETED: read-only, balance 0.00, kept for its history.
--
-- WALLET.status (LDM) gains COMPLETED. This is wallet-wide (every member sees it as completed);
-- it is not a per-member "archive" (hiding a wallet for one member), which LAMMA doesn't have.
-- completed_at records when. The CHECKs guarantee a completed wallet is a shared wallet whose
-- balance is zero, so no later transfer can ever put money into it.
-- The transfer that delivered the gift is found through its transfer_kind on this wallet;
-- no separate gift balance or gift table is added.
ALTER TABLE wallets
    ADD COLUMN completed_at DATETIME NULL AFTER status,
    DROP CHECK chk_wallets_status,
    ADD CONSTRAINT chk_wallets_status CHECK (status IN ('ACTIVE', 'COMPLETED')),
    ADD CONSTRAINT chk_wallets_completed CHECK (
        (status = 'ACTIVE' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND wallet_type = 'SHARED' AND balance = 0));

ALTER TABLE wallet_transactions
    DROP CHECK chk_wallet_transactions_transfer_kind,
    ADD CONSTRAINT chk_wallet_transactions_transfer_kind CHECK (transfer_kind IS NULL OR transfer_kind IN
        ('CONTRIBUTION', 'WITHDRAWAL', 'SHARED_SEND', 'EXPENSE_PAYMENT', 'REIMBURSEMENT', 'GIFT_DELIVERY'));
