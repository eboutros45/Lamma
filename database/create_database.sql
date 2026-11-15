-- One-time setup, run by a MySQL administrator (e.g. root):
--
--     mysql -u root -p < database/create_database.sql
--
-- Creates the LAMMA database, a test database used by the automated
-- tests, and a dedicated application account.
--
-- DEVELOPMENT PLACEHOLDER PASSWORD: 'DEV_ONLY_change_me' below is public (it
-- is in this repository) and only acceptable for a local development
-- database. Replace it with your own password before running this script,
-- and put the same value in config/database.properties (git-ignored).
--
-- Tables are NOT created here. They are created by the versioned
-- migration scripts in src/main/resources/db/migration, which the
-- application (or `mvn exec:java`) applies automatically.

CREATE DATABASE IF NOT EXISTS lamma
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS lamma_test
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'lamma_app'@'localhost' IDENTIFIED BY 'DEV_ONLY_change_me';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON lamma.* TO 'lamma_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON lamma_test.* TO 'lamma_app'@'localhost';
