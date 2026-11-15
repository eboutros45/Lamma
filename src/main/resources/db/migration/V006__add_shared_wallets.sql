-- V006: Shared wallets (Shared Wallet P01), following the LAMMA LDM.
--
-- WALLET (existing table "wallets") gains the LDM attributes it was missing:
--   * name    - every wallet has a display name; existing and new personal wallets
--               default to 'Personal Wallet'.
--   * status  - lifecycle state; only ACTIVE exists for now (closure comes later).
--   * wallet_type now allows SHARED.
--   * owner_user_id is the LDM's personal_user_id: required for PERSONAL wallets and
--     NULL for SHARED wallets (whose people are listed in shared_wallet_members).
--     The existing UNIQUE rule still allows at most one personal wallet per user.
-- A shared wallet is a wallet: same id, same balance column, same transaction history.
ALTER TABLE wallets
    MODIFY COLUMN owner_user_id BIGINT UNSIGNED NULL,
    ADD COLUMN name VARCHAR(60) NOT NULL DEFAULT 'Personal Wallet' AFTER wallet_type,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER balance,
    DROP CHECK chk_wallets_type,
    ADD CONSTRAINT chk_wallets_type CHECK (wallet_type IN ('PERSONAL', 'SHARED')),
    ADD CONSTRAINT chk_wallets_owner_matches_type CHECK (
        (wallet_type = 'PERSONAL' AND owner_user_id IS NOT NULL)
        OR (wallet_type = 'SHARED' AND owner_user_id IS NULL)),
    ADD CONSTRAINT chk_wallets_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    ADD CONSTRAINT chk_wallets_status CHECK (status IN ('ACTIVE'));

-- SHARED_WALLET: the shared-wallet attributes of a wallet (1:1, same id).
-- gift_recipient_id is part of the LDM; it is optional and not used by the app yet.
CREATE TABLE shared_wallets (
    shared_wallet_id   BIGINT UNSIGNED NOT NULL,
    gift_recipient_id  BIGINT UNSIGNED NULL,
    purpose            VARCHAR(80)     NULL,
    description        VARCHAR(500)    NULL,
    target_amount      DECIMAL(15, 2)  NULL,

    CONSTRAINT pk_shared_wallets PRIMARY KEY (shared_wallet_id),
    CONSTRAINT fk_shared_wallets_wallet FOREIGN KEY (shared_wallet_id) REFERENCES wallets (wallet_id),
    CONSTRAINT fk_shared_wallets_gift_recipient FOREIGN KEY (gift_recipient_id) REFERENCES users (user_id),
    CONSTRAINT chk_shared_wallets_target_positive CHECK (target_amount IS NULL OR target_amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- SHARED_WALLET_MEMBER: who belongs to which shared wallet, and with which role.
-- OWNER: full administration and money permissions; CONTRIBUTOR (default for future
-- members): can contribute but not withdraw; SPENDER: can contribute and send money out.
-- Having a row here is what gives a user access to the shared wallet.
CREATE TABLE shared_wallet_members (
    user_id           BIGINT UNSIGNED NOT NULL,
    shared_wallet_id  BIGINT UNSIGNED NOT NULL,
    member_role       VARCHAR(20)     NOT NULL,
    joined_at         DATETIME        NOT NULL,

    CONSTRAINT pk_shared_wallet_members PRIMARY KEY (user_id, shared_wallet_id),
    CONSTRAINT fk_shared_wallet_members_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_shared_wallet_members_wallet FOREIGN KEY (shared_wallet_id)
        REFERENCES shared_wallets (shared_wallet_id),
    CONSTRAINT chk_shared_wallet_members_role CHECK (member_role IN ('OWNER', 'CONTRIBUTOR', 'SPENDER')),
    INDEX idx_shared_wallet_members_wallet (shared_wallet_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
