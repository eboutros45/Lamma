# LAMMA Alpha 1 — Implementation Report

**Phases covered:** P01–P04 of the approved Alpha 1 development plan
**Status:** P04 validation complete, **awaiting human review**. Alpha 1 is *not* declared complete
until the reviewer approves it, and the macOS verification below is still pending.
**Date:** 2026-11-15
**Verification platform:** Linux (Ubuntu 24.04), OpenJDK 21, Maven 3.9, MySQL 8.0.46, JavaFX 21
(on a virtual display). **Nothing has been run on macOS.**

---

## 1. Implementation summary

Alpha 1 is a local JavaFX desktop app for a **simulated** digital wallet, backed by MySQL through
JDBC. It delivers the following.

- **Startup**
  - The app connects to MySQL and brings the schema up to date from versioned migration scripts.
  - If MySQL is unreachable, it shows an explanation and a **Try again** button instead of
    crashing.
- **Sign In**
  - One common screen for all account types: phone number and a masked six-digit PIN.
  - Every failure shows the same message, so the screen never reveals which phone numbers have
    accounts.
- **Sign Up**
  - **Personal** opens the registration form.
  - **Business** shows "Coming Soon" and creates nothing (verified: database row counts are
    unchanged).
- **Personal registration**
  - Fields: first name, last name, phone, PIN, Confirm PIN.
  - All validation problems are reported at once.
  - The user and exactly one PERSONAL wallet with a balance of 0.00 are saved in **one
    transaction**: both rows or neither.
- **PIN security**
  - PINs are hashed with PBKDF2-HMAC-SHA256: 600,000 iterations and a random 16-byte salt per PIN,
    checked with a constant-time comparison.
  - A PIN is never stored or logged in plaintext.
- **Lockout**: 5 wrong PINs in a row lock the account for 5 minutes. A successful sign-in resets
  the counter.
- **Personal wallet dashboard**
  - Shows LAMMA branding, the user's name, a "Personal Wallet" heading and the **balance read from
    MySQL**, formatted as USD.
  - Send Money, Receive Money and Recent Activity appear as read-only "Coming Soon" previews.
  - If the wallet can't be loaded, a **Retry** button appears.
- **Session**
  - The signed-in user is held in memory only. Sign Out clears it, and closing the app ends it.
  - Late results from background database work can't attach to the wrong user (see §4).

**Not included (as planned):** business accounts, deposits, transfers, transaction history,
shared wallets, billing, SMS verification, PIN recovery, multi-computer use.

> The six-digit PIN, PBKDF2 hashing and lockout are suitable for a simulated-money prototype. They
> are **not** production-grade financial security.

## 2. Technical overview

### Components

```
JavaFX screens (com.lamma.ui)
  StartupView -> SignInView <-> SignUpChoiceView -> PersonalSignUpView
                     └-> DashboardView (only with an active session)
  AppNavigator   switches screens; holds the services and the UserSession
  BackgroundWork runs database work off the UI thread
        │
Services (com.lamma.auth, com.lamma.wallet)
  RegistrationService    validation + user/wallet transaction
  AuthenticationService  PIN check, lockout, generic failures
  UserSession            in-memory signed-in user
  PhoneNumbers, Pins, PinHasher, WalletService, Money
        │
JDBC (com.lamma.db)
  Database (connections + friendly errors), DatabaseConfig (git-ignored settings),
  SchemaMigrator (versioned scripts), DatabaseStatus, DatabaseSetup (command-line check)
        │
MySQL: users, wallets, schema_version
```

- **Dependencies:** JavaFX 21 (`javafx-controls`), MySQL Connector/J 8.4 and JUnit 5. No other
  frameworks.
- **Java version:** the code compiles for Java 17.

### Database (schema version 2)

| Table | Key columns | Constraints |
|---|---|---|
| `users` | `user_id` (primary key), `first_name`, `last_name`, `phone_number`, `pin_hash`, `failed_pin_attempts`, `locked_until`, timestamps | unique `phone_number`; phone must be 10–15 digits; names not blank; `pin_hash` at least 32 characters, so a raw PIN can't be stored |
| `wallets` | `wallet_id` (primary key), `wallet_type`, `owner_user_id` (references `users`), `balance` DECIMAL(15,2), `created_at`, `personal_owner_user_id` (computed) | `wallet_type` must be `PERSONAL`; balance can't be negative; **at most one PERSONAL wallet per user** (a unique constraint on the computed column) |
| `schema_version` | `version`, `script_name`, `applied_at` | records which migrations have run |

### Migrations

| Version | Script | Phase |
|---|---|---|
| 1 | `V001__create_users_and_wallets.sql` | P01 |
| 2 | `V002__add_sign_in_lockout_fields.sql` | P02 |

P03 and P04 needed no schema changes. Applied migration files have never been edited.

### Application flow

Launch → `StartupView` (connect and migrate) → `SignInView`. From there:

- **Sign Up** → `SignUpChoiceView` → `PersonalSignUpView` → back to `SignInView`, with the phone
  filled in and the cursor in the PIN field.
- **Sign In** → `AuthenticationService` → `UserSession` → `DashboardView`. The wallet loads in the
  background by the session's user id.
- **Sign Out** → session cleared → `SignInView`.

## 3. Verification results

### 3.1 Automated tests (final run, after the P04 fixes)

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| No MySQL settings, no display | 100 | 62 | 0 | 38 |
| MySQL settings (`LAMMA_TEST_DB_*`), no display | 100 | 91 | 0 | 9 |
| **MySQL settings and a display (virtual display)** | **100** | **100** | **0** | **0** |

- **Skipped tests did not run**, and none are counted as passed.
- The 29 MySQL integration tests run only when `LAMMA_TEST_DB_*` is set.
- The 9 JavaFX tests need a display.
- In the final configuration **all 100 tests executed and passed**.

Per class (final configuration):

| Test class | Tests | Needs | Covers |
|---|---|---|---|
| `SchemaConstraintsIntegrationTest` | 11 | MySQL | fresh schema, re-running migrations, all table constraints |
| `AuthenticationIntegrationTest` | 8 | MySQL | correct and wrong credentials, phone formats, generic messages, lockout and expiry, counter reset |
| `RegistrationIntegrationTest` | 4 | MySQL | user plus one $0 wallet, duplicate phone, rollback in two failure modes |
| `WalletServiceIntegrationTest` | 6 | MySQL | $0 and nonzero balances, two-user isolation, missing wallet, database error |
| `DashboardNavigationTest` | 7 | display | dashboard needs a session, Sign Out, account switching, stale-result race, error and Retry |
| `AsyncNavigationRaceTest` (new in P04) | 2 | display | a late sign-in or sign-up result is ignored after the user has navigated away |
| `DatabaseOutageMessagesTest` (new in P04) | 2 | — | outage messages contain no connection details |
| `PhoneNumbersTest` | 30 | — | normalization and ambiguity rules |
| `PinHasherTest` | 7 | — | salt, verification, malformed hashes, PIN format |
| `RegistrationValidationTest` | 5 | — | form validation |
| `UserSessionTest` | 3 | — | sign-out and staleness |
| `DatabaseConfigTest`, `DatabaseConnectionFailureTest`, `SchemaMigratorTest` | 13 | — | config, connection errors, migration list |
| `MoneyTest`, `WalletServiceConnectionFailureTest` | 2 | — | USD format, outage message |

**Regression baseline:** before any P04 change, 96 run / 96 passed / 0 skipped, which matches the
P03 report. All 96 still pass after P04.

### 3.2 Database and migration checks (manual, real MySQL)

| Check | Result |
|---|---|
| Fresh database: V001 and V002 apply | ✅ "2 migration(s) applied" |
| **Existing P01 database upgrade** (built by the real P01 commit `eb53b1d` from a separate checkout, then upgraded by current code) | ✅ V002 applied; the existing user and its $42.10 wallet were preserved; new columns took their defaults |
| Re-running migrations | ✅ nothing applied, on both the fresh and the upgraded database |
| Upgraded and fresh schemas identical (columns and constraints) | ✅ no differences |
| Constraints enforced after the upgrade: duplicate phone, second personal wallet, negative balance, raw PIN, negative attempt counter | ✅ all rejected by MySQL |

### 3.3 End-to-end journey (real app, real MySQL, Linux, virtual display, driven by a script)

The script drove the real app's controls and checked the database directly. It is not committed.

| # | Step | Result |
|---|---|---|
| 1–2 | Launch and connect to MySQL | ✅ startup check passed; Sign In shown, with the cursor in the phone field |
| — | Business Sign Up | ✅ "Coming Soon" notice; users/wallets **0/0 before and after** |
| 3–4 | Open Sign Up → Personal → register | ✅ "Account created" |
| 5 | Exactly one PERSONAL wallet, balance 0 | ✅ database shows `wallets=1 type=PERSONAL balance=0.00` |
| 6 | Back on the common Sign In screen | ✅ phone filled in, cursor in the PIN field |
| 7–8 | Sign in (with the **Enter** key) | ✅ "Lina Haddad", `$0.00` |
| 9 | Sign Out | ✅ "You have signed out."; no dashboard |
| 10 | Second account (balance set to 250.75 in MySQL) | ✅ "Rami Khoury", `$250.75`; no data from the first account |
| 11 | Restart the app | ✅ starts at Sign In (no session); records persisted (Lina, `$0.00`) |
| — | MySQL stopped while on Sign In | ✅ plain message without connection details; no crash; sign-in works after restarting MySQL |
| — | Wallet table unavailable while signed in (P03 check) | ✅ friendly error and Retry; recovered after Retry |
| — | Window shrink attempt (to 420×440) | ✅ stays at the designed 600×600 (P04 fix) |

### 3.4 Security and data-integrity review

| Area | Finding |
|---|---|
| PIN hashing and verification | ✅ PBKDF2-HMAC-SHA256, 600k iterations, per-PIN salt, constant-time comparison, iteration count stored with the hash |
| No plaintext PIN storage or logging | ✅ code sweep: no PIN in any print, log, exception or label; the database rejects short `pin_hash` values |
| Parameterized SQL | ✅ every statement carrying user data uses `PreparedStatement`; plain `Statement` is used only for fixed SQL in the bundled migration scripts |
| Registration atomicity | ✅ one transaction; rollback tested for a wallet-insert failure and for a database-constraint failure |
| Duplicate phone prevention | ✅ unique constraint in the database, plus one normalization rule for registration and sign-in |
| One personal wallet per user | ✅ unique constraint on the computed `personal_owner_user_id` column |
| Lockout | ✅ 5 failures → 5 minutes; even the correct PIN is refused while locked; same generic message; the row is locked during the check (`SELECT … FOR UPDATE`) |
| Session invalidation | ✅ Sign Out clears `UserSession`; the dashboard is unreachable without a session; memory only |
| Stale background results | ⚠️→✅ the **dashboard** was protected in P03; **Sign In and Sign Up were not** (defect D1, fixed) |
| Credentials out of version control | ✅ `config/database.properties` is git-ignored and **appears in no commit** (`git log --all` checked); the committed password is a labelled `DEV_ONLY_` placeholder |
| Error messages | ⚠️→✅ outage messages on Sign In and Sign Up showed connection details (defect D2, fixed). The startup screen still shows the database address by design (setup guidance) |

## 4. Issues discovered in P04

### Fixed

| ID | Severity | Defect | Fix | Proof |
|---|---|---|---|---|
| **D1** | **High (account isolation)** | A slow **Sign In** result was applied even after the user had left that screen. If user A's sign-in was slow, and A navigated away and back and user B signed in, A's late result **replaced B's session**: B then saw A's dashboard. A slow **registration** could similarly move a signed-in user back to Sign In. | `SignInView` and `PersonalSignUpView` apply a background result only if they are still the screen being shown (`navigator.isShowing(this)`), the same rule P03 used for the dashboard | `AsyncNavigationRaceTest`: both tests **failed before the fix** (session became user 1 instead of user 2; screen switched to Sign In) and pass after it |
| **D2** | Medium (information exposure) | With MySQL down, Sign In and Sign Up showed the raw setup message, including the JDBC URL and the `db.url` setting. A *connection* failure arrives as a different exception type and bypassed the friendly wrapping. | Both services wrap connection failures in their user-facing message (the pattern `WalletService` already used); the technical cause still goes to the console | `DatabaseOutageMessagesTest`: **failed before the fix**, passes after; end-to-end outage step re-checked |
| D3 | Low (usability) | After registering, Sign In opened with the phone filled in but the cursor in the phone field | The cursor goes to the PIN field when the phone is filled in | End-to-end step 6: focus is now `pinField` |
| D4 | Low (usability) | The window could be shrunk until the dashboard tiles truncated and sign-up errors could push **Create Account** out of view | The window can't shrink below its starting (designed) size | Shrink attempt keeps 600×600 |

All four fixes are small and use existing patterns. No migrations were changed.

### Known, not changed (judged acceptable for Alpha 1)

- **Startup error text** includes the database address (for example
  `jdbc:mysql://localhost:3306/lamma`) to help with local setup. It never contains the password.
- **Console logs** may include a phone number inside a technical SQL error, such as a duplicate
  entry. They never include a PIN. Console output is for developers only.
- **Brief pause at startup:** after connecting, the sign-in service computes one PBKDF2 hash on
  the UI thread (about 130 ms here), once.
- **Registration reveals that a phone number is registered** ("already exists"). This is
  unavoidable if duplicates must be explained. Sign-in reveals nothing.
- **Lockout is per account**, so anyone at the computer can deliberately lock an account for 5
  minutes. Acceptable for a single-computer prototype.
- **Phone rule:** numbers must be 10–15 digits (P01 constraint), so some valid short foreign
  numbers are rejected. Bare 8-digit numbers starting with 70/71/76/78/79/81 are read as Lebanese
  mobiles; this was introduced in P02 and is awaiting explicit approval.
- **Dashboard balance** loads once, when the dashboard opens; there is no refresh. Balances can't
  change through the app in Alpha 1.

## 5. Outstanding verification

| Item | Status |
|---|---|
| **macOS: build, tests, launch, full journey** | **PENDING.** Nothing has been run on macOS. The JavaFX tests start the JavaFX toolkit from the test runner and are expected to work on macOS, but this is unconfirmed. |
| Windows | Not tested and not in scope |
| Team can explain the JavaFX → Java → JDBC → MySQL flow | Unverified (needs the team) |
| Reviewer demonstration and approval of Alpha 1 | Pending |

### macOS verification checklist

Run from the repository root on the Mac, and record the actual results.

1. **Java and Maven:** `brew install openjdk@21 maven`. `java -version` must show 17 or newer, and
   `mvn -v` must show that Maven uses that JDK.
2. **MySQL:** `brew install mysql && brew services start mysql`, then `mysqladmin ping` should
   print "mysqld is alive". MySQL 8.0.16 or newer is required.
3. **Database:** replace the `DEV_ONLY_change_me` password in `database/create_database.sql`,
   then run `mysql -u root < database/create_database.sql`.
4. **Configuration:** `cp config/database.properties.example config/database.properties` and set
   `db.password` to the same password. Then `git status` must **not** list
   `config/database.properties`.
5. **Migrations:** `mvn -q compile exec:java` should print `OK: … schema version 2 (2 migration(s) applied now).`
   Running it again should print `… schema version 2.` with nothing applied.
6. **Automated tests:**

   ```bash
   LAMMA_TEST_DB_URL='jdbc:mysql://localhost:3306/lamma_test' \
   LAMMA_TEST_DB_USER=lamma_app LAMMA_TEST_DB_PASSWORD='<password>' mvn test
   ```

   Expect **`Tests run: 100, Failures: 0, Errors: 0, Skipped: 0`**. Any skips mean something did
   not run. The JavaFX tests should run on macOS without extra setup.
7. **Launch:** `mvn javafx:run` opens the window at Sign In.
8. **Journey:**
   1. Sign Up → Business shows "Coming Soon".
   2. Personal: register `Test One`, `03 123 456`, PIN `135790` → back at Sign In with the cursor
      in the PIN field.
   3. Sign in with `+961 3 123 456` → the dashboard shows "Test One" and `$0.00`.
   4. Sign Out.
   5. Register a second account (`70 123 456`), sign in, and check its own name and `$0.00`.
   6. Wrong PIN 5 times → even the correct PIN is refused for 5 minutes.
   7. Quit and relaunch → Sign In is required, and accounts still exist.
   8. `brew services stop mysql` → launch → error screen with **Try again**, no crash. Start MySQL
      again → **Try again** connects.

## 6. Repository status

| Item | Status (at the time of writing, before the P04 commit) |
|---|---|
| Branch | `claude/serene-goldberg-w79epj` |
| Commits | `eb53b1d` P01 · `6fb0512` P02 · `f8d058b` P03 · P04 is the commit that adds this report |
| Working tree before P04 | clean; matched `origin` exactly |
| P01–P04 committed and pushed | Yes, once the P04 commit is pushed (confirmed in the completion message) |
| Uncommitted changes or conflicts | None. **The remote has no other branch** |
| **Merge readiness** | The work is committed, pushed and passes all tests, but **there is no `main` branch on the remote**: this branch is the repository's only branch and its first commit is the root commit. A `main` branch needs to be created (for example from this branch) before anything can be merged into it. **No branch has been created, merged, renamed or deleted; this awaits your decision.** |

## 7. Alpha 1 acceptance criteria

Legend: ✅ **Verified** (executed, on Linux) · 🟡 **Partially verified** · ⬜ **Unverified**

### P01: Foundation and Database
| Criterion | Status |
|---|---|
| JavaFX application starts on the target development machine | 🟡 verified on Linux; **macOS pending** |
| MySQL connectivity verifiable; connection errors handled clearly | ✅ |
| Initial schema can be created reproducibly | ✅ |
| Unique phone and at most one personal wallet enforced by the database | ✅ |

### P02: Registration and Authentication
| Criterion | Status |
|---|---|
| New personal registration succeeds with valid input | ✅ |
| Each registration creates exactly one personal wallet with $0 balance | ✅ |
| Duplicate phone registration rejected | ✅ |
| Invalid PIN format or mismatched confirmation rejected | ✅ |
| Failed wallet creation rolls back user creation | ✅ |
| Correct credentials authenticate; incorrect ones do not | ✅ |
| Repeated failed attempts limited; PINs not stored in plaintext | ✅ |
| Business Sign Up visible but cannot create a business | ✅ (database counts unchanged) |
| Logout ends the authenticated session | ✅ |

### P03: Personal Wallet Dashboard
| Criterion | Status |
|---|---|
| Signed-in users see their own identity and $0 balance | ✅ |
| Users cannot view another user's wallet through ordinary navigation | ✅ (including the stale-result races: P03 dashboard, P04 sign-in and sign-up) |
| Logout removes access to the prior user's dashboard | ✅ |
| Two accounts remain distinct when switching | ✅ |
| Records survive closing and reopening the application | ✅ |

### P04: Alpha 1 Validation
| Criterion | Status |
|---|---|
| End-to-end registration → wallet → login → dashboard → logout works | ✅ (Linux) |
| All P01–P03 acceptance criteria pass | 🟡 all pass on Linux; macOS pending |
| App restart preserves user and wallet records | ✅ |
| Database outages and invalid inputs do not crash the application | ✅ (startup, sign-in, wallet load, form validation) |
| Team members can explain the JavaFX → Java → JDBC → MySQL flow | ⬜ needs the team (README and §2 provide the material) |
| User reviews the demonstration and approves moving to Alpha 2 | ⬜ pending your review |

## 8. Alpha 2 readiness notes

These are technical points to settle before transactions are implemented. This is not a plan.

1. **Balance updates need locking.** Transfers must update both wallets in one transaction, lock
   the rows (`SELECT … FOR UPDATE`) in a consistent order such as by `wallet_id` to avoid
   deadlocks, and re-check the balance inside the transaction. The `balance >= 0` constraint
   already prevents overdrafts at the database level.
2. **Ledger versus stored balance.** Alpha 1 stores `wallets.balance` directly. Alpha 2 should
   decide how a transaction table relates to it: a cached balance plus a ledger, or a balance
   derived from the ledger.
3. **New migrations:** use `V003` and later, and add them to `SchemaMigrator.MIGRATIONS`. MySQL
   commits `CREATE`/`ALTER TABLE` immediately, so keep each script small.
4. **Test helper must learn new tables.** `TestDatabase.freshSchema()` (and the P01
   `SchemaConstraintsIntegrationTest`) drop `wallets`, `users` and `schema_version` explicitly.
   New tables with foreign keys to `wallets` or `users` must be added there, or test setup will
   fail.
5. **Wallet types:** `chk_wallets_type` allows only `PERSONAL`. Shared and business wallets need
   a migration that widens it. The computed-column uniqueness rule already leaves other types
   unrestricted.
6. **Recipient lookup by phone** will reveal whether a number has an account, the same trade-off
   as registration. Decide how much to reveal.
7. **Dashboard refresh:** the balance loads once per sign-in. After a transaction, the dashboard
   must reload it, and the existing stale-result guards should be reused for that.
8. **Currency:** amounts are simulated USD with no currency column. Add one only if Alpha 2 needs
   more than one currency.
9. **Repository:** create a `main` branch and complete the macOS verification first, so Alpha 2
   starts from a confirmed cross-platform baseline.
