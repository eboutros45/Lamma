-- V002: P02 sign-in protection.
--
-- failed_pin_attempts: consecutive wrong PINs since the last successful sign-in
--                      (reset to 0 on success or when a lockout starts).
-- locked_until:        while this time (UTC) is in the future, sign-in for this
--                      account is refused without checking the PIN.
ALTER TABLE users
    ADD COLUMN failed_pin_attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER pin_hash,
    ADD COLUMN locked_until DATETIME NULL AFTER failed_pin_attempts;
