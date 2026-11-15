-- V009: Contact book (P04A).
--
-- Each user's own list of names and phone numbers, imported from CSV or vCard files.
-- Contacts are private to the user who imported them (owner_user_id) and are NOT
-- LAMMA accounts: choosing a contact only fills in a phone number, which is then
-- looked up and validated like a typed number.
-- phone_number is normalized like users.phone_number (digits only, country code
-- included). One entry per number per owner, so importing the same file twice adds
-- nothing.
CREATE TABLE contacts (
    contact_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_user_id  BIGINT UNSIGNED NOT NULL,
    display_name   VARCHAR(100)    NOT NULL,
    phone_number   VARCHAR(15)     NOT NULL,
    created_at     DATETIME        NOT NULL,

    CONSTRAINT pk_contacts PRIMARY KEY (contact_id),
    CONSTRAINT fk_contacts_owner FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
    CONSTRAINT uq_contacts_owner_phone UNIQUE (owner_user_id, phone_number),
    CONSTRAINT chk_contacts_phone_format CHECK (phone_number REGEXP '^[0-9]{10,15}$'),
    CONSTRAINT chk_contacts_name_not_blank CHECK (CHAR_LENGTH(TRIM(display_name)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
