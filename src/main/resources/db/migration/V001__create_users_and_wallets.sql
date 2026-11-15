-- V001: Alpha 1 initial schema - users (USER entity) and wallets (WALLET entity).
--
-- Rules for migration files (see SchemaMigrator):
--   * Never edit a migration after it has been applied; add a new Vxxx file instead.
--   * Each statement ends with a semicolon at the end of a line.
--   * Full-line comments start with "--".

-- A person using LAMMA. Business owners (Alpha 4) will reuse this same row;
-- there is deliberately no separate business login identity.
CREATE TABLE users (
    user_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    first_name    VARCHAR(50)     NOT NULL,
    last_name     VARCHAR(50)     NOT NULL,
    -- Normalized phone number: digits only, country code included, no spaces,
    -- dashes, brackets or leading "+" (E.164 without the "+", max 15 digits).
    phone_number  VARCHAR(15)     NOT NULL,
    -- Salted hash of the six-digit PIN (algorithm chosen in P02).
    -- The plaintext PIN is never stored; the length check rejects a raw PIN.
    pin_hash      VARCHAR(255)    NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uq_users_phone_number UNIQUE (phone_number),
    CONSTRAINT chk_users_phone_number_format CHECK (phone_number REGEXP '^[0-9]{10,15}$'),
    CONSTRAINT chk_users_first_name_not_blank CHECK (CHAR_LENGTH(TRIM(first_name)) > 0),
    CONSTRAINT chk_users_last_name_not_blank CHECK (CHAR_LENGTH(TRIM(last_name)) > 0),
    CONSTRAINT chk_users_pin_hash_length CHECK (CHAR_LENGTH(pin_hash) >= 32)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- A wallet holding a simulated balance. Alpha 1 only has PERSONAL wallets;
-- later alphas add more types (e.g. SHARED, BUSINESS) by widening the
-- wallet_type check in a new migration.
CREATE TABLE wallets (
    wallet_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    wallet_type    VARCHAR(20)     NOT NULL,
    owner_user_id  BIGINT UNSIGNED NOT NULL,
    -- Simulated funds in dollars. DECIMAL avoids floating-point rounding errors.
    balance        DECIMAL(15, 2)  NOT NULL DEFAULT 0.00,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Equals owner_user_id for PERSONAL wallets and NULL for every other type.
    -- The UNIQUE constraint below therefore allows at most one personal wallet
    -- per user, while future non-personal wallets (NULL here) are unaffected.
    personal_owner_user_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN wallet_type = 'PERSONAL' THEN owner_user_id END) STORED,

    CONSTRAINT pk_wallets PRIMARY KEY (wallet_id),
    CONSTRAINT fk_wallets_owner_user FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
    CONSTRAINT uq_wallets_one_personal_per_user UNIQUE (personal_owner_user_id),
    CONSTRAINT chk_wallets_type CHECK (wallet_type IN ('PERSONAL')),
    CONSTRAINT chk_wallets_balance_not_negative CHECK (balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
