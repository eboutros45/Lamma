-- V007: Shared Wallet P02 - invitations, QR join requests and membership management.
--
-- The LDM has no entities for pending invitations or join requests, so these are minimal
-- supporting tables. Membership itself stays in shared_wallet_members (LDM
-- SHARED_WALLET_MEMBER): a row there is access; removing or leaving deletes the row.
-- No financial records are touched.

-- QR invitations: one opaque, random token per shared wallet, stored with the wallet.
-- NULL means QR invitations are turned off. Generating a new token makes the old one
-- invalid. The token is not a wallet id and grants nothing by itself: it only lets a
-- signed-in user ask to join, and the Owner must approve.
ALTER TABLE shared_wallets
    ADD COLUMN invite_token VARCHAR(64) NULL,
    ADD CONSTRAINT uq_shared_wallets_invite_token UNIQUE (invite_token);

-- Direct invitations by phone number. Accepting makes the invitee a CONTRIBUTOR.
-- At most one PENDING invitation per (wallet, invitee): the generated column is the
-- invitee's id only while pending, and NULLs do not clash in a UNIQUE index.
CREATE TABLE shared_wallet_invitations (
    invitation_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shared_wallet_id    BIGINT UNSIGNED NOT NULL,
    invitee_user_id     BIGINT UNSIGNED NOT NULL,
    invited_by_user_id  BIGINT UNSIGNED NOT NULL,
    status              VARCHAR(12)     NOT NULL,
    created_at          DATETIME        NOT NULL,
    responded_at        DATETIME        NULL,
    pending_invitee_id  BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN invitee_user_id END) STORED,

    CONSTRAINT pk_shared_wallet_invitations PRIMARY KEY (invitation_id),
    CONSTRAINT fk_shared_wallet_invitations_wallet FOREIGN KEY (shared_wallet_id)
        REFERENCES shared_wallets (shared_wallet_id),
    CONSTRAINT fk_shared_wallet_invitations_invitee FOREIGN KEY (invitee_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_shared_wallet_invitations_inviter FOREIGN KEY (invited_by_user_id) REFERENCES users (user_id),
    CONSTRAINT uq_shared_wallet_invitations_one_pending UNIQUE (shared_wallet_id, pending_invitee_id),
    CONSTRAINT chk_shared_wallet_invitations_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT chk_shared_wallet_invitations_not_self CHECK (invitee_user_id <> invited_by_user_id),
    INDEX idx_shared_wallet_invitations_invitee (invitee_user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Join requests made with a QR invitation. The Owner approves (-> CONTRIBUTOR) or rejects.
-- At most one PENDING request per (wallet, requester), as above.
CREATE TABLE shared_wallet_join_requests (
    request_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shared_wallet_id    BIGINT UNSIGNED NOT NULL,
    requester_user_id   BIGINT UNSIGNED NOT NULL,
    status              VARCHAR(12)     NOT NULL,
    created_at          DATETIME        NOT NULL,
    decided_at          DATETIME        NULL,
    decided_by_user_id  BIGINT UNSIGNED NULL,
    pending_requester_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN requester_user_id END) STORED,

    CONSTRAINT pk_shared_wallet_join_requests PRIMARY KEY (request_id),
    CONSTRAINT fk_shared_wallet_join_requests_wallet FOREIGN KEY (shared_wallet_id)
        REFERENCES shared_wallets (shared_wallet_id),
    CONSTRAINT fk_shared_wallet_join_requests_requester FOREIGN KEY (requester_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_shared_wallet_join_requests_decider FOREIGN KEY (decided_by_user_id) REFERENCES users (user_id),
    CONSTRAINT uq_shared_wallet_join_requests_one_pending UNIQUE (shared_wallet_id, pending_requester_id),
    CONSTRAINT chk_shared_wallet_join_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_shared_wallet_join_requests_decision CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND decided_by_user_id IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL AND decided_by_user_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
