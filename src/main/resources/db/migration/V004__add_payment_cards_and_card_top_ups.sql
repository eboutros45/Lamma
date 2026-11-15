-- V004: Alpha 2 P02 - saved payment cards and card top-ups.
--
-- payment_cards holds ONLY the metadata needed to recognise a simulated card:
-- nickname, cardholder name, brand, last four digits and expiry. The full card
-- number and the CVV are never stored (LAMMA processes no real payments).
-- Removing a card deletes its row; past transactions keep their own copy of the
-- card's brand and last four digits, so history is never affected.
CREATE TABLE payment_cards (
    card_id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_id          BIGINT UNSIGNED  NOT NULL,
    nickname         VARCHAR(40)      NOT NULL,
    cardholder_name  VARCHAR(60)      NOT NULL,
    brand            VARCHAR(20)      NOT NULL,
    last4            CHAR(4)          NOT NULL,
    exp_month        TINYINT UNSIGNED NOT NULL,
    exp_year         SMALLINT UNSIGNED NOT NULL,
    created_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_payment_cards PRIMARY KEY (card_id),
    CONSTRAINT fk_payment_cards_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uq_payment_cards_same_card UNIQUE (user_id, brand, last4, exp_month, exp_year),
    CONSTRAINT chk_payment_cards_nickname CHECK (CHAR_LENGTH(TRIM(nickname)) > 0),
    CONSTRAINT chk_payment_cards_cardholder CHECK (CHAR_LENGTH(TRIM(cardholder_name)) > 0),
    CONSTRAINT chk_payment_cards_brand CHECK (brand IN ('VISA', 'MASTERCARD', 'AMEX', 'DISCOVER', 'OTHER')),
    CONSTRAINT chk_payment_cards_last4 CHECK (last4 REGEXP '^[0-9]{4}$'),
    CONSTRAINT chk_payment_cards_exp_month CHECK (exp_month BETWEEN 1 AND 12),
    CONSTRAINT chk_payment_cards_exp_year CHECK (exp_year BETWEEN 2000 AND 2099)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Card top-ups: a new transaction type plus a snapshot of the card used.
-- Cash top-ups have no card details; card top-ups must have them.
ALTER TABLE wallet_transactions
    ADD COLUMN card_brand VARCHAR(20) NULL AFTER status,
    ADD COLUMN card_last4 CHAR(4) NULL AFTER card_brand,
    DROP CHECK chk_wallet_transactions_type,
    DROP CHECK chk_wallet_transactions_top_up_is_credit,
    ADD CONSTRAINT chk_wallet_transactions_type
        CHECK (transaction_type IN ('CASH_TOP_UP', 'CARD_TOP_UP')),
    ADD CONSTRAINT chk_wallet_transactions_top_up_is_credit
        CHECK (transaction_type NOT IN ('CASH_TOP_UP', 'CARD_TOP_UP') OR direction = 'CREDIT'),
    ADD CONSTRAINT chk_wallet_transactions_card_details CHECK (
        (transaction_type = 'CARD_TOP_UP' AND card_brand IS NOT NULL AND card_last4 IS NOT NULL)
        OR (transaction_type <> 'CARD_TOP_UP' AND card_brand IS NULL AND card_last4 IS NULL)),
    ADD CONSTRAINT chk_wallet_transactions_card_last4
        CHECK (card_last4 IS NULL OR card_last4 REGEXP '^[0-9]{4}$');
