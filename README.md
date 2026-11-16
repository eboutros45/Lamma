# LAMMA

Simulated digital wallet — local JavaFX desktop application backed by MySQL (via JDBC).

**Current state: Alpha 2 / P03 (Send Money),** on branch
`alpha2-development`. Alpha 1 (on `main`) lets you register a personal account, sign in, see your
personal wallet balance and sign out. Alpha 2 adds:

- **Receive Money → Cash Top-Up** (P01)
- **Credit Card Top-Up**, with a saved or new card (P02)
- **Payment Methods** to save, rename and remove cards (P02)
- **Send Money** to another registered LAMMA user by phone number (P03)
- a **Recent Activity** list
- **Shared wallets** (Shared Wallet P01): create one, see it in Wallets, and open its details
  and members.
- **Shared wallet membership** (Shared Wallet P02): the Owner invites by phone number or shares a
  QR invitation, approves join requests, switches members between Contributor and Spender and
  removes them; members can leave.
- **Shared wallet money** (Shared Wallet P03): every member can **Contribute** from their Personal
  Wallet; the Owner and Spenders can also **Withdraw** to their own Personal Wallet and **Send
  Money** from the shared wallet to any registered user. Every member sees the shared wallet's
  history. Shared expenses come in a later phase.
- **Account settings and contacts** (P04A): edit your name, change your phone number or PIN (both
  need your current PIN), and import a private contact book from CSV or vCard files. Contacts
  are suggested under every recipient phone field. The Owner changes a member's role with a
  dropdown.
- **Gift wallets** (P04B): when creating a shared wallet, switch on **This is a gift wallet** and
  choose a registered LAMMA user as the recipient. Members see "Gift for …" on the wallet. It is
  information only: the recipient doesn't become a member and nothing is paid out.
- **Shared expenses** (P04C): any member adds an expense to a shared wallet, split equally or with
  custom amounts, and collected either into the shared wallet or by reimbursing the member who
  paid the bill. Each participant gets a payment request (under Wallets → Payment requests and on
  the expense) and pays or declines it; nobody is charged automatically. The creator can revise
  declined shares or cancel the expense.
- **Gift delivery** (P05): the Owner of a gift wallet uses **Send Gift** to send its whole balance
  to the recipient's Personal Wallet (after a review and confirmation). The wallet then becomes a
  **completed, read-only gift**, listed under Wallets → Completed gifts with its full history.

All money is simulated. Business accounts are still "Coming Soon".

The interface was redesigned before P04. It has light and dark themes plus a System option, and
uses a sidebar on wide windows and a bottom bar on narrow ones. See **Look and navigation**
below.

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

   Expected output: `OK: Connected to MySQL 8.0.x, database 'lamma', schema version 11 ...`

   > Once a database has been upgraded to version 3 or later, the Alpha 1 app on `main` refuses to start
   > against it ("schema is newer than this application"). If you switch between `main` and
   > `alpha2-development`, give each branch its own database (for example `lamma_alpha1`), set
   > through `db.url`.
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
  (e.g. `$0.00`), plus a **Sign Out** button. Below that are **Receive Money**, **Payment
  Methods** and **Send Money** buttons, and **Recent Activity**: your 10 newest
  transactions with type, amount, date and time, and status. Everything loads in the background.
  If it can't be loaded, a friendly message and a **Retry** button appear.
- **Receive Money**: **Cash Top-Up** or **Credit Card Top-Up**.
- **Cash Top-Up**: enter an amount → review it → **Confirm Top-Up** → success with your new
  balance → **Back to Dashboard**. Amounts are USD from $0.01 to $10,000.00 per top-up, with at
  most 2 decimal places. All money is simulated.
- **Credit Card Top-Up**:
  - With no saved cards, the new-card form opens directly (cardholder name, card number,
    expiration MM/YY, CVV).
  - With saved cards, you **choose a card**, or pick **Use a New Card**. Each saved card shows its
    nickname, brand and masked number, expiry and cardholder.
  - Then amount → review (shows the card and "simulated payment") → **Confirm Top-Up** → success.
  - After paying with a new card, you're asked "Would you like to save this card for future
    top-ups?" → **Save Card** (asks for a nickname) or **Not Now**.
- **Payment Methods**: your saved cards, shown with nickname, cardholder and
  `Visa •••• 4242 · Expires 08/29`. You can **Rename** (nickname only), **Remove** (with
  confirmation; past top-ups stay in your activity) and **Add Card** (nickname plus card details).
  There is no default card; you choose one each time.
- **Send Money**:
  1. Enter the recipient's registered phone number (any accepted format) → **Find Recipient**.
     Their name and number appear. Editing the number clears the recipient, so you must look
     them up again.
  2. **Continue** → amount (your available balance is shown) → **Continue**.
  3. Review shows the amount, recipient name and number, your balance and "simulated funds
     between LAMMA wallets". Choose **Confirm and Send**, **Back** or **Cancel**.
  4. Success shows the amount sent, the recipient and your new balance.

  Errors are shown for unknown numbers, invalid numbers, sending to yourself, invalid amounts
  and insufficient funds.

## Look and navigation

- **Signed in, the app has four destinations:** **Home** (balance, Send Money / Add Money, recent
  activity, "View all" history), **Wallets** (your personal wallet → details and full history,
  then your **shared wallets** with **Create Shared Wallet**, invitations waiting for you and
  **Join with an invitation code**), **Cards** (saved cards) and
  **Profile** (account details, theme, Sign Out).
- **Navigation follows the window width:**
  - **860 px or wider:** sidebar
  - **below 820 px:** bottom bar
  - **in between:** the layout keeps its current mode, so it doesn't flicker while you resize

  The smallest window is 380×600.
- **Actions** (Send Money, Add Money, cash and card top-ups, Add Card, wallet details) open with a
  **Back** button that returns to where you started.
- **Profile → Account:** edit your first and last name; change your phone number (the one you
  sign in with) or your PIN. Both need your current PIN, and wrong PINs count towards the same
  5-try lockout as sign-in. PINs are only stored as salted PBKDF2 hashes and are never shown.
  **Forgotten PIN:** there is no self-service recovery. LAMMA has no verified email address or
  SMS service with which to prove who you are, and a recovery without one would let anyone who
  knows a phone number take over the account.
- **Profile → Contacts:** import a CSV file (name and phone columns; Google and Outlook exports
  work) or a vCard (.vcf) file. Numbers already saved or repeated in the file are skipped, and
  invalid entries are listed with the reason. Contacts are private to you and are not LAMMA
  accounts. Typing part of a name or number in a recipient field (Send Money, shared wallet Send
  Money, Invite Member) suggests matching contacts; choosing one only fills in the number, which
  is then looked up as usual.
- **Theme:** Profile → Appearance → **System** (default), **Light** or **Dark**. The change applies
  immediately and is saved on this computer with Java's `Preferences` (the registry on Windows, a
  plist on macOS, `~/.java` on Linux).
  - "System" reads the OS setting when LAMMA starts or when you choose System; it doesn't follow
    changes made while LAMMA is open.
  - JavaFX only reports the system colour scheme from version 22 (which needs JDK 21), so
    LAMMA asks the OS directly.
- **Styling:** `lamma.css` holds all layout and components. `theme-light.css` and
  `theme-dark.css` contain only colour tokens. Icons are vector paths from Google's Material Icons
  (Apache-2.0) in `Icons.java`. There are no extra UI libraries: AtlantaFX and Ikonli were
  evaluated (both compatible) but not needed.

## Run the tests

```bash
mvn test
```

Two groups of tests are **skipped** unless their environment is available. Maven reports them as
`Skipped`, not passed:

- **183 MySQL integration tests** run only when `LAMMA_TEST_DB_*` is set (see below).
- **71 JavaFX UI tests** (`DashboardNavigationTest`, `AsyncNavigationRaceTest`, `CashTopUpFlowTest`,
  `CardFlowsTest`, `SendMoneyFlowTest`, `AppShellNavigationTest`, `SharedWalletFlowTest`,
  `SharedWalletMembershipFlowTest`, `SharedWalletFundsFlowTest`, `AccountAndContactsFlowTest`,
  `GiftWalletFlowTest`, `ExpenseFlowTest`, `GiftDeliveryFlowTest`) need a
  graphical display. They always run on macOS and Windows; on Linux they need `DISPLAY` (a
  desktop session, or `xvfb-run`). No window is shown. `SharedWalletMembershipFlowTest` (3 tests),
  `SharedWalletFundsFlowTest` (2), `AccountAndContactsFlowTest` (2), `GiftWalletFlowTest` (2),
  `ExpenseFlowTest` (2) and `GiftDeliveryFlowTest` (1) need MySQL as well and are counted here.

With neither available, expect `Tests run: 390, Failures: 0, Errors: 0, Skipped: 254`. To run the
MySQL tests, point them at the test database:

```bash
LAMMA_TEST_DB_URL='jdbc:mysql://localhost:3306/lamma_test' \
LAMMA_TEST_DB_USER=lamma_app LAMMA_TEST_DB_PASSWORD='<your password>' \
mvn test
```

Expect `Skipped: 71` if no display is available (the JavaFX tests), and with a display as well,
`Tests run: 390, Failures: 0, Errors: 0, Skipped: 0`. On a headless Linux machine:
`xvfb-run -a mvn test` (with the variables above).

These tests drop and recreate all tables in that database, so they refuse to run unless the
database name ends in `_test`.

## How the code is organised

```
JavaFX screens (com.lamma.ui)         StartupView -> SignInView <-> SignUpChoiceView -> PersonalSignUpView
   │  AppNavigator switches screens        └─> DashboardView -> ReceiveView -> CashTopUpView / CardTopUpView
   │                                                         -> PaymentMethodsView -> AddCardView
   │                                                         -> SendMoneyView
   │                                           (only with an active UserSession; CardForm is shared)
   │  BackgroundWork runs DB calls off the UI thread
   ▼
Services (com.lamma.auth)             RegistrationService, AuthenticationService, UserSession
   │  PhoneNumbers (normalization), Pins (format), PinHasher (PBKDF2)
   │      (com.lamma.wallet)          WalletService (wallet + recent activity), TopUpService, TransferService,
   │                                  Amounts (amount rules), Money (USD format)
   │      (com.lamma.cards)           CardValidator (number/expiry/CVV checks -> CardMetadata),
   │                                  PaymentCardService (saved cards)
   ▼
JDBC (com.lamma.db)                   Database, DatabaseConfig, SchemaMigrator, DatabaseStatus
   ▼
MySQL                                 users, wallets, schema_version
```

- `src/main/java/com/lamma/LammaApp.java`: the JavaFX entry point.
- `src/main/java/com/lamma/ui/`: the screens, plus `src/main/resources/com/lamma/ui/lamma.css`.
- `src/main/java/com/lamma/auth/`: registration, sign-in, session, phone and PIN rules.
- `src/main/java/com/lamma/wallet/`: wallet balance, transactions, cash and card top-ups, transfers.
- `src/main/java/com/lamma/cards/`: card validation and saved cards (metadata only).
- `src/main/java/com/lamma/db/`: the database code (config, connection, migrations, CLI check).
- `src/main/resources/db/migration/`: versioned schema scripts.
- `database/create_database.sql`: one-time admin setup (database and user).

## Database schema (version 11)

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

### `wallet_transactions` (Alpha 2)

One row per change to one wallet's balance. A cash top-up is one CREDIT row; a future transfer
will be one DEBIT row plus one CREDIT row.

| Column | Type | Notes |
|---|---|---|
| `transaction_id` | BIGINT UNSIGNED, PK, auto-increment | Also gives the order, newest first |
| `wallet_id` | BIGINT UNSIGNED NOT NULL, FK → `wallets.wallet_id` | A wallet with transactions can't be deleted |
| `transaction_type` | VARCHAR(30) | `CASH_TOP_UP`, `CARD_TOP_UP`, `TRANSFER_OUT`, `TRANSFER_IN` |
| `direction` | VARCHAR(6) | `CREDIT` or `DEBIT`. Top-ups and `TRANSFER_IN` are `CREDIT`; `TRANSFER_OUT` is `DEBIT` |
| `amount` | DECIMAL(15,2) | `CHECK (amount > 0)`. Always positive; the direction gives the sign |
| `balance_after` | DECIMAL(15,2) | `CHECK (>= 0)`. The wallet balance right after this transaction |
| `status` | VARCHAR(20) | `CHECK IN ('COMPLETED')`. Later phases may add e.g. `PENDING` |
| `card_brand`, `card_last4` | VARCHAR(20), CHAR(4), NULL | V004: a copy of the card used. **Required** for `CARD_TOP_UP`, **must be NULL** otherwise. Kept even if the card is removed |
| `transfer_id` | VARCHAR(36), NULL | V005: shared by the sent and received rows of one transfer. **UNIQUE** (`transfer_id`, `transaction_type`) allows exactly one row per side |
| `counterparty_wallet_id` | FK → `wallets`, NULL | V005: the other wallet in a transfer (never the row's own wallet). Names are read through it, not copied: the owner's name for a personal wallet, the wallet's name for a shared one |
| `transfer_kind` | VARCHAR(20), NULL | V008: `CONTRIBUTION`, `WITHDRAWAL` or `SHARED_SEND` for shared-wallet transfers; NULL for personal Send Money and top-ups |
| `initiated_by_user_id` | FK → `users`, NULL | V008: the member who started a shared-wallet transfer. Required exactly when `transfer_kind` is set |
| `request_id` | VARCHAR(36), **UNIQUE** | One id per confirmation, so a repeated request can't credit twice |
| `created_at` | DATETIME | Stored in **UTC**, shown in the computer's time zone |

### Shared wallets (V006, LDM WALLET / SHARED_WALLET / SHARED_WALLET_MEMBER)

A shared wallet **is a wallet**: a `wallets` row with `wallet_type = 'SHARED'`, its own
`balance`, and (later) its own `wallet_transactions`.

- **`wallets`** gained `name` (personal wallets default to "Personal Wallet") and `status`
  (`ACTIVE` only for now). `owner_user_id` is the LDM's `personal_user_id`: required for PERSONAL
  wallets and **NULL for SHARED** ones.
- **`shared_wallets`**: `shared_wallet_id` (primary key and foreign key to `wallets`),
  `gift_recipient_id` (optional, references `users`; set when a gift wallet is created, P04B), `purpose` (≤80), `description`
  (≤500), `target_amount` (optional, > 0).
- **`shared_wallet_members`**: primary key (`user_id`, `shared_wallet_id`), `member_role`
  (`OWNER` / `CONTRIBUTOR` / `SPENDER`) and `joined_at` (UTC). **Having a membership row is what
  grants access.** Every shared-wallet query filters by the session's user id.
- **Creation** inserts the wallet, its `shared_wallets` row and the creator's `OWNER` membership
  in **one transaction**.

### Invitations and join requests (V007)

The LDM has no entities for invitations, so V007 adds the smallest tables that make them safe:

- **`shared_wallets.invite_token`**: the current QR/link code (NULL when QR invitations are off),
  **UNIQUE**. It is a random 24-character code (18 bytes from `SecureRandom`), never the wallet id.
- **`shared_wallet_invitations`**: a direct invitation by phone number: wallet, invitee, inviter,
  `status` (`PENDING` / `ACCEPTED` / `DECLINED` / `CANCELLED`), `created_at`, `responded_at`.
- **`shared_wallet_join_requests`**: a request created by opening a QR invitation: wallet,
  requester, `status` (`PENDING` / `APPROVED` / `REJECTED`), `created_at`, and `decided_at` /
  `decided_by_user_id` (a CHECK requires both once decided, neither while pending).
- **One pending invitation / request per person per wallet** is enforced by the database: a
  stored generated column holds the user id only while the row is `PENDING`, and it has a
  UNIQUE index together with the wallet id. Answered rows are kept as history.

Rules (all enforced in `SharedWalletMembershipService`, never only in the UI):

- Only the Owner invites, shares the QR code, approves or rejects requests, changes roles and
  removes members. New members always join as **Contributor**; the Owner can switch a member
  between Contributor and Spender. The Owner can't leave, be removed or change their own role.
- An invitation or a QR code **never grants access by itself**: the invitee accepts, or the Owner
  approves the request. Only the invitee can answer an invitation.
- Every change locks the wallet's `shared_wallets` row (`SELECT … FOR UPDATE`) and re-checks the
  current state in the same transaction, so two approvals, or an accept and a cancel at the same
  moment, can't both succeed or create a duplicate membership.
- Removing a member or leaving deletes the membership row only, so access ends immediately;
  users, invitations, requests and transactions are kept. A removed member can be invited again.
- Someone who isn't a member gets the same answer as for a wallet that doesn't exist.

**QR payload:** `lamma://join/<code>`. The QR shows this link; a future camera scanner can read
it as is. Without a scanner, the link or the bare 24-character code can be pasted under
**Wallets → Join with an invitation code**. **New code** replaces the code (the old one stops
working), and **Turn off** removes it. QR images are drawn with ZXing `core` 3.5.3 (Apache-2.0,
no other dependencies), always black on white.

### Shared wallet money (V008)

Shared-wallet money movements use the **same transfer rows as personal Send Money**. Each
operation is one logical transfer stored as two rows in one database transaction: a
`TRANSFER_OUT` (DEBIT) row on the wallet the money leaves and a `TRANSFER_IN` (CREDIT) row on the
wallet it enters. Both carry the same `transfer_id`, point at each other through
`counterparty_wallet_id`, and carry the same `transfer_kind` and `initiated_by_user_id`.

| Operation | Who | Money moves | Shared wallet history shows |
|---|---|---|---|
| Contribute | any member | member's Personal Wallet → shared wallet | Contribution, + amount, from the member |
| Withdraw | Owner, Spender | shared wallet → that member's Personal Wallet | Withdrawal, − amount, to the member |
| Send Money | Owner, Spender | shared wallet → any registered user's Personal Wallet | Money Sent, − amount, to the recipient, by the member |

- Rules are enforced in `SharedWalletFundsService`, not only by hiding buttons. A Contributor,
  a removed member, someone with only a pending invitation and outsiders are refused even when
  the service is called directly.
- **Locking:** both wallets are locked with `SELECT … FOR UPDATE` in ascending `wallet_id` order,
  the same order as personal Send Money. Membership changes lock the shared wallet's row too, so
  a removal or role change happens entirely before or after a transfer. The member's role and
  both balances are re-read after the locks.
- **Idempotency:** each review screen creates one request id, stored as the `request_id` of the
  outgoing row (UNIQUE). Repeating it (double click, retry after a lost answer) returns the
  earlier result and moves nothing; reusing it for different details is refused.
- Amounts follow the existing rules: $0.01 to $10,000.00 per operation, cents only, and no
  balance may go below zero or above the column's maximum.
- Each wallet's history shows one row per operation (its own side). History rows for shared
  transfers show a short reference: the first 8 characters of the transfer id.

### `contacts` (V009)

Each user's own contact book: `contact_id`, `owner_user_id` (FK → `users`), `display_name`
(≤100), `phone_number` (normalized like `users.phone_number`), `created_at`. UNIQUE
(`owner_user_id`, `phone_number`) means one entry per number per user, so importing a file twice
adds nothing. Every query filters by the session's user id.

### Shared expenses (V010, LDM EXPENSE / PAYMENT_REQUEST)

- **`expenses`**: wallet, creator, title (≤80), total (> 0), notes (≤500), `split_method`
  (`EQUAL` / `CUSTOM`), `collection_method` (`SHARED_WALLET` / `REIMBURSEMENT`), `paid_by_user_id`
  (required exactly for reimbursements), `status` (`OPEN` / `CANCELLED`), `expense_date`,
  `created_at`, `cancelled_at`.
- **`expense_shares`** (not in the LDM): each participant's current share, adding up to the total.
  `covered_by_payer` marks the reimbursed payer's own part.
- **`payment_requests`**: one per participant's share: `requester_id`, `payer_id`,
  `destination_wallet_id` (the shared wallet, or the payer's Personal Wallet for a reimbursement),
  `amount`, `status` (`PENDING` / `PAID` / `DECLINED` / `CANCELLED`), `transaction_id` (the transfer
  that paid it, required exactly when PAID), `replaces_request_id` (a revised request), timestamps.
  At most one PENDING-or-PAID request per person per expense (UNIQUE on a generated column).
- Paying a request is one ordinary transfer (`transfer_kind` `EXPENSE_PAYMENT` or
  `REIMBURSEMENT`) made with the same steps as shared-wallet money. The request becomes PAID in
  the same transaction, and the transfer's `request_id` is `expense-request-<id>` (UNIQUE), so a
  request can never be paid twice. A bill paid outside LAMMA is never recorded as a transaction.
- Equal splits are in cents; any remaining cents go one each to the participants with the
  lowest user ids, so shares always add up exactly. Custom shares must add up to the total.
- Money transactions (top-ups, Send Money, shared-wallet money, expense payments) run at READ
  COMMITTED so that transfers on unrelated wallets can't deadlock on the request-id index. Every
  check is still made on rows locked with `FOR UPDATE` / `FOR SHARE`.

### Gift delivery (V011)

- **`wallets.status`** (LDM WALLET) now allows `COMPLETED`, and **`wallets.completed_at`** records
  when. A CHECK allows `COMPLETED` only for a shared wallet with a **zero balance**, so no later
  transfer can put money into it. Completion applies to the whole wallet, for every member; it is
  not a per-member "archive".
- Delivery is one ordinary transfer (`transfer_kind` `GIFT_DELIVERY`, shared wallet → recipient's
  Personal Wallet). It is made in the same transaction that completes the wallet and closes
  pending invitations and join requests. Its `request_id` is `gift-delivery-<wallet id>` (UNIQUE),
  so a gift can't be delivered twice. Shown as **Ref GIFT-<wallet id>**.
- Delivery is refused while the balance is $0.00, while an expense still has money outstanding
  (settle or cancel it first), or if the balance changed since the Owner reviewed it. The target
  amount is information only.

### `payment_cards` (Alpha 2 P02)

Saved cards hold **metadata only**. There is no column for the card number or CVV.

| Column | Type | Notes |
|---|---|---|
| `card_id` | BIGINT UNSIGNED, PK | |
| `user_id` | FK → `users.user_id` | Every query also filters by the session's user id |
| `nickname` | VARCHAR(40) | Required, not blank. Rename changes only this |
| `cardholder_name` | VARCHAR(60) | Not blank |
| `brand` | VARCHAR(20) | `VISA`, `MASTERCARD`, `AMEX`, `DISCOVER` or `OTHER` |
| `last4` | CHAR(4) | Exactly 4 digits |
| `exp_month`, `exp_year` | | 1–12 and 2000–2099 |
| | **UNIQUE** (`user_id`, `brand`, `last4`, `exp_month`, `exp_year`) | A user can't save the same card twice |

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
| 3 | `V003__create_wallet_transactions.sql` | `wallet_transactions` (Alpha 2 P01) |
| 4 | `V004__add_payment_cards_and_card_top_ups.sql` | `payment_cards`; `CARD_TOP_UP` with card snapshot columns (Alpha 2 P02) |
| 5 | `V005__add_wallet_transfers.sql` | `TRANSFER_OUT` / `TRANSFER_IN`, `transfer_id`, `counterparty_wallet_id` (Alpha 2 P03) |
| 6 | `V006__add_shared_wallets.sql` | `wallets.name`, `wallets.status`, type `SHARED`, `shared_wallets`, `shared_wallet_members` (Shared Wallet P01) |
| 7 | `V007__add_shared_wallet_invitations.sql` | `shared_wallets.invite_token`, `shared_wallet_invitations`, `shared_wallet_join_requests` (Shared Wallet P02) |
| 8 | `V008__add_shared_wallet_transfers.sql` | `wallet_transactions.transfer_kind`, `wallet_transactions.initiated_by_user_id` (Shared Wallet P03) |
| 9 | `V009__add_contacts.sql` | `contacts` (P04A) |
| 10 | `V010__add_shared_expenses.sql` | `expenses`, `expense_shares`, `payment_requests`; transfer kinds `EXPENSE_PAYMENT`, `REIMBURSEMENT` (P04C) |
| 11 | `V011__add_gift_delivery.sql` | `wallets.status` `COMPLETED`, `wallets.completed_at`; transfer kind `GIFT_DELIVERY` (P05) |

When adding a table that references `users`, `wallets`, `wallet_transactions`, `payment_cards`, `shared_wallets`, `shared_wallet_members`, `shared_wallet_invitations` or
`shared_wallet_join_requests`, `contacts`, `expenses`, `expense_shares` or `payment_requests`, also add it to
the `DROP TABLE` lists in `src/test/java/com/lamma/testsupport/TestDatabase.java` and
`SchemaConstraintsIntegrationTest`, child tables first.

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

## Cash top-up: data integrity

`TopUpService.cashTopUp` does everything in **one MySQL transaction**:

1. `SELECT … FROM wallets WHERE owner_user_id = ? AND wallet_type = 'PERSONAL' FOR UPDATE` locks
   the signed-in user's wallet row. The user id comes from the session; the screen never supplies
   a wallet id. Concurrent top-ups to the same wallet wait for each other, so no update is lost.
2. If this `request_id` was already processed, the earlier result is returned and nothing is
   credited again.
3. `UPDATE wallets SET balance = balance + ?`, then `INSERT INTO wallet_transactions …`.
4. Commit both, or roll both back on any error.

Repeated credits are blocked at three levels:

- The Confirm button is disabled while the top-up runs.
- After success the Confirm button is gone.
- The request id, created once per review screen, is reused if you retry after an error and
  replaced if you edit the amount. So even a retry after a timeout credits at most once.

## Send Money: transfer integrity

`TransferService.send` is **one MySQL transaction**:

1. Both wallets are locked with `SELECT … FOR UPDATE`, **always in ascending `wallet_id` order**,
   so opposite transfers (A→B and B→A) can't deadlock.
2. If this request id was already processed, the earlier result is returned and nothing moves
   twice. This also covers a retry after the app lost the database's answer.
3. The recipient's wallet and the sender's **current** balance are checked again. The balance
   shown on the review screen is only guidance.
4. The sender is debited, the recipient credited, and both rows inserted (`TRANSFER_OUT` +
   `TRANSFER_IN`, same `transfer_id`).
5. All four changes are committed together, or all rolled back.

- The sender is always the signed-in user (from the session). The recipient comes from a phone
  lookup that returns only their user id, name and phone.
- The database's `balance >= 0` rule stays as a last line of defence against overdrafts.
- No fees, no daily limit; $0.01–$10,000.00 per transfer.

## Card data handling

LAMMA processes **no real payments**. There is no gateway, authorisation or bank connection.

- The full card number and CVV are checked only inside `CardValidator.validate`:
  - 12–19 digits
  - Luhn checksum
  - brand-specific length
  - expiry not in the past (and not more than 20 years ahead)
  - CVV of 3 digits (4 for American Express)

  It returns `CardMetadata`: cardholder name, brand, **last four digits** and expiry. Nothing after
  that point, whether services, MySQL, logs or later screens, ever receives the full number or CVV.
- After a card is accepted, the form's number and CVV fields are cleared.
- A saved card is a convenience label for a simulated payment, not a reusable payment credential.
- Removing a card deletes its row. Past transactions keep their own copy of brand and last four
  digits.
- Card top-ups use the same rules as cash: one locked database transaction, the same request-id
  idempotency, $0.01–$10,000.00, and the card must belong to the signed-in user and not be
  expired.
- Saving a card after a top-up is a separate step. If it fails, the completed top-up is neither
  undone nor repeated.

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
mvn -q compile exec:java         # expect: OK: Connected to MySQL ... schema version 11
LAMMA_TEST_DB_URL='jdbc:mysql://localhost:3306/lamma_test' \
LAMMA_TEST_DB_USER=lamma_app LAMMA_TEST_DB_PASSWORD='<your password>' \
mvn test                         # expect: Tests run: 390, Failures: 0, Errors: 0, Skipped: 0

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
6. Receive Money → Cash Top-Up → `25.50` → Confirm. Check that the success screen and dashboard
   show `$25.50`, and that Recent Activity shows "Cash Top-Up +$25.50 Completed" with your local
   time. Sign in as the second account and check that its balance and activity are unchanged.
7. Receive Money → Credit Card Top-Up. With no saved cards, the form opens. Enter
   `4242 4242 4242 4242`, a future date, CVV `123` → amount `10` → Confirm → **Save Card**
   (nickname `Personal Visa`). Then check:
   - the dashboard shows "Card Top-Up · Visa •••• 4242"
   - Payment Methods lists the card, and Rename and Remove work
   - the next card top-up offers the saved card
8. Send Money from the first account to the second (e.g. `70 123 456`).
   - The recipient's name appears before you continue.
   - The review shows name, number, amount and your balance.
   - After **Confirm and Send**, both dashboards show the new balances.
   - Recent Activity shows "Money Sent · To …" for the sender and "Money Received · From …" for
     the recipient.
9. Shared wallets, with both accounts: the first creates a shared wallet and, on its details
   screen, uses **Invite Member** with the second account's number. The second account sees the
   invitation under Wallets, accepts it and appears as a Contributor. The first account then uses
   **QR Invitation**, copies the link, and the second pastes it under **Join with an invitation
   code** for another wallet; the first approves the request.
10. Shared wallet money, with both accounts in that wallet: the first account (Owner) uses
    **Contribute** for `20` and checks both balances and the activity row. The second account
    (Contributor) sees only **Contribute**. After the Owner makes it a Spender, it can
    **Withdraw** `5` and **Send Money** `1` to the first account's number; **View all** shows
    the four operations, and both personal histories show the matching rows.
