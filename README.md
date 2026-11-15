# LAMMA

Simulated digital wallet — local JavaFX desktop application backed by MySQL (via JDBC).

**Current state: Alpha 1 / P03 (personal wallet dashboard).** You can register a personal account,
sign in, see your personal wallet balance (read from MySQL), and sign out. Business sign-up shows
"Coming Soon" only. Send Money, Receive Money and Recent Activity are shown on the dashboard as
non-clickable "Coming Soon" previews.

> This is a prototype for **simulated money**. The six-digit PIN, PBKDF2 hashing and sign-in lockout
> are reasonable for that purpose. They are **not** production-grade financial security.

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
   MySQL user `lamma_app`. The script's password `DEV_ONLY_change_me` is a **development
   placeholder**: it is public, so replace it in the script before running it unless this is a
   throwaway local database.

2. **Configure the connection.** Copy the example settings file. The copy is git-ignored, so your
   password is never committed.

   ```bash
   cp config/database.properties.example config/database.properties
   ```

   Set `db.password` to the password you used in step 1 (and change the URL or user if needed). The environment variables `LAMMA_DB_URL`,
   `LAMMA_DB_USER` and `LAMMA_DB_PASSWORD` override the file's values.

3. **Create the tables and check the connection** from the terminal:

   ```bash
   mvn -q compile exec:java
   ```

   Expected output: `OK: Connected to MySQL 8.0.x, database 'lamma', schema version 2 ...`
   If something is wrong, you get a `DATABASE ERROR: ...` line that explains the problem.

## Run the application

```bash
mvn javafx:run
```

On startup the app connects to MySQL and applies any pending schema migrations. If MySQL can't
be reached, it shows an explanation and a **Try again** button instead of crashing. Then it opens
the **Sign In** screen:

- **Sign In**: phone number and masked PIN. This one screen serves every account type.
- **Sign Up**: choose **Personal** (opens the registration form) or **Business** (shows "Coming
  Soon" and creates nothing).
- **Personal Sign Up**: first name, last name, phone number, PIN and Confirm PIN. When it
  succeeds, you return to Sign In with the phone number filled in.
- **Dashboard**: shows LAMMA branding, your name, **Personal Wallet** and your balance in USD
  (e.g. `$0.00`), plus a **Sign Out** button. The balance loads in the background. If it can't be
  loaded, a friendly message and a **Retry** button appear. The Send Money, Receive Money and
  Recent Activity tiles are read-only "Coming Soon" previews.

## Run the tests

```bash
mvn test
```

Two groups of tests are **skipped** unless their environment is available. Maven reports them as
`Skipped`, not passed:

- **29 MySQL integration tests** run only when `LAMMA_TEST_DB_*` is set (see below).
- **9 JavaFX navigation tests** (`DashboardNavigationTest`, `AsyncNavigationRaceTest`) need a
  graphical display. They always run on macOS and Windows; on Linux they need `DISPLAY` (a
  desktop session, or `xvfb-run`). No window is shown.

With neither available, expect `Tests run: 100, Failures: 0, Errors: 0, Skipped: 38`. To run the
MySQL tests, point them at the test database:

```bash
LAMMA_TEST_DB_URL='jdbc:mysql://localhost:3306/lamma_test' \
LAMMA_TEST_DB_USER=lamma_app LAMMA_TEST_DB_PASSWORD='<your password>' \
mvn test
```

Expect `Skipped: 9` if no display is available (the JavaFX tests), and with a display as well,
`Tests run: 100, Failures: 0, Errors: 0, Skipped: 0`. On a headless Linux machine:
`xvfb-run -a mvn test` (with the variables above).

These tests drop and recreate all tables in that database, so they refuse to run unless the
database name ends in `_test`.

## How the code is organised

```
JavaFX screens (com.lamma.ui)         StartupView -> SignInView <-> SignUpChoiceView -> PersonalSignUpView
   │  AppNavigator switches screens        └─> DashboardView (only with an active UserSession)
   │  BackgroundWork runs DB calls off the UI thread
   ▼
Services (com.lamma.auth)             RegistrationService, AuthenticationService, UserSession
   │  PhoneNumbers (normalization), Pins (format), PinHasher (PBKDF2)
   │      (com.lamma.wallet)          WalletService (reads the PERSONAL wallet), Money (USD format)
   ▼
JDBC (com.lamma.db)                   Database, DatabaseConfig, SchemaMigrator, DatabaseStatus
   ▼
MySQL                                 users, wallets, schema_version
```

- `src/main/java/com/lamma/LammaApp.java`: the JavaFX entry point.
- `src/main/java/com/lamma/ui/`: the screens, plus `src/main/resources/com/lamma/ui/lamma.css`.
- `src/main/java/com/lamma/auth/`: registration, sign-in, session, phone and PIN rules.
- `src/main/java/com/lamma/wallet/`: wallet data access for the dashboard.
- `src/main/java/com/lamma/db/`: the database code (config, connection, migrations, CLI check).
- `src/main/resources/db/migration/`: versioned schema scripts.
- `database/create_database.sql`: one-time admin setup (database and user).

## Database schema (version 2)

### `users` (USER)

| Column | Type | Notes |
|---|---|---|
| `user_id` | BIGINT UNSIGNED, PK, auto-increment | Unique user identifier |
| `first_name`, `last_name` | VARCHAR(50) NOT NULL | Must not be blank |
| `phone_number` | VARCHAR(15) NOT NULL, **UNIQUE** | Normalized format: digits only, 10–15 digits |
| `pin_hash` | VARCHAR(255) NOT NULL | Salted PBKDF2 hash. Must be at least 32 characters, so a raw 6-digit PIN cannot be stored |
| `failed_pin_attempts` | INT UNSIGNED NOT NULL DEFAULT 0 | Added in V002: consecutive wrong PINs |
| `locked_until` | DATETIME NULL | Added in V002: sign-in is refused until this time (UTC) |
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

### Migration history

| Version | Script | Change |
|---|---|---|
| 1 | `V001__create_users_and_wallets.sql` | `users`, `wallets` (P01) |
| 2 | `V002__add_sign_in_lockout_fields.sql` | `users.failed_pin_attempts`, `users.locked_until` (P02) |

## Phone number normalization

`PhoneNumbers.normalize` is used by **both** registration and sign-in. It stores numbers as
international digits only, with the country code first and no `+` (E.164 without the `+`), 10–15
digits. Spaces, dashes, dots and brackets are ignored.

| Typed | Stored | Rule |
|---|---|---|
| `03 123 456` | `9613123456` | Leading `0` means a Lebanese local number |
| `+961 3 123 456` / `00961 3 123 456` | `9613123456` | `+` or `00` means an international number |
| `9613123456` | `9613123456` | Already Lebanese international digits |
| `70 123 456` | `96170123456` | 8-digit Lebanese mobile (70/71/76/78/79/81), normally written without a 0 |
| `+1 514 555 0100` | `15145550100` | Other countries need `+` or `00` |

Lebanese numbers must have 7 or 8 digits after the `0`/`961`. Any other bare digits, such as
`3123456` or `15145550100`, are **rejected as ambiguous** instead of being guessed as Lebanese.

## Registration and sign-in

- **Registration** validates the form and reports every problem at once: blank names, a bad phone
  number, a PIN that isn't exactly 6 digits, or a Confirm PIN that doesn't match. It then inserts
  the user and their `PERSONAL` wallet (balance 0.00) in **one transaction**. If either insert
  fails, both are rolled back. Duplicate phone numbers are rejected by the database's unique
  constraint, and the user sees "An account with this phone number already exists."
- **PIN storage**: the PIN is hashed with PBKDF2-HMAC-SHA256 (built into the JDK), using a random
  16-byte salt per PIN and 600,000 iterations. It is stored as
  `pbkdf2-sha256$<iterations>$<salt>$<hash>`, and verification uses a constant-time comparison.
  The plaintext PIN is never stored or logged.
- **Sign-in** gives the same message for an unknown phone number, a wrong PIN and a locked account,
  and an unknown phone still costs one hash computation. This way neither the screen nor its
  timing reveals which numbers are registered. Registration does say when a number is taken,
  which is unavoidable if duplicates must be explained.
- **Lockout**: after 5 wrong PINs in a row, the account is locked for 5 minutes. While locked,
  even the correct PIN is refused. A successful sign-in resets the counter.
- **Session**: the signed-in user is held in memory only (`UserSession`), so closing the app ends
  it. **Sign Out** clears it and returns to Sign In.
- All SQL uses `PreparedStatement` parameters.

## Dashboard data and account isolation

- The dashboard runs one parameterized query:
  `SELECT wallet_id, owner_user_id, balance FROM wallets WHERE owner_user_id = ? AND wallet_type = 'PERSONAL'`.
  The `?` is the **signed-in user's id from the session**. No wallet id ever comes from the screen,
  so one user can't request another user's wallet.
- If the wallet is missing, the screen says so. If the data is inconsistent (more than one PERSONAL
  wallet, or a negative or missing balance), the screen says so too. If the database fails, the
  user sees a friendly message without SQL or connection details and can press **Retry**. The
  technical cause goes to the console only.
- **Account switching**: each sign-in builds a new dashboard screen. A slow wallet load that
  finishes after Sign Out, or after another user signs in, is discarded: results are applied only
  if the same sign-in is still active (`UserSession.isCurrent`) and the screen is still showing.
  Sign In and Sign Up follow the same rule: a slow result is ignored once the user has left that
  screen, so a late sign-in can never replace someone else's session.
- **Restart**: the session is in memory only, so a restarted app always starts at Sign In. Accounts
  and wallets are stored in MySQL and are still there.

## macOS

The project is pure Java. Maven downloads the right JavaFX build for macOS automatically, on both
Intel and Apple Silicon. **macOS has not yet been verified by the team**; please run this checklist
once on a Mac:

```bash
# 1. Tools (Homebrew)
brew install openjdk@21 maven mysql
brew services start mysql
java -version && mvn -v          # check that Maven uses JDK 17 or newer

# 2. Database (Homebrew's MySQL root user has no password by default)
mysql -u root < database/create_database.sql
cp config/database.properties.example config/database.properties   # then set db.password

# 3. Verify
mvn -q compile exec:java         # expect: OK: Connected to MySQL ... schema version 2
LAMMA_TEST_DB_URL='jdbc:mysql://localhost:3306/lamma_test' \
LAMMA_TEST_DB_USER=lamma_app LAMMA_TEST_DB_PASSWORD='<your password>' \
mvn test                         # expect: Tests run: 100, Failures: 0, Errors: 0, Skipped: 0

# 4. Run and try it
mvn javafx:run
```

Manual check in the app:

1. Sign Up → Business shows "Coming Soon".
2. Personal: register with `03 123 456`. When you're returned to Sign In, sign in with
   `+961 3 123 456`.
3. Check that the dashboard shows your name, Personal Wallet and `$0.00`, then Sign Out.
   Register a second account, sign in with it, and check that it shows its own name.
4. A wrong PIN 5 times → even the correct PIN is refused for 5 minutes.
5. `brew services stop mysql` → restart the app: it shows an error and a **Try again** button,
   and does not crash.
