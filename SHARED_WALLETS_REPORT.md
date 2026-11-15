# LAMMA Shared Wallets — Implementation Report

**Current phase:** P01, Shared Wallet Foundation. **Awaiting user review.** P02 (invitations,
roles and money) has not been started.
**Branch:** `alpha2-development` (continues from the UI redesign, `e53efc2`). `main` is unchanged.
**Verification platform:** Linux (Ubuntu 24.04), OpenJDK 21, Maven 3.9, MySQL 8.0.46, JavaFX 21
on a virtual display. **Nothing in this phase has been run on Windows or macOS.**

---

## P01 — Shared Wallet Foundation

### 1. Summary of changes

- **Create a shared wallet** (Wallets → Create Shared Wallet, or "New shared wallet" once you have
  one):
  - **Fields:** name (required, ≤60), purpose (optional, ≤80), description (optional, ≤500),
    target amount (optional, a positive USD amount with ≤2 decimals).
  - **Validation:** all problems are listed together.
  - **One flexible model:** there are no Gift, Trip or Household sub-types.
  - **Saving:** the Create button is disabled while saving, and the new wallet opens straight
    away.
- **Atomic creation** (`SharedWalletService.create`, one MySQL transaction):
  1. a `wallets` row (type SHARED, the name, balance 0.00, ACTIVE, no personal owner)
  2. a `shared_wallets` row (purpose, description, target)
  3. the creator's `shared_wallet_members` row (OWNER, joined at the current UTC time)

  It commits all three, or rolls all three back on any failure.
- **Wallets screen:** the placeholder sentence is replaced by the real list.
  - **Each tile:** icon, name, purpose (or description, or "Shared wallet · Owner"), the balance
    (never truncated) and "of $X target" when a target is set.
  - **States:** loading, error with Retry, and an empty state that explains shared wallets and
    offers **Create Shared Wallet**.
- **Shared wallet details** (opened from its tile; Back returns to Wallets):
  - name, balance and **"You: Owner"**
  - **Target**: progress bar and "$0.00 of $1,500.00 (0%)", computed from the real balance
  - **About**: purpose and description, when given
  - **Members**: each member with avatar, name ("(you)" for yourself) and role badge; the creator
    is listed as **Owner**
  - **States:** loading; error with Retry; "not available" with no Retry, since retrying couldn't
    help
  - **No action buttons.** Contributions, transfers, invitations and expenses don't exist yet,
    so the screen only has one plain sentence saying they'll be added later.
- **Roles** (`MemberRole`):
  - **OWNER:** administers, contributes and sends out.
  - **CONTRIBUTOR:** contributes only; the default for future members.
  - **SPENDER:** contributes and sends out.

  Only OWNER is ever assigned in P01, to the creator. The permission rules are encoded and
  unit-tested for later phases, but nothing uses them yet, since no money moves.
- **Access control:**
  - Every shared-wallet query filters by `user_id = ?` with the **session's** user id, through
    the membership table, plus `wallet_type = 'SHARED'` and `status = 'ACTIVE'`.
  - A wallet you're not a member of gives **the same message as a wallet that doesn't exist**,
    so wallet ids can't be probed.
  - A personal wallet's id is never accepted as a shared wallet.
  - All new screens use the existing "same sign-in, same screen" guard, so a slow load can't
    show another user's wallets after switching accounts (tested).
- **No changes** to personal wallets, cards, top-ups, Send Money, authentication or their screens,
  apart from the Wallets list itself.

### 2. CDM/LDM versus the implemented schema

> **The CDM/LDM document was not available to me.** It isn't in the repository or this session's
> files. I implemented from the WALLET, SHARED_WALLET and SHARED_WALLET_MEMBER definitions quoted
> in the task. Please compare with the full document.

| LDM | Implemented | Difference and reason |
|---|---|---|
| WALLET.`wallet_id` PK | `wallets.wallet_id` PK | none |
| WALLET.`personal_user_id` (nullable, unique, FK USER) | `wallets.owner_user_id` (now **nullable**, FK `users`) + computed `personal_owner_user_id` (**UNIQUE**, set only for PERSONAL) | **Name only.** The existing column names were kept, so no personal-wallet query or past migration changes. Behaviour matches: one personal wallet per user; **NULL for shared wallets**, enforced by `chk_wallets_owner_matches_type` |
| WALLET.`name` | `wallets.name` VARCHAR(60) NOT NULL, default 'Personal Wallet' | Added. The default backfills existing personal wallets and keeps registration unchanged |
| WALLET.`balance` | `wallets.balance` DECIMAL(15,2) ≥ 0 | none; shared wallets use the same column (no second balance system) |
| WALLET.`wallet_type` | `wallets.wallet_type` ∈ {PERSONAL, SHARED} | Widened from PERSONAL only. Business wallets are not added (out of scope) |
| WALLET.`status` | `wallets.status` ∈ {ACTIVE}, default ACTIVE | Added. Only ACTIVE until closure is implemented |
| *(not in LDM)* | `wallets.created_at` | Existing column, kept |
| SHARED_WALLET.`shared_wallet_id` PK + FK WALLET | `shared_wallets.shared_wallet_id` PK, FK → `wallets` | none (1:1 "is a" wallet) |
| SHARED_WALLET.`gift_recipient_id` FK USER | nullable FK → `users` | Created as in the LDM. **Not used by the app in P01**: there are no gift-specific fields, per "one flexible model" |
| SHARED_WALLET.`purpose`, `description` | VARCHAR(80), VARCHAR(500), both nullable | Lengths are my choice (the LDM has no sizes) |
| SHARED_WALLET.`target_amount` | DECIMAL(15,2) NULL, CHECK > 0 | none |
| SHARED_WALLET_MEMBER PK (`user_id`, `shared_wallet_id`) | same composite PK, FKs to `users` and `shared_wallets` | Plus an index on `shared_wallet_id` for the member list |
| SHARED_WALLET_MEMBER.`member_role` | VARCHAR(20) ∈ {OWNER, CONTRIBUTOR, SPENDER} | Values from the approved roles |
| SHARED_WALLET_MEMBER.`joined_at` | DATETIME (UTC, written by the app) | none |
| "authorized **active** members" | membership row exists **and** the wallet is ACTIVE | **There is no member status column** (the LDM has none). Removing a member later can delete the row, or a status can be added then. Please confirm |
| Shared wallet's type matches its `shared_wallets` row | enforced by the service; not by a database constraint | A database-level guarantee would need an extra composite key. Not added, to keep the change minimal |

**Table names:** `wallets`, `shared_wallets` and `shared_wallet_members` follow the existing plural
naming.

**Migration `V006__add_shared_wallets.sql`:** one `ALTER TABLE wallets` plus two new tables.
V001–V005 are unchanged.

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `e53efc2`, before any change (MySQL and display) | 262 | 262 | 0 | 0 |
| No MySQL settings, no display | 287 | 120 | 0 | 167 |
| MySQL settings, no display | 287 | 228 | 0 | 59 |
| **MySQL settings and a display (virtual display)** | **287** | **287** | **0** | **0** |

Skipped tests didn't run and are never counted as passed. There are 108 MySQL integration tests
and 59 JavaFX UI tests.

New tests (25):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `SharedWalletServiceIntegrationTest` | 10 | MySQL | **Creation:** all three rows are correct (SHARED, name, NULL owner, 0.00, ACTIVE; purpose, description, target; **OWNER** at the clock time); exactly one member. **Details:** the creator is Owner. **Listing:** only the member's own wallets, in order. **Non-member denied** with the same message as a missing wallet. A **personal wallet id is refused**. **Invalid input** creates nothing. **Rollback:** when the membership insert fails, no wallet or shared row is left. A **nonexistent creator** is rejected by the database and nothing is kept. **Persistence after restart.** **Personal wallets, top-ups and Send Money still work** next to shared wallets, and registration still makes exactly one personal wallet |
| `SharedWalletSchemaIntegrationTest` | 3 | MySQL | Shared wallets can't have a personal owner and personal wallets must have one; unknown type or status and blank names are rejected; target must be > 0; `shared_wallets` must reference an existing wallet, once; one membership per user per wallet; unknown role rejected; many shared wallets allowed but still one personal wallet |
| `SharedWalletValidationTest` | 4 | — | Form rules (required name, trimming, optional fields, every bad target); role permissions; outage messages |
| `SharedWalletFlowTest` | 8 | display | Screens need a session; empty state; **create** (validation errors → valid → details with Owner, purpose, description, target, member row, **no buttons on the details screen** → Back → tile in list → reopen); **double click creates once**; create failure then retry; **non-member sees "not available" and no data**; load error → Retry; **account switch never shows the previous user's wallets** |

**Existing tests changed:** `AppShellNavigationTest` asserted the old placeholder sentence; it now
checks the shared-wallet section (empty state and Create button). This is intended: the
placeholder was replaced. The test-database helpers also drop the two new tables.

**Upgrade check:** a database built by the actual previous commit (`e53efc2`), with users, a cash
top-up, a saved card, a card top-up and a transfer pair, upgraded to version 6.
- All existing wallets, transactions, cards and users were **byte-for-byte identical**; existing
  wallets gained only "Personal Wallet" and ACTIVE.
- Re-running changed nothing.
- On the upgraded data: a shared wallet was created with $0.00 and one OWNER; Rami was refused;
  Lina's personal balance was unchanged.

**Manual run on Linux** (real app, real `lamma` database, which upgraded on launch):
- Lina saw the empty state, triggered validation errors, then created "Beirut Trip" (purpose,
  description, $1,500 target) and "Flat Bills".
- Details showed $0.00, "You: Owner", target 0%, About and members ("Lina Haddad (you) · Owner").
- Checked in light/wide and dark/compact; screenshots attached to the handoff.
- Rami's Wallets showed the empty state with neither of Lina's wallets.
- Database: wallets 14 and 15 are SHARED, NULL owner, 0.00, ACTIVE, each with Lina as OWNER.
  Total money unchanged at $217.50.

### 4. Outstanding issues and decisions for review

1. **The CDM/LDM document was not available.** Please check §2 against it, especially column
   names and lengths.
2. **"Active member":** there's no member status column, so a membership row means access. Do
   you want a member status (e.g. ACTIVE/REMOVED) in the next phase, or will removal delete the
   row?
3. **`gift_recipient_id`** exists but isn't exposed in the app, following the "one flexible
   model" instruction. Should it ever appear in the form?
4. **Duplicate creation after an uncertain result:** if the database commits but the app loses
   the answer, retrying creates a second wallet; the button is disabled during a request. Unlike
   money operations, there's no request id here. A request id could be added later if needed.
5. **Wallet names aren't unique;** a user can create two "Trip" wallets.
6. **No closing, leaving or editing wallets yet** (out of scope).
7. **History:** shared wallets have no transactions yet. `TransactionRecords` reads counterparty
   names through `wallets.owner_user_id`, which is NULL for shared wallets. Later phases that move
   money in or out of shared wallets will need to show the wallet's name instead.

### 5. Windows manual-testing checklist

1. `git pull`, then `mvn -q compile exec:java` → `OK: … schema version 6 (1 migration(s) applied now)`.
2. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 287, Failures: 0, Errors: 0, Skipped: 0`.
3. `mvn javafx:run`, then sign in as user A → **Wallets**. You should see the personal wallet tile
   and, under Shared wallets, the explanation and **Create Shared Wallet**.
4. Create with an empty name and target `12.345` → both errors listed. Then create "Trip",
   purpose "Summer", target `500` → the details screen opens:
   - $0.00
   - "You: Owner"
   - "$0.00 of $500.00 (0%)"
   - you listed as Owner
   - no action buttons
5. Back → Wallets shows the "Trip" tile with "of $500.00 target", and "New shared wallet"
   appears. Create a second wallet with only a name.
6. Resize narrow and wide, and switch Light/Dark in Profile → the Wallets and details screens stay
   readable and amounts aren't cut off.
7. Sign out → sign in as user B → **Wallets** shows no shared wallets of user A.
8. Check that user A's personal balance, a cash top-up and Send Money still work as before.
9. Optional, in MySQL: `SELECT * FROM shared_wallet_members;` → one OWNER row per created wallet,
   for user A.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| Previous commit | `e53efc2` (UI redesign) |
| P01 commit | the commit that adds this report (hash given in the completion message) |
| `main` | unchanged at `b9962d9`; nothing merged |
