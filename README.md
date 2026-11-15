# LAMMA

Simulated digital wallet — local JavaFX desktop application backed by MySQL (via JDBC).

**Current state: Alpha 1 / P01 (foundation and database).** The app opens a window and checks the
database connection. There is no registration, sign-in or dashboard yet.

## Requirements

- JDK 17 or newer (tested with JDK 21)
- Maven 3.8+
- MySQL Server 8.0.16 or newer, running locally. Older versions ignore `CHECK` constraints.

JavaFX and the MySQL JDBC driver are downloaded by Maven automatically.

## First-time setup

1. **Create the database and the application account.** Run this once as a MySQL administrator:

   ```bash
   mysql -u root -p < database/create_database.sql
   ```

   This creates the `lamma` database, the `lamma_test` database used by the automated tests, and the
   MySQL user `lamma_app`. You can change the password in the script first if you like.

2. **Configure the connection.** Copy the example settings file. The copy is git-ignored, so your
   password is never committed.

   ```bash
   cp config/database.properties.example config/database.properties
   ```

   Edit `db.password` (and the URL or user, if needed). The environment variables `LAMMA_DB_URL`,
   `LAMMA_DB_USER` and `LAMMA_DB_PASSWORD` override the file's values.

3. **Create the tables and check the connection** from the terminal:

   ```bash
   mvn -q compile exec:java
   ```

   Expected output: `OK: Connected to MySQL 8.0.x, database 'lamma', schema version 1 ...`
   If something is wrong, you get a `DATABASE ERROR: ...` line that explains the problem.

## Run the application

```bash
mvn javafx:run
```

The window shows the database status. It reads green when the app is connected and red, with an
explanation, when the database is not reachable. Press **Check database connection** to try again,
for example after you start MySQL. The app also applies any pending schema migrations on startup.

## Run the tests

```bash
mvn test
```

The MySQL constraint tests are skipped unless a **test** database is configured. To run them:

```bash
LAMMA_TEST_DB_URL='jdbc:mysql://localhost:3306/lamma_test' \
LAMMA_TEST_DB_USER=lamma_app LAMMA_TEST_DB_PASSWORD=change-me \
mvn test
```

These tests drop and recreate all tables in that database, so they refuse to run unless the
database name ends in `_test`.

## How the code is organised

```
LammaApp (JavaFX window)
   └─ DatabaseStatus.checkAndMigrate()      runs on a background thread
        ├─ DatabaseConfig   reads config/database.properties and env vars
        ├─ Database         opens JDBC connections and turns errors into plain-English messages
        └─ SchemaMigrator   applies src/main/resources/db/migration/V*.sql in order
```

- `src/main/java/com/lamma/LammaApp.java`: the JavaFX entry point.
- `src/main/java/com/lamma/db/`: the database code (config, connection, migrations, CLI check).
- `src/main/resources/db/migration/`: versioned schema scripts.
- `database/create_database.sql`: one-time admin setup (database and user).

## Database schema (version 1)

### `users` (USER)

| Column | Type | Notes |
|---|---|---|
| `user_id` | BIGINT UNSIGNED, PK, auto-increment | Unique user identifier |
| `first_name`, `last_name` | VARCHAR(50) NOT NULL | Must not be blank |
| `phone_number` | VARCHAR(15) NOT NULL, **UNIQUE** | Normalized format: digits only, 10–15 digits |
| `pin_hash` | VARCHAR(255) NOT NULL | Salted PIN hash (P02). Must be at least 32 characters, so a raw 6-digit PIN cannot be stored |
| `created_at`, `updated_at` | DATETIME | Set automatically |

### `wallets` (WALLET)

| Column | Type | Notes |
|---|---|---|
| `wallet_id` | BIGINT UNSIGNED, PK, auto-increment | Unique wallet identifier |
| `wallet_type` | VARCHAR(20) NOT NULL | `CHECK IN ('PERSONAL')`. Later alphas will add types in a new migration |
| `owner_user_id` | BIGINT UNSIGNED NOT NULL, FK → `users.user_id` | Deleting a user who owns wallets is blocked |
| `balance` | DECIMAL(15,2) NOT NULL DEFAULT 0.00 | `CHECK (balance >= 0)`. Exact decimal, no floating point |
| `created_at` | DATETIME | Set automatically |
| `personal_owner_user_id` | generated column, **UNIQUE** | Equals `owner_user_id` for personal wallets and NULL otherwise. This enforces **at most one personal wallet per user** while leaving future wallet types unrestricted |

### `schema_version`

This table records which migration scripts have run (`version`, `script_name`, `applied_at`).

### Changing the schema later

Add a new file such as `V002__something.sql` to `src/main/resources/db/migration/` and append its
name to `SchemaMigrator.MIGRATIONS`. **Never edit a migration that has already been applied.**
MySQL commits `CREATE`/`ALTER TABLE` statements immediately, so test new scripts on `lamma_test`
first.

### Phone number normalization rule

The rule is decided now; the Java code that applies it comes in P02. A phone number is stored as
digits only, with the country code included and no `+`, spaces, dashes or brackets (E.164 without
the `+`). For example, `+1 (514) 555-0100` is stored as `15145550100`. The database rejects any
value that is not 10–15 digits.
