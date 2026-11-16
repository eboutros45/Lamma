# LAMMA Shared Wallets — Implementation Report

**Current phase:** P05, in two checkpoints: P05-1 (gift delivery) and P05-2 (application polish).
Each has its own section and commit below.
**Branch:** `alpha2-development` (P01 `909efdd`, P02 `bafb4fe`, P03 `84ca5d1`, P04A `ff8a9b3`,
P04B `0145fd5`, P04C `efe884e`). `main` is unchanged.
**Verification platform:** Linux (Ubuntu 24.04), OpenJDK 21, Maven 3.9, MySQL 8.0.46, JavaFX 21
on a virtual display. **Automated and GUI verification is Linux-only.** On macOS, the user
reported (after P04C) that the app builds, connects at the current schema and navigates on an
Apple Silicon Mac with MySQL 8.4, once stale build output was removed with `mvn clean`. Nothing
has been run on Windows.

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

---

## P02 — Shared Wallet Membership & Permissions

### 1. Summary of changes

**Direct invitations by phone number**
- On a shared wallet's details screen, the Owner uses **Invite Member** and enters a registered
  user's phone number (same formats as Send Money). The invitation is **PENDING** and grants no
  access.
- The invitee sees it under **Wallets → Invitations (n)**: "Lina Haddad invited you to “Beirut
  Trip”" with **Accept** and **Decline**. Accept makes them a **Contributor** and opens the wallet.
- The Owner sees **Pending invitations** on the details screen and can **Cancel** each one.
  Cancelled or declined invitations can't be accepted.
- Refused, with a clear message: unknown number, the Owner's own number, an existing member, a
  second pending invitation to the same person, someone who already has a pending join request,
  and anyone other than the invitee answering.

**QR invitations and join requests**
- **QR Invitation** (Owner only) shows a QR code for `lamma://join/<code>`, where the code is 24
  random URL-safe characters (18 bytes from `SecureRandom`). It contains no wallet id. The link
  is also shown as text with **Copy**.
- **New code** replaces the code (the old one stops working); **Turn off** removes it. Both ask
  for confirmation. Invitations are off until the Owner turns them on.
- Anyone signed in can use **Wallets → Join with an invitation code**, paste the link or the
  bare code, and **Check**. The preview shows the wallet name and the Owner's name; **Request to
  join** creates a **PENDING join request** and never grants access.
- The Owner sees **Join requests** on the details screen and **Approves** (→ Contributor) or
  **Rejects**. An approved or rejected request can't be handled again.
- Invalid, turned-off or replaced codes create nothing and say "This invitation code isn't valid
  or has been turned off." Members, people with a pending request, and people with a pending
  invitation (who are told to accept it instead) can't request again.
- Camera scanning is not included. The QR image is rendered with ZXing `core` (see §2), and the
  automated test decodes the image actually shown on screen, so a future scanner can read it.

**Roles**
- New members always join as Contributor. On each member row the Owner has **Make Spender** /
  **Make Contributor**, with confirmation. Changes are stored and shown to everyone; each member
  sees their own role in the header badge ("You: Spender").
- Only CONTRIBUTOR ↔ SPENDER changes are possible. The Owner's role can't be changed and no one
  can be made Owner (no ownership transfer). No new roles, no limits.

**Member management**
- Owner: members list, Invite Member, QR Invitation, Join requests, Pending invitations, role
  changes and **Remove** (with confirmation).
- Other members: members list (read only) and **Leave wallet** (with confirmation), which returns
  them to Wallets. No Owner controls are created for them at all.
- Removal and leaving delete only the membership row, so access ends at once. Users,
  invitations, requests and all transactions are kept, and the person can be invited again. The
  Owner can't leave or be removed.

**Authorization and integrity**
- Every rule is in `SharedWalletMembershipService`; the screens only call it. The user id
  always comes from the session.
- Each change runs in **one transaction** that first locks the wallet's `shared_wallets` row
  (`SELECT … FOR UPDATE`) and then re-reads the current state (role, invitation or request
  status). Changes to one wallet are therefore serialized: of two simultaneous approvals one
  succeeds and the other is told "already handled"; an accept and a cancel can't both succeed.
- Answering a pending invitation or request uses `UPDATE … WHERE status = 'PENDING'` and checks
  the row count, so state transitions are only PENDING → final.
- The database enforces one pending invitation and one pending request per person per wallet and
  one membership per person per wallet, as a second line of defence.
- Non-members, and people asking about a wallet id that doesn't exist, get the same "not
  available" message. Invitation and request ids that aren't theirs give a neutral message.
- Every screen reloads from the database after each action, and shows loading, empty, success
  and error states.

**Not included (as requested):** contributions, withdrawals, expenses, payment requests,
reimbursements, closing wallets, business wallets, general QR payments, camera scanning.

### 2. Schema (V007) and the CDM/LDM

The attached CDM/LDM has the entities WALLET, SHARED_WALLET and SHARED_WALLET_MEMBER, which
already match P01:
- SHARED_WALLET and SHARED_WALLET_MEMBER match exactly (`member_role` OWNER / CONTRIBUTOR /
  SPENDER, `joined_at`).
- WALLET matches, except that its `personal_user_id` is our `owner_user_id` (same meaning; NULL
  for shared wallets).

The model has **no entity for invitations or join requests**, so V007 adds the minimum:

| Change | Columns | Why |
|---|---|---|
| `shared_wallets.invite_token` | VARCHAR(64) NULL, **UNIQUE** | The current QR code. One per wallet; NULL = off; replacing it invalidates the old one |
| `shared_wallet_invitations` | `invitation_id`, `shared_wallet_id` → shared_wallets, `invitee_user_id` / `invited_by_user_id` → users, `status` (PENDING / ACCEPTED / DECLINED / CANCELLED), `created_at`, `responded_at` | Direct invitations, kept as history |
| `shared_wallet_join_requests` | `request_id`, `shared_wallet_id`, `requester_user_id`, `status` (PENDING / APPROVED / REJECTED), `created_at`, `decided_at`, `decided_by_user_id` | QR join requests, kept as history |

Constraints:
- **One pending per person per wallet:** a stored generated column (`pending_invitee_id` /
  `pending_requester_id`) equals the user id only while `status = 'PENDING'`. A UNIQUE index on
  (wallet, that column) allows any number of answered rows but only one pending row.
- CHECKs on the status values; an invitation can't be sent to oneself; a pending request has no
  decision, and a decided one has both `decided_at` and `decided_by_user_id`.
- Indexes for "my invitations" (invitee, status) and the foreign keys.

Notes for the model:
- The LDM's **PAYMENT_REQUEST** has `qr_token` / `qr_type`. These are for money requests and were
  deliberately **not reused** for membership; mixing them would make every join code a payment
  request.
- The LDM's **TRANSACTION** (one row with `sender_wallet_id`, `receiver_wallet_id`,
  `reference_code`) still differs from LAMMA's `wallet_transactions` (one row per wallet side,
  linked by `transfer_id`), as since Alpha 2 P01. P02 moves no money, so this is unchanged. It
  will matter in P03, when shared wallets get transactions.
- If you add these entities to the CDM/LDM: SHARED_WALLET 1–N INVITATION, USER 1–N INVITATION
  (invitee and inviter), SHARED_WALLET 1–N JOIN_REQUEST, USER 1–N JOIN_REQUEST (requester and
  decider).

**Dependency added:** `com.google.zxing:core:3.5.3`, which draws the QR image (Apache-2.0, Java 8
bytecode, no further dependencies, works on Windows, macOS and Linux). Nothing else was added.

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `909efdd` (P01), MySQL and display | 287 | 287 | 0 | 0 |
| No MySQL settings, no display | 312 | 124 | 0 | 188 |
| MySQL settings, no display | 312 | 250 | 0 | 62 |
| **MySQL settings and a display (virtual display)** | **312** | **312** | **0** | **0** |

Skipped tests didn't run and are never counted as passed. There are 126 MySQL integration tests
and 62 JavaFX UI tests; `SharedWalletMembershipFlowTest` needs both and is counted with the UI
tests.

New tests (25):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `SharedWalletMembershipIntegrationTest` | 15 | MySQL | **Invitation lifecycle** (pending grants nothing → accept → Contributor, invitation ACCEPTED); **declined and cancelled** can't be accepted; **rules**: unknown number, Owner, member, duplicate pending, pending request; **only the Owner** invites/cancels, **only the invitee** answers; **QR lifecycle** (off by default, code format, preview, request is pending only, approve → Contributor, can't approve twice); **reject**; **new code / turn off** invalidate the old code and create nothing; QR rules (member, duplicate, invited user, garbage input); **12 threads approving the same request → exactly 1 membership**, the rest "already handled"; **accept vs cancel at the same moment, 10 rounds → never both**; **roles persist after a restart** (new service instance); role rules (Owner fixed, no OWNER grant, non-members, non-owners); **remove and leave** end access immediately, history kept, **re-invite works**; Owner can't leave or be removed; **balances and transactions unchanged**; **rollback** when inserting the member fails (invitation stays PENDING, no member) |
| `MembershipSchemaIntegrationTest` | 3 | MySQL | Database refuses a second pending invitation/request for the same person (but allows answered history), unknown statuses, self-invitations, inconsistent decisions, duplicate codes |
| `SharedWalletMembershipFlowTest` | 3 | MySQL + display | Real services, **both sides with two users**: Lina invites Rami → Rami accepts → sees no Owner controls → Lina promotes him → Rami sees "Spender" → Rami leaves → Lina sees one member. Lina turns on QR → **the QR image on screen is decoded** → Rami pastes it, previews, requests → Lina approves → removes him → Rami can't open the wallet. Decline, and a turned-off code |
| `InvitationCodesTest` | 3 | — | Codes are random and URL-safe; link and bare code both parse, case-insensitive prefix; malformed input refused |
| `QrCodesTest` | 1 | — | The QR matrix decodes back to the exact link (no display needed) |

**Existing tests changed:** `SharedWalletFlowTest` and `AppShellNavigationTest` now give the
screens a stub membership service (empty lists), and the details-screen check expects the
Owner's two buttons (Invite Member, QR Invitation) instead of "no buttons". The test-database
helpers drop the two new tables.

**Mutation checks** (to show the concurrency tests can fail): with the `FOR UPDATE` locks removed,
the 12-thread approval test fails (database errors instead of "already handled"), and the
accept-versus-cancel test fails (both succeed). The locks were restored and both pass.

**Upgrade check:** a database built by the previous commit (`909efdd`, schema 6) with users,
personal and shared wallets, members and transactions, upgraded to version 7. All 13 compared
rows (4 wallets, 2 shared wallets, 2 members, 3 transactions, 2 users) were **identical** after
the upgrade. (A first attempt at this check compared only 6 rows because of a faulty snapshot
query; it was discarded and redone.)

**Manual run on Linux** (real app, real `lamma` database, which upgraded to 7 on launch), with
two users:
- Lina invited Rami to "Beirut Trip" → Rami saw "Invitations (1)", accepted, and saw the wallet
  as a Contributor with only **Leave wallet** → Lina made him a Spender → Rami saw "You: Spender".
- Lina turned on the QR invitation for "Flat Bills" → Rami pasted the link, saw the preview and
  requested → Lina approved → then removed him → Rami no longer saw "Flat Bills".
- Database afterwards: Beirut Trip has Lina (OWNER) and Rami (SPENDER); Flat Bills has only Lina;
  the join request is APPROVED and decided by Lina. Total money unchanged at $217.50.
- Screenshots in light and dark, wide and compact, were checked. They found three layout faults,
  all fixed: Accept/Decline and the confirm buttons were cut to "…" on narrow windows (they now
  keep their width, and invitation buttons sit under the text), and the QR link field was too
  narrow to read the code.

### 4. Outstanding issues and decisions for review

1. **Confirmations are inline**, not pop-up dialogs: the row's buttons are replaced by the
   question with Confirm / Cancel, as for removing a saved card. Tell us if you'd prefer dialogs.
2. **No member status column:** removing or leaving deletes the membership row (allowed by the
   brief). Invitations, requests and transactions keep the history. If you want to show "former
   members", a status column would be needed.
3. **No notification badges:** pending requests and invitations appear when the Wallets or
   details screen is opened or refreshed, not as live alerts.
4. **Theme is per computer, not per user** (unchanged from the redesign). In a two-user test on
   one computer, the second user's choice also applies to the first.
5. **Lost response on an invitation:** if the database commits but the app loses the answer,
   a retry is refused as a duplicate ("already has a pending invitation") rather than creating a
   second one, thanks to the unique constraint.
6. **A harmless JavaFX CSS warning** may appear in the console when the whole signed-in screen is
   replaced while switching accounts (`ClassCastException … from rule '*.icon'`). It is logged
   for a screen that has already been discarded, which no longer has the theme colours. Nothing
   visible is affected; it appears 3 times in the GUI test run. Not fixed in this phase to avoid
   changing the theme mechanism.
7. **Camera scanning** is deferred as requested; the payload format (`lamma://join/<code>`) is
   ready for it.
8. **Wallet transactions in history** (P01 item 7) still need the wallet name for shared wallets;
   this belongs to P03.

### 5. Windows manual-testing checklist

Verified on Linux only. Please run this on Windows with two accounts (A and B):

1. `git pull`, then `mvn -q compile exec:java` → `OK: … schema version 7 (1 migration(s) applied now)`.
2. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 312, Failures: 0, Errors: 0, Skipped: 0`.
3. `mvn javafx:run` → sign in as A → Wallets → open (or create) a shared wallet. You should see
   **Invite Member** and **QR Invitation**.
4. Invite Member with B's number → "Invitation sent to …". Try A's own number and an unknown
   number → refused. Invite B again → "already has a pending invitation". The invitation appears
   under **Pending invitations**.
5. Sign out → sign in as B → Wallets shows **Invitations (1)** → **Accept** → the wallet opens:
   "You: Contributor", no Invite/QR/Remove buttons, **Leave wallet** visible.
6. Sign in as A → the member row for B → **Make Spender** → confirm → "B is now a Spender". Sign in
   as B → "You: Spender".
7. As A, open a second shared wallet → **QR Invitation** → **Turn on**. Check that the QR is
   readable (black on white, also in Dark) and **Copy** puts the `lamma://join/…` link on the
   clipboard.
8. As B → Wallets → **Join with an invitation code** → paste → **Check** shows the wallet and A's
   name → **Request to join** → "Request sent". B can't open the wallet yet.
9. As A → **Join requests** → **Approve** → B is listed as Contributor. Then **Remove** B → confirm.
   As B, the wallet is gone from Wallets.
10. As A → QR Invitation → **New code** → as B, the old link is refused; **Turn off** → the new
    link is refused too.
11. As B, in a wallet B is still in → **Leave wallet** → confirm → back on Wallets without it.
12. Resize narrow and wide, and switch Light/Dark → Invitations, the details screen and the QR
    screen stay readable; no button text is cut to "…".
13. Personal balances, top-ups and Send Money behave as before.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| Previous commit | `909efdd` (Shared Wallet P01) |
| P02 commit | `bafb4fe` |
| `main` | unchanged; nothing merged |

---

## P03 — Shared Wallet Financial Operations

### 1. Summary of changes

**What members can do** (the shared wallet's details screen):

| Action | Owner | Contributor | Spender |
|---|---|---|---|
| See balance, target progress, recent activity and the full history | yes | yes | yes |
| **Contribute** from their own Personal Wallet | yes | yes | yes |
| **Withdraw** to their own Personal Wallet | yes | — | yes |
| **Send Money** from the shared wallet to any registered user | yes | — | yes |

- Buttons a member may not use are not created. The service re-checks the role in the database
  for every operation, so a Contributor is refused even when calling the service directly or
  opening the screen another way.
- **Every action follows the Send Money pattern:**
  1. Amount, with the available balance shown.
  2. Review, with **From** and **To** (wallet names and balances, or the recipient's name and phone number).
  3. **Confirm**.
  4. Success, with the new shared balance and, if it changed, your personal balance.
  5. **Done**, which returns to the wallet.
- **Send Money** first looks up the recipient by phone number and shows their name. The recipient
  can be another member, someone outside the wallet, or the sender themself ("(you)").
- **History:**
  - The details screen shows the latest 5 transactions; **View all** opens the shared wallet's
    full history (up to 100, as for personal wallets).
  - Each row shows type, signed amount (+ in, − out), the other side, the date and time, the
    status and a short reference (the first 8 characters of the transfer id).
  - Example rows: "Contribution · From Lina Haddad", "Withdrawal · To Rami Khoury",
    "Money Sent · To Sami Aoun · by Rami Khoury".
- **Personal histories** show the other side: "Contribution · To Beirut Trip", "Withdrawal · From
  Beirut Trip", "Money Received · From Beirut Trip · sent by Rami Khoury". Top-ups and personal
  Send Money rows are unchanged.
- Target progress uses the new shared balance.
- Loading, empty, error (with Retry, except for "not available") and success states are on all
  new screens. Light and dark themes and the narrow layout are supported. On narrow windows,
  Contribute gets its own line and Withdraw / Send Money share the next.

**Not included (as requested):** shared expenses and splitting, contribution requests,
reimbursements, recurring contributions, spending limits, closing wallets, business wallets,
bank transfers, QR payments, camera scanning.

### 2. How shared-wallet transfers use the existing transaction model

LAMMA has stored one row per wallet side since Alpha 2 P01. A personal transfer is a
`TRANSFER_OUT` (DEBIT) row plus a `TRANSFER_IN` (CREDIT) row, linked by `transfer_id`, each
pointing at the other wallet. This represents shared-wallet operations without change, so it was
**kept**:

| Operation | DEBIT row (TRANSFER_OUT) on | CREDIT row (TRANSFER_IN) on |
|---|---|---|
| Contribution | member's Personal Wallet | shared wallet |
| Withdrawal | shared wallet | member's Personal Wallet |
| Send Money | shared wallet | recipient's Personal Wallet |

**V008** adds two nullable columns to `wallet_transactions`:
- `transfer_kind`: `CONTRIBUTION` / `WITHDRAWAL` / `SHARED_SEND`; NULL for personal Send Money and
  top-ups.
- `initiated_by_user_id` (FK to `users`): which member did it. This is needed because the shared
  wallet itself belongs to no one.

CHECKs require the two together and only on transfer rows. Existing rows are not changed
(NULL = personal Send Money, as before). No existing constraint was relaxed: amount > 0,
balance_after ≥ 0, direction per side, one row per side, and the counterparty never being the
row's own wallet all still apply.

Compared with the LDM's TRANSACTION (one row with sender and receiver wallets): one LDM
transaction corresponds to our pair of rows with the same `transfer_id`. `sender_wallet_id` /
`receiver_wallet_id` are the DEBIT row's `wallet_id` / `counterparty_wallet_id`, and
`reference_code` corresponds to `transfer_id`. The LDM's `category_id` is still not used; it fits
shared expenses (P04).

**Integrity (`SharedWalletFundsService`)** — each operation is one database transaction that:
1. **Locks both wallets** with `SELECT … FOR UPDATE` in ascending `wallet_id` order, the same
   order as personal Send Money, so transfers in opposite directions can't deadlock.
2. Checks both wallets: the shared one is SHARED and ACTIVE, the personal one PERSONAL and ACTIVE.
3. **Reads the member's role with a locking read.** Membership changes (P02) lock the same
   shared-wallet row, so a removal or demotion is entirely before or after the transfer. A plain
   read could use an older snapshot; the mutation check below shows the test catches that.
4. **Idempotency:** the request id (one per review screen) is stored UNIQUE as the outgoing row's
   `request_id`.
   - Repeating it returns the earlier result and moves nothing.
   - The same id with a different amount, wallet, kind or member is refused.
   - Different operations of the same amount each get their own id and all go through.
5. Re-checks the balance with the wallets locked (no negative balances) and the receiving
   wallet's maximum.
6. Updates both balances and inserts both rows, then commits; any failure rolls back everything.

The acting user always comes from the session, and the personal wallet is looked up by that
user. Non-members, pending invitees and removed members get the same "not available" message as
for a wallet that doesn't exist.

**Files**
- New: `V008__add_shared_wallet_transfers.sql`, `SharedWalletFundsService`, `SharedTransferView`
  (Contribute / Withdraw / Send Money), `SharedWalletHistoryView`.
- Changed: `WalletTransaction` (+ `transferKind`, `initiatedByName`), `TransactionRecords` (reads
  and writes the new columns; names a shared wallet by its name), `TransactionLabels`,
  `SharedWalletDetailView` (money buttons, recent activity), `AppNavigator`, `SchemaMigrator`.
- `TransferService`: the recipient lookups were made package-visible so they can be reused; its
  behaviour is unchanged.

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `bafb4fe` (P02), MySQL and display | 312 | 312 | 0 | 0 |
| No MySQL settings, no display | 332 | 124 | 0 | 208 |
| MySQL settings, no display | 332 | 268 | 0 | 64 |
| **MySQL settings and a display (virtual display)** | **332** | **332** | **0** | **0** |

Skipped tests didn't run and are never counted as passed. There are 144 MySQL integration tests
and 64 JavaFX UI tests; the two shared-wallet UI flow tests need both and are counted with the
UI tests.

New tests (20):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `SharedWalletFundsIntegrationTest` | 14 | MySQL | **Contributions by every role**, both rows linked, both histories correct. **Invalid amounts** (0, negative, 3 decimals, over $10,000, missing) and **insufficient funds** move nothing. **Owner and Spender withdraw**; **Owner and Spender send** to an outsider, another member and themself; unknown and missing recipients refused. **Contributor refused** for withdraw, send and recipient lookup, directly through the service; demotion and promotion take effect at once. **Outsider, pending invitee and removed member** refused for everything, including the history; a personal wallet id passed as a shared one is refused. **Idempotency:** a repeat returns the earlier result for all three operations; the same id with other details or by another member is refused; equal amounts with new ids go through. **8 identical requests at once → one transfer.** **12 concurrent withdrawals/sends of $30 from $100 → exactly 3**, the rest "Insufficient funds", nothing negative. **24 mixed transfers in both directions at once** (contributions, withdrawals, personal Send Money, shared sends) → all succeed, no deadlock. **A role change holding the wallet lock is waited for**, and the withdrawal is then refused. **Removal racing a withdrawal (8 rounds)** → always consistent. **Rollback** when the second row fails, for all three operations; the failed request id can be retried once. **Persistence** after a restart (new service), target progress, and personal Send Money unchanged. **Total money constant** throughout |
| `SharedTransferSchemaIntegrationTest` | 4 | MySQL | The database accepts the three kinds with an initiator; it refuses a kind without an initiator, an initiator without a kind, unknown kinds, a kind on a top-up and unknown users; the V005 transfer rules still apply |
| `SharedWalletFundsFlowTest` | 2 | MySQL + display | Real screens and services, **both sides**: Lina contributes (a **double click contributes once**), sees the balance, target and activity. Rami as **Contributor** sees only Contribute and contributes; a Withdraw screen opened directly refuses him; his personal history shows "Contribution · To Beirut Trip". **Promoted to Spender**, he withdraws (insufficient amount refused first) and sends to Sami (unknown number refused first). Lina's **full history**: 4 rows in order with names, signs and a reference. Sami's history shows "From Beirut Trip · sent by Rami Khoury". Outsider refused on the history and Contribute screens; invalid amounts caught; **a balance change between review and confirm is refused by the database** and nothing moves |

**Existing tests changed:** `SharedWalletFlowTest` gives the screen a stub money service, and its
details-screen check now expects the money buttons and "View all"; `SharedWalletMembershipFlowTest`
also uses the real money service.

**A bug found by the tests and fixed:** the first version also took a shared lock on the
`shared_wallets` row after locking the wallets. Membership changes lock those rows in the
opposite order, so "remove a member" racing "withdraw" deadlocked (MySQL picked a victim). The
`removalAndWithdrawalAtTheSameMomentStayConsistent` test caught it. That row is now read without
a lock (it is never deleted), and the wallet row is the single point where they meet. The test
then passed three runs in a row.

**Mutation checks** (to show the key tests can fail; each change was reverted):

| Change | Test that failed |
|---|---|
| Wallets read without `FOR UPDATE` | 12 concurrent withdrawals → overdrawn |
| Role read without a lock (snapshot) | role change in progress → withdrawal allowed after demotion |
| Duplicate-request check removed | 8 identical requests → several transfers / database error |

**Upgrade check:** a database built by the previous commit (`bafb4fe`, schema 7) contained users,
a cash top-up, a personal transfer pair, a shared wallet, a membership, an invitation and a QR
code. It was then upgraded to version 8:
- All 19 existing rows were **identical** after the upgrade (compared column by column; the only
  difference is the new `schema_version` row).
- The three existing transactions have NULL kind and initiator.
- On the upgraded data, a contribution and a withdrawal worked, both histories were correct, and
  total money stayed at $80.00.
- Running the migration again applied nothing.

**Real app on Linux, two users** (real `lamma` database, upgraded to 8 on launch):

| Step | Who | Result |
|---|---|---|
| Contribute $50 to Beirut Trip (light, wide) | Lina (Owner) | Lina $166.00 → $116.00, Beirut Trip $50.00, target 3% |
| Contribute $20 (dark, compact) | Rami (Spender) | Beirut Trip $70.00 |
| Withdraw $10 | Rami | Beirut Trip $60.00, Rami $41.50 |
| Send Money $5 to Lina | Rami | Beirut Trip $55.00, Lina $121.00 |
| View the shared history and their own personal history | both | as described in §1 |
| Make Rami a Contributor, then reopen the wallet | Lina, then Rami | Rami sees only Contribute |

- Database afterwards: 8 transfer rows, in 4 linked pairs, with kind, initiator and
  balance_after correct.
- Total money is **$217.50 before and after**.
- Rami is now a Contributor in Beirut Trip; he was a Spender before this test.
- The theme was set back to System.
- Screenshots are attached to the handoff.

### 4. Outstanding issues and decisions for review

1. **Per-operation maximum:** each contribution, withdrawal or send is limited to $10,000.00, the
   existing rule for top-ups and Send Money ("existing currency and decimal conventions"). It is
   not a role-based spending limit. Tell us if shared wallets should have no per-operation cap.
2. **Short references:** history rows show the first 8 characters of the transfer id; the full id
   is stored, and both rows share it. No separate `reference_code` column was added.
3. **Narrow windows:** long detail lines in history rows end with "…", hiding the date and
   reference. This is the existing behaviour of all history rows; nothing was lost, but you may
   want detail lines to wrap.
4. **Sending to yourself:** a Spender can use Send Money to their own number. It is recorded as
   "Money Sent … (you)" rather than as a withdrawal. It is allowed because the brief says
   "or the sender's own personal wallet"; a source and destination that resolve to the same
   wallet are refused.
5. **Who did it:** shared-wallet rows record the initiating member; personal Send Money rows
   still don't (unchanged).
6. **The JavaFX CSS warning from P02** now appears 16 times in the GUI test run instead of 3:
   8 in `SharedWalletMembershipFlowTest` and 8 in the new `SharedWalletFundsFlowTest`. Both switch
   accounts while a shared wallet with more icon buttons is open. A trace confirmed it is the same
   cause (the old screen is restyled after being discarded); still harmless (see P02 §4.6).
7. **Earlier transfers are not re-labelled:** rows created before V008 have no kind and keep
   their old labels. There were no shared-wallet transfers before P03, so nothing is affected.
8. **History limit:** the screens show the latest 100 transactions per wallet (same as personal);
   there is no paging yet.

### 5. Windows manual-testing checklist

Verified on Linux only. Please run this on Windows with two accounts, A (Owner) and B (member):

1. `git pull`, then `mvn -q compile exec:java` → `OK: … schema version 8 (1 migration(s) applied now)`.
2. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 332, Failures: 0, Errors: 0, Skipped: 0`.
3. A: top up the personal wallet if needed → open a shared wallet → **Contribute** `20` → the review
   shows From "Your Personal Wallet" and To the wallet → **Confirm** → both new balances shown →
   **Done** → the balance, target and Recent activity ("Contribution · From A") are updated.
4. Try `0`, `abc`, `1.234` and more than your balance → each is refused, nothing moves.
5. B as **Contributor**: only **Contribute** is shown; contribute `5`.
6. A makes B a **Spender**. B: **Withdraw** `3` → B's personal balance increases. B: **Send Money**
   → an unknown number is refused → A's number shows A's name → `2` → **Confirm and Send**.
7. Double-click **Confirm** once → only one transaction appears in **View all**.
8. **View all** (as A and as B): the same rows, newest first, + for contributions, − for the
   withdrawal and the send, with names and "Ref …".
9. Home → **View all** for A and B: "Contribution · To <wallet>", "Withdrawal · From <wallet>",
   "Money Received · From <wallet> · sent by B".
10. A removes B → B can no longer open the wallet or its history.
11. Resize narrow and wide, Light and Dark → buttons and amounts readable.
12. Personal top-ups and Send Money still work as before.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| Previous commit | `bafb4fe` (Shared Wallet P02) |
| P03 commit | `84ca5d1` |
| `main` | unchanged; nothing merged |

---

## P04A — UX, Account Settings & Contacts

### 1. Summary of changes

**Role dropdown.**
- On the shared wallet screen, the Owner sees a dropdown (Contributor / Spender) on every
  member's row instead of the "Make Spender / Make Contributor" button. The Owner's own row shows
  a fixed "Owner" badge, and other members see badges only.
- Choosing a different role shows the inline confirmation ("Make Rami a Spender? …").
  - Confirm saves the change and reloads the list from the database.
  - Cancel puts the dropdown back to the saved role.
- The service checks are unchanged: Owner only, Contributor ↔ Spender only.

**Account settings (Profile → Account).**
- **Edit name:** first and last name, with the same rules as registration. The same sign-in
  continues with the new name (`UserSession.replace`), so Home and Profile show it at once.
- **Change phone number:** needs the current PIN. The number is validated and normalized as at
  sign-up; numbers used by another account (UNIQUE in the database) or the same number are
  refused. Afterwards you sign in with the new number; the old one no longer works.
- **Change PIN:** needs the current PIN, then the new 6-digit PIN twice; it must differ from the
  current one. Only a new salted PBKDF2 hash is stored.
- **Re-authentication:** a wrong current PIN counts towards the **same 5-try lockout as sign-in**
  and is saved even though the change is refused. After 5 wrong PINs, both changes and sign-in
  are blocked for 5 minutes, so the settings screens can't be used to guess a PIN. PINs are typed
  in password fields, cleared after a failure, and never shown.
- **Forgotten PIN: not implemented (blocked).** LAMMA stores no verified email address and has no
  SMS service, so there is no second channel to prove who someone is. A recovery based only on
  the phone number or on personal details would let anyone who knows a number take over the
  account. This needs an OTP/SMS or email service first. No placeholder control was added.
- **Theme:** unchanged. No other settings were added; there are no other user preferences to set.

**Contacts (Profile → Contacts).**
- **Import CSV or vCard (.vcf)** via a file chooser:
  - CSV: header or no header, comma or semicolon, quoted fields, and Google/Outlook column
    names (First/Last Name, "Phone 1 - Value").
  - vCard: FN or N, the first TEL line, and folded lines.
  - Limits: up to 1 MB and 2,000 contacts per file.
- **Import feedback:** "2 contacts imported, 1 already saved or repeated (skipped), 1 could not be
  imported", followed by the reason for each entry ("Line 4 (Broken): Phone number may only
  contain …").
- **Duplicates:** numbers already in the book, or repeated in the file (in any written form, e.g.
  `70 123 456` and `+961 70 123 456`), are skipped and the existing entry kept.
  UNIQUE (owner, number) in the database backs this.
- **Managing contacts:** search by name, or by number in any written form (`70 12`, `070123`,
  `+961 70`). Delete asks for confirmation.
- **Private:** every query is limited to the session's user. Another user can neither list nor
  delete them (tested).
- **Suggestions in recipient fields:** personal Send Money, shared wallet Send Money and Invite
  Member.
  - Typing part of a name or number lists up to 5 matches under the field.
  - Choosing one **only fills the phone field**. The user then presses Find / Send Invitation as
    before, so the registered-user lookup and every check still apply. A contact without a LAMMA
    account is refused ("No LAMMA account uses this phone number", tested).
- **Not included:** no operating-system or cloud contact sync.

### 2. Schema (V009) and the LDM

| Change | Columns | Why |
|---|---|---|
| `contacts` (new) | `contact_id`, `owner_user_id` → users, `display_name` (≤100), `phone_number` (normalized, same CHECK as `users`), `created_at`; UNIQUE (`owner_user_id`, `phone_number`) | The LDM has no contact entity. This is the minimum: contacts belong to one user and are not linked to `users` rows (a contact isn't an account) |

No other schema changes. Name, phone and PIN changes use the existing `users` columns, and the
lockout uses the V002 columns.

**Files**
- New: `V009__add_contacts.sql`, `auth/AccountService`, `contacts/ContactFiles`,
  `contacts/ContactService`, `ui/AccountSettingsView`, `ui/ContactsView`, `ui/ContactSuggestions`.
- Changed: `UserSession` (+ `replace`), `ProfileView` (Account and Contacts cards, notices),
  `SharedWalletDetailView` (role dropdown), `SendMoneyView`, `SharedTransferView`,
  `InviteMemberView` (suggestions), `AppNavigator`, `SchemaMigrator`, `lamma.css` (suggestion
  styles), and the test database helpers (drop `contacts`).

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `84ca5d1` (P03), MySQL and display | 332 | 332 | 0 | 0 |
| No MySQL settings, no display | 350 | 131 | 0 | 219 |
| MySQL settings, no display | 350 | 284 | 0 | 66 |
| **MySQL settings and a display (virtual display)** | **350** | **350** | **0** | **0** |

New tests (18):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `AccountServiceIntegrationTest` | 5 | MySQL | Name validated, trimmed and saved, and sign-in still works. Phone change: wrong PIN, number taken by another account, same number, invalid number and PIN format are all refused with nothing changed; after a change the new number signs in and the old one doesn't. PIN change: wrong current PIN, mismatch, same PIN and bad formats refused; the hash changes, never contains the PIN and is PBKDF2; old PIN refused, new PIN accepted. **5 wrong PINs lock changes and sign-in**, even with the right PIN, until the lock expires (simulated clock); a correct PIN resets the counter |
| `ContactServiceIntegrationTest` | 4 | MySQL | Import with valid, duplicate (written differently), no-name, invalid, ambiguous and missing-phone entries, each reported; re-import adds nothing; vCard import; empty and unrecognised files refused. **Privacy**: another user can't list or delete them, while the same number in another user's book is fine. Search by name, by number in several written forms, and with a limit |
| `ContactFilesTest` | 6 | — | CSV without a header, columns in any order, quoted fields and escaped quotes, Google/Outlook headers, semicolons, a header without a phone column; vCard with parameters, folded lines, N vs FN, `tel:` URIs, a card without a phone, an incomplete card, a BOM; format detected from content |
| `UserSessionTest` (+1) | 1 | — | `replace` works only for the current sign-in and the same user id; views built for the old details become stale |
| `AccountAndContactsFlowTest` | 2 | MySQL + display | Real screens: edit name (shown on Profile and Home, same sign-in); change phone (taken number, then wrong PIN with the PIN field cleared, then success); change PIN (mismatch, then success; no PIN is shown on screen; sign-in uses the new PIN). Contacts: import a file (summary and per-line reasons), search, re-import (all skipped). **Suggestions** in Send Money (choose → number filled → Find shows the registered user), in Invite Member (a contact without an account is refused by the normal lookup) and in shared Send Money; delete with confirmation |

**Existing tests changed:** `SharedWalletMembershipFlowTest` now uses the role dropdown, and
checks that Cancel restores the saved role and the database value. A first version waited for
the dropdown's own value, which shows the new role before it is saved; it now waits for the
saved confirmation.

**Problems found and fixed during verification**
- **Suggestions missing for text typed early:** text typed before the contacts had loaded showed
  no suggestions unless the field had focus. The full GUI run caught it; suggestions now refresh
  when the contacts arrive, and the test then passed three runs in a row.
- **Cut-off descriptions:** setting descriptions on Profile were cut with "…" on narrow windows;
  they now wrap.

**Migration check:** the real `lamma` database (users, wallets, transfers, shared wallets) was
upgraded to version 9 on launch. Only the new table was added; existing rows are untouched,
because V009 only creates a table.

**Real app on Linux:** Lina's Profile (light, wide; dark, compact) showed the Account and Contacts
cards.
- Importing a 3-line CSV gave 2 contacts and 1 reported error.
- Send Money suggested "Rami Khoury · +961 70 123 456" for "Ram".
- Change Phone with Rami's number showed "Another LAMMA account already uses this phone number".
- The dev database now holds those two contacts for Lina.
- No PIN or phone number was actually changed on the real accounts.

### 4. Known limitations

1. **No forgotten-PIN recovery:** it needs an SMS/OTP or verified-email service (see §1).
2. **Theme** is still saved per computer, not per user.
3. **Search** compares contacts in memory, which is fine for personal contact books (up to a few
   thousand entries).
4. **Duplicates:** a contact whose number is already saved keeps its existing name, and the import
   doesn't offer to rename it.
5. **CSV encoding:** files are read as UTF-8. Files saved in other encodings may show wrong
   accented characters.

### 5. Windows manual-testing steps

1. `git pull`, then `mvn -q compile exec:java` → `… schema version 9 (1 migration(s) applied now)`.
2. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 350, Failures: 0, Errors: 0, Skipped: 0`.
3. Profile → Name **Edit** → change it → Profile and Home show the new name.
4. Profile → Phone number **Change** → another account's number → refused; a wrong PIN → refused
   and the PIN field cleared; a new number with the right PIN → sign out and sign in with it.
5. Profile → PIN **Change** → mismatch refused → change it → sign in with the new PIN.
6. Profile → Contacts → **Import Contacts** → choose a `.csv` (e.g. `Name,Phone` /
   `Rami,70 123 456` / `Bad,12ab`) and a `.vcf` exported from a phone → check the summary,
   search, then Delete one.
7. Send Money → type part of a contact's name → choose the suggestion → **Find Recipient**.
   Repeat in a shared wallet's Send Money and in Invite Member.
8. As Owner: a member's role dropdown → Spender → Cancel (nothing changes) → Spender → Confirm.
9. Light/Dark and narrow/wide: the dropdowns, suggestions and settings forms stay readable.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| P04A commit | `ff8a9b3` |

---

## P04B — Gift Wallet Option

### 1. Summary of changes

- **Creating the wallet:** the New Shared Wallet form has a **This is a gift wallet** check box.
  - **Off** (the default): the form and the result are exactly as before.
  - **On:** a recipient section appears, with the phone number, contact suggestions (P04A),
    **Find**, and the person found ("Gift for Nour Saad · +961 71 555 666").
- **Choosing the recipient:**
  - The recipient must be a **registered LAMMA user**, found with the same lookup as Send Money,
    and not the creator.
  - Creating with the switch on but nobody found is refused: "Choose the gift recipient: enter
    their phone number and press Find."
  - Editing the number after a lookup clears the choice.
- **Saving:** the recipient is stored in the LDM's existing **`shared_wallets.gift_recipient_id`**.
  `create()` re-checks it **inside the same transaction** that saves the wallet (a registered user
  other than the creator), so a forged or stale id is refused and nothing is saved.
- **Display:** members see a "Gift for Nour Saad" badge on the wallet's balance card and "Gift for
  …" on its Wallets tile.
- **Information only:**
  - No new wallet type and no separate balance (still `wallet_type = 'SHARED'`).
  - Owner / Contributor / Spender work exactly as before.
  - The recipient is **not** made a member: they can't see the wallet or its history, and receive
    nothing.
  - No ownership transfer and no payout.
- **The recipient can't be changed:** it is set only at creation, and no service method or SQL
  statement updates `gift_recipient_id`. A test runs every membership and money operation on a
  gift wallet and checks that the stored recipient is unchanged.

### 2. Schema and the LDM

**No migration.** P04B uses the LDM column `SHARED_WALLET.gift_recipient_id`, created by V006 with
a foreign key to `users`.

**Files**
- Changed: `SharedWalletService` (`findGiftRecipient`, the gift check in `create`,
  `NewSharedWallet.asGiftFor`, the recipient's name in summaries), `SharedWallet.Summary`
  (+ `giftRecipientName`, `isGift()`), `CreateSharedWalletView`, `SharedWalletDetailView` (badge),
  `WalletsView` (tile), `lamma.css` (check boxes).
- `create()` now also declares `ValidationException`. Its callers already handled it.

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `ff8a9b3` (P04A), MySQL and display | 350 | 350 | 0 | 0 |
| No MySQL settings, no display | 355 | 131 | 0 | 224 |
| MySQL settings, no display | 355 | 287 | 0 | 68 |
| **MySQL settings and a display (virtual display)** | **355** | **355** | **0** | **0** |

New tests (5):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `GiftWalletIntegrationTest` | 3 | MySQL | The gift wallet stores the recipient's id. Members see the name; it is still a SHARED wallet with the normal balance. **The recipient has no access** (not listed; details and history refused) and **gets no money**. Normal roles apply (a Contributor still can't withdraw). The lookup refuses unknown numbers, the creator's own number and invalid input; **`create` refuses the creator's id and a nonexistent id, saving nothing**. An ordinary wallet has no recipient. **The recipient is unchanged** after invitations, role changes, QR codes, contributions, a send and a member leaving |
| `GiftWalletFlowTest` | 2 | MySQL + display | Real screens: the switch reveals the recipient section; creating without a recipient is refused; the creator's own number is refused; the recipient is picked from a **contact suggestion**, then Find, then Create; the details show "Gift for Nour Saad" and the Owner's money buttons; the Wallets tile shows it. Turning the switch on and off again creates an ordinary wallet |

**Found and fixed:** on the real-app screenshot, the check box's label was nearly invisible
(JavaFX's default check box colours don't use the theme). Check boxes now use the theme's text,
surface and primary colours; checked again in light and dark.

**Real app on Linux:**
- Lina created "Rami's Graduation" ($300 target) as a gift for Rami, choosing him from the contact
  suggestion. The details and the tile show "Gift for Rami Khoury".
- Database: `shared_wallets` row 16 has `gift_recipient_id = 13` (Rami). The dev database now
  contains this wallet.

### 4. Known limitations

1. **Fixed after creation:** the recipient can't be corrected. A mistake means creating a new
   wallet (the empty one can be left unused; closing wallets isn't implemented).
2. **The recipient isn't notified** and can't see the wallet (by design in this phase).
3. **Name only:** members see the recipient's name, not their phone number.

### 5. Windows manual-testing steps

1. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 355, Failures: 0, Errors: 0, Skipped: 0`.
2. Wallets → New shared wallet → leave the switch off → create → it works as before (no "Gift for").
3. New shared wallet → switch on → Create without a recipient → refused. Enter your own number →
   Find → refused. Choose another registered user (type a contact's name and pick the suggestion) →
   Find → their name appears → Create → the details show "Gift for …".
4. Sign in as the recipient → the wallet isn't in their Wallets.
5. Check the switch and its label in Light and Dark.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| P04B commit | `0145fd5` |

---

## P04C — Shared Expenses & Splitting

### 1. Summary of changes

**Creating an expense** (shared wallet → Expenses → **Add Expense**; any current member):
- Title, total, optional notes, and participants (current members, all ticked by default).
- **Split equally** or with **custom amounts** (one field per participant). A live preview shows
  every share; custom shares must add up exactly to the total.
- **Collection:**
  - **Into the shared wallet**: each participant pays their share from their Personal Wallet
    into the shared wallet.
  - **Reimburse the member who paid**: choose the member who paid the bill (not necessarily the
    creator, nor even a participant). Their own share is marked **covered**, and the others pay
    them back directly, Personal Wallet to Personal Wallet. Nothing passes through the shared
    wallet, and the outside payment is **not** recorded as a LAMMA transaction. The screen says
    so: "Nour Saad paid the full $50.00 outside LAMMA (not a LAMMA transaction)…".
- **Creating charges nobody** and never spends the pooled balance: it only sends each participant
  a PENDING payment request. Outgoing transfers from the shared wallet are still only the
  P03 Withdraw / Send Money of the Owner and Spenders.

**Splits:**
- Exact cents with `BigDecimal`; no floating point anywhere.
- **Equal:** any remaining cents go one each to the participants with the lowest user ids, so the
  same input always gives the same split and it adds up exactly. A total too small to give
  everyone at least $0.01 is refused.
- **Custom:** every share ≥ $0.01, at most 2 decimals, and the shares add up exactly to the total.
- The total follows the existing per-operation rules ($0.01–$10,000.00).

**Answering a request.** Pending requests appear in a new **Payment requests (n)** card on
**Wallets** ("Lina Haddad asks you for $30.00 for “Hotel” in Beirut Trip · goes into the shared
wallet") and on the expense's own screen.
- **Pay** asks for confirmation ("Pay $30.00 from your Personal Wallet to “Beirut Trip”?"). It
  then makes one transfer, and the request becomes **Paid** only in the same transaction as that
  transfer.
- If the transfer fails (insufficient funds, database error), the request stays **Pending** and
  can be retried.
- **Decline** marks it **Declined**. A declined share is **not redistributed**: it stays visible
  as Declined until the creator acts.

**Revising and cancelling** (the creator, or the wallet's Owner):
- **Revise Unpaid Shares:**
  - The form shows every share that isn't paid, plus, for a reimbursement, the payer, who can
    absorb part of it. Paid shares are listed but can't be changed.
  - New amounts ($0.00 allowed) must add up to the same unpaid total.
  - For each person: an unchanged pending request is kept; otherwise the old request is
    **Cancelled** (kept in the history) and, if they still owe something, a new **Pending**
    request replaces it (linked by `replaces_request_id`).
  - Re-asking someone who declined for the same amount is allowed.
  - Members who have left must be set to $0.00.
- **Cancel Expense** (with confirmation): pending requests become **Cancelled**. Payments already
  made stay where they are; nothing is refunded automatically.
- Paid requests are immutable; cancelled or declined requests can't be paid.

**Seeing it all:**
- The shared wallet's **Expenses** card lists each expense with its total, collection method and
  "$x outstanding" / Settled / Cancelled.
- The expense screen shows: total and status; the collection method; Collected / Outstanding /
  Covered with a progress bar; each participant's share and status (Paid, Pending, Declined,
  Cancelled, Covered by payer, Nothing owed, "no longer a member"); and the **payment history**.
  The history lists every request, including declined and replaced ones, with times and, when
  paid, the transfer reference (**EXP-<request number>**).
- Wallet histories show "Expense payment · From Nour Saad · … for “Hotel” · Ref EXP-7" (shared
  wallet) and "Reimbursement · To Rami Khoury · … for “Taxi to the airport”" (personal).

### 2. Schema (V010) and the LDM

| Table | LDM | Columns used | Notes |
|---|---|---|---|
| `expenses` | EXPENSE | `expense_id`, `shared_wallet_id`, `title`, `total_amount`, `split_method`, `notes`, `expense_date`, `created_at` | Added: `created_by_user_id`, `collection_method`, `paid_by_user_id` (required exactly for reimbursements), `status` OPEN/CANCELLED, `cancelled_at`. **Not used:** `category_id`, `billing_period`, `confirmed_at`, `transaction_id` (a reimbursed bill was paid outside LAMMA, and pooled money is never spent automatically) |
| `payment_requests` | PAYMENT_REQUEST | `request_id`, `requester_id`, `payer_id`, `destination_wallet_id`, `expense_id`, `transaction_id`, `amount`, `status`, `created_at`, `responded_at` | Added: `replaces_request_id` (revision history). **Not used:** `description`, `qr_type`, `qr_token`, `due_date`, `expires_at` (QR payment requests are out of scope) |
| `expense_shares` | — (new) | `expense_id`, `user_id`, `amount`, `covered_by_payer` | The LDM can't record several participants' allocations or their revision. This is the minimum normalized addition |

Constraints:
- **Requests:**
  - At most **one PENDING-or-PAID request per person per expense** (UNIQUE on a stored generated
    column), so a share can't be collected twice.
  - **PAID ⇔ linked to a transfer** (`transaction_id`, itself UNIQUE).
  - Answered requests have `responded_at`; status values are checked; amounts > 0.
- **Expenses:** totals > 0; a reimbursement must name a payer and a pooled expense must not;
  status values are checked; `cancelled_at` is set exactly when cancelled.
- **Transfers:** `transfer_kind` now also allows `EXPENSE_PAYMENT` and `REIMBURSEMENT`.

**Transfers:** paying a request reuses P03's steps, which were extracted unchanged into a shared
`LedgerTransfers` helper; the P03 tests still pass. A payment is the usual
TRANSFER_OUT + TRANSFER_IN pair with one transfer id, its kind, and the paying member. The
transfer's `request_id` is `expense-request-<request id>`, so even if every status check failed,
the UNIQUE index would refuse a second transfer for the same request.

**Locking order**, the same for create, pay, decline, revise and cancel:
1. `wallets` rows in ascending id order, always including the shared wallet's row, which P02
   membership changes and P03 transfers also lock;
2. the `expenses` row;
3. its `payment_requests`.

The payer's membership (and, for a reimbursement, the payee's) and the request's status are
re-read with those locks held.

**Files**
- New: `V010__add_shared_expenses.sql`, `wallet/ExpenseService`, `wallet/LedgerTransfers`,
  `ui/CreateExpenseView`, `ui/ExpenseDetailView`, `ui/ReviseExpenseView`, `ui/PaymentActions`.
- Changed:
  - `SharedWalletFundsService` (uses `LedgerTransfers`)
  - `WalletTransaction` / `TransactionRecords` (+ `expenseTitle`)
  - `TransactionLabels` (expense labels, EXP references)
  - `SharedWalletDetailView` (Expenses card; the "coming later" note removed)
  - `WalletsView` (Payment requests card)
  - `AppNavigator`, `SchemaMigrator`, `lamma.css` (radio buttons)
  - `TransferService`, `TopUpService` (READ COMMITTED; see §3)
  - the test database helpers

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `0145fd5` (P04B), MySQL and display | 355 | 355 | 0 | 0 |
| No MySQL settings, no display | 379 | 136 | 0 | 243 |
| MySQL settings, no display | 379 | 309 | 0 | 70 |
| **MySQL settings and a display (virtual display)** | **379** | **379** | **0** | **0** |

New tests (24):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `ExpenseSplitTest` | 5 | — | Equal split with remainder cents to the lowest ids, independent of input order; exact sums for hundreds of totals and 1–3 people, shares never more than a cent apart; too small to split; custom shares (sum mismatch, 3 decimals, zero share, missing or extra amounts); field validation |
| `ExpenseServiceIntegrationTest` | 13 | MySQL | **Pooled:** creating charges nobody; paying moves the share into the shared wallet and links the request to its transfer; histories show "Hotel"; settled when all have paid. **Reimbursement:** the payer's share is covered, the others pay the payer's Personal Wallet directly, the shared wallet is untouched, no transaction for the outside payment; a payer who isn't a participant. **Decline → revise:** not redistributed; paid shares can't be revised; sums must match; only creator or Owner; superseded requests can't be paid; history and links kept; re-asking for the same amount; the payer absorbing a share. **Cancel:** paid stays, the rest closed, nothing payable. **Authorization:** outsiders, non-participants and other people's requests refused. **Member removed / payee left:** can't pay, history kept, must be revised to $0.00. **Insufficient funds** leaves the request open and a retry works. **Rollback** when the second row fails: still Pending, nothing moved, retry works. **10 identical payments at once → 1 transfer, 9 "already paid"**. **Pay racing cancel or revise (8 rounds)** → always consistent. **A cancellation in progress is waited for, then respected** (deterministic). **Expense payments, P03 withdrawals and personal Send Money at once** → all succeed, no deadlock, money constant |
| `ExpenseSchemaIntegrationTest` | 3 | MySQL | The database refuses a second open request for a person, PAID without a transfer, inconsistent answer times, unknown statuses, zero amounts, a reimbursement without a payer (and a pooled expense with one), cancelled without a time, and unknown transfer kinds |
| `TransferServiceIntegrationTest` (+1) | 1 | MySQL | **Regression:** 24 personal transfers on 6 unrelated wallet pairs at once all succeed (see below) |
| `ExpenseFlowTest` | 2 | MySQL + display | Real screens, three members. **Pooled:** validation, then create (preview $30 each). Nour declines from Wallets; Lina sees "Declined" and revises (a wrong sum is refused, then Rami $45, Nour $15). Lina pays on the expense screen with confirmation; Rami and Nour pay from Wallets (**a double click pays once**). Settled; the wallet activity shows "Expense payment … for “Hotel”"; the history shows the decline and **Ref EXP-** references. **Custom reimbursement:** a custom sum mismatch is refused; Nour is the payer and her share covered; the outside-payment note is shown; Rami pays on the expense screen and Lina from Wallets; Nour's history shows "Reimbursement … for “Dinner”"; money is constant |

**Existing tests changed:** the UI tests give the screens an expense service (stubs return empty
lists). `SharedWalletFlowTest` now expects the Add Expense button and "No expenses yet."

**A pre-existing concurrency bug found and fixed.** A P04C stress test failed once with a database
error on a personal Send Money. InnoDB's deadlock report showed the cause: the duplicate-request
check (`SELECT … WHERE request_id = ? FOR UPDATE`, used since Alpha 2 by top-ups, Send Money and
P03) takes a **gap lock** on the unique `request_id` index under MySQL's default REPEATABLE READ.
Two transfers on **unrelated wallets**, which wallet locks don't serialize, could gap-lock the
same range and block each other's inserts; MySQL then rolled one back. No money was ever wrong,
but the user got a spurious failure.
- **The fix:** the four money services run their transaction at **READ COMMITTED**, where that
  read takes no gap lock. Every decision is still made on rows locked with `FOR UPDATE` /
  `FOR SHARE`, which read the latest committed data at either level.
- **The proof:** a new regression test fails **3 out of 3** times without the fix (a deadlock
  rolls back one transfer) and passes 3 out of 3 with it.

**Mutation checks** (each change reverted):

| Change | Result |
|---|---|
| A PAID request treated as payable | 10 identical payments → database errors (test fails) |
| Paid shares included in a revision | the revision test fails |
| No membership check for the payer | the removed-member test fails |
| Request row read without a lock | **not detected**, expected: every operation that changes a request first locks the shared wallet's row and the expense, so this lock is a second layer |
| No shared-wallet / expense / request locks in `pay()` | the deterministic cancellation test fails (the payment proceeds and hits a database error instead of the clean "request closed") |

**Problems found in screenshots and fixed:**
- Expense payments showed the same reference "EXPENSER" (the first 8 characters of
  `expense-request-…`); they now show **EXP-<request number>**.
- Every paid request was labelled "revised request": a `wasNull()` check after the wrong column
  read a NULL `replaces_request_id` as 0. A test now checks it.
- On narrow windows the Pay confirmation text was squeezed to one character per line and "View
  expense" was cut to "Vi…"; confirmations now put the question above the buttons.
- An empty message line left a gap on the expense screen.

**Upgrade check:** a database built by `0145fd5` (schema 9) contained users, a top-up, a personal
transfer, a gift wallet, membership, a contribution, a withdrawal and a contact. It was upgraded
to 10:
- All **26 existing rows were identical**, every column (only the `schema_version` row was added).
- A reimbursement was created, paid and settled on the upgraded data, and the shared history
  still shows the P03 rows.
- Total money stayed at $80.00, and re-running the migration applied nothing.

**Real-app multi-user scenario on Linux** (the real `lamma` database, upgraded to 10 on launch):

| Step | Who | Result |
|---|---|---|
| "Villa deposit", $60, equal, into Beirut Trip | Lina (light, wide) | 2 × $30 pending; nobody charged |
| Decline from Wallets | Rami (dark, compact) | Declined, still shown as his share |
| Revise to Lina $40 / Rami $20; Lina pays $40 on the expense screen | Lina | old requests cancelled, new ones issued; $40 in |
| Pay $20 from Wallets | Rami | Beirut Trip $115.00; Settled |
| "Taxi to the airport", $24, equal, **reimburse Rami** | Rami | Rami covered $12; Lina asked for $12 |
| Pay Rami back from Wallets | Lina | Rami +$12 straight into his Personal Wallet |
| Two small check expenses ("Groceries", "Snacks"): declined, then cancelled | both | no money moved |

- **Database afterwards:** Lina $69.00, Rami $33.50, Beirut Trip $115.00. **Total money $217.50
  before and after.**
- **Request rows:** CANCELLED (superseded), DECLINED, then three PAID requests, each linked to
  its transfer, with `replaces_request_id` set on the revised ones.
- The dev database keeps these expenses.

### 4. Known limitations

1. **Paid shares are final:** after someone has paid, their share can't be increased. If
   others have already paid when someone declines, the creator can re-ask the person who declined,
   absorb it (reimbursement payer), or cancel the expense; paid members can't be asked for more.
   This follows "paid requests are immutable" and "never collect a share twice".
2. **Members who leave:** a member who leaves or is removed keeps their requests in the history,
   but can't pay them (nor be paid back for a reimbursement). The creator revises their share to
   $0.00.
3. **Who can manage:** only the creator **or the Owner** can revise or cancel (the Owner too, so an
   expense isn't stuck if its creator leaves).
4. **No refunds:** cancelling never refunds payments already made; they were real transfers.
5. **Unused LDM columns:** categories, billing periods, due dates, expiry and QR payment requests
   aren't implemented.
6. **No notifications:** pending requests are shown when Wallets or the expense is opened.
7. **Same CSS warning:** the harmless JavaFX CSS warning from P02 appears 18 times in the GUI
   test run (16 before; `ExpenseFlowTest` also switches accounts). The cause is the same.

### 5. Windows manual-testing steps

1. `mvn -q compile exec:java` → `… schema version 10 (1 migration(s) applied now)`.
2. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 379, Failures: 0, Errors: 0, Skipped: 0`.
3. With three members A (Owner), B and C, each with some money: A → shared wallet → **Add Expense**
   "Hotel" $90, equal, into the wallet → the preview shows $30 each → Create → nobody's balance
   changes.
4. C → Wallets → **Payment requests** → **Decline**. A → the expense shows C "Declined" →
   **Revise Unpaid Shares** → B 45, C 15 (a wrong sum is refused) → Save.
5. A pays on the expense screen (confirmation shown). B and C pay from Wallets; double-click
   Confirm once → only one payment. The expense becomes **Settled** and the shared wallet holds $90.
6. B → **Add Expense** "Dinner" $50, custom A 20 / B 18 / C 12, **Reimburse** C → the screen says C
   paid outside LAMMA and C's $12 is covered. A and B pay → C's Personal Wallet goes up by $38;
   the shared wallet doesn't change.
7. Create a small expense and **Cancel** it → requests closed, nothing paid.
8. Check Light/Dark and a narrow window: the Payment requests card, the confirmations and the
   expense screen stay readable.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| P04C commit | `efe884e` |
| `main` | unchanged; nothing merged |

---

## P05-1 — Gift Delivery

**Baseline:** the branch was at P04C (`efe884e`, schema 10, 379 tests), not P03; P05 builds on it.

### 1. Summary of changes

**Send Gift** (Owner of an active gift wallet only). The wallet screen shows a "Gift for <name>"
card with **Send Gift**; other members don't see it.
- **The review screen** shows:
  - the gift wallet's name
  - the recipient's full name and registered number
  - the current gift balance
  - the amount delivered (the whole balance)
  - the balance after ($0.00)
  - the target, marked "information only"
- If pending invitations or join requests exist, it says they will be closed.
- **Confirm** ("Send $40.00 to Nour Saad") is offered only when delivery is possible. Otherwise
  the screen explains why:
  - **$0.00 balance:** "There's nothing to deliver yet…".
  - **Expenses with money outstanding:** they are listed by name; the Owner must settle or cancel
    them first. Nothing is cancelled or settled automatically.
- **After Confirm:** "Gift delivered", with the amount and recipient. **Done** returns to the
  wallet, now completed.
- **No partial delivery:** the whole balance is always sent. Delivery before the target is reached
  is allowed.

**A completed gift:**
- Status **COMPLETED**, balance **$0.00**, kept under **Wallets → Completed gifts** ("Delivered to
  Nour Saad · read-only"); it doesn't disappear.
- The screen shows a **Gift delivered** banner (amount, recipient, date, who sent it, Ref
  GIFT-<id>), a "Delivered" badge, and read-only sections: About, Expenses, Recent activity / View
  all, Members.
- **No actions are offered:** no Contribute / Withdraw / Send Money / Send Gift / Add Expense /
  invitations / QR / role changes / removal / leaving.
- **The service refuses all of them too, even when called directly:**
  - Contributions, withdrawals, sends and new expenses get "This gift has been delivered. The
    wallet is now read-only…".
  - Membership actions and QR join requests are refused because the wallet isn't active.
- **Pending invitations and join requests** are closed in the same transaction as the delivery
  (Cancelled / Rejected). A completed gift takes no new members.
- **The recipient** receives an ordinary incoming transaction: "Gift received · From <wallet> ·
  sent by <Owner> · Ref GIFT-<id>". They don't become a member.
- **Wallet-wide, not personal:** completion applies to the whole wallet, for every member. It isn't
  the per-member "archive" concept (hiding a wallet from one person), which LAMMA doesn't
  implement. No personal wallet is closed or changed apart from receiving the money.

### 2. Schema (V011) and integrity

| Change | Why |
|---|---|
| `wallets.status` allows `COMPLETED` (LDM WALLET.status) | The minimal persistent "delivered/completed" state; no new table and no separate gift balance |
| `wallets.completed_at` (DATETIME, NULL) | When the gift was completed |
| CHECK: `COMPLETED` ⇒ shared wallet, `completed_at` set, **balance = 0**; `ACTIVE` ⇒ `completed_at` NULL | The database itself refuses money in a completed wallet (tested with a direct UPDATE) |
| `transfer_kind` also allows `GIFT_DELIVERY` | Delivery is an ordinary two-row transfer, recognisable in histories |

**How the delivery reuses P03.** `GiftDeliveryService.deliver` is one transaction built on
`LedgerTransfers`:
1. **Lock** the shared wallet and the recipient's personal wallet, lowest id first (the same order
   as every money operation). Contributions, withdrawals, sends, expense payments and membership
   changes all lock the shared wallet's row, so they happen entirely before or after the delivery.
   As in P03, the `shared_wallets` row is never locked here, which avoids the lock-order deadlock
   found in P03.
2. **Already COMPLETED?** Return the earlier delivery to the Owner (`alreadyDelivered`); nothing
   moves.
3. **Re-check with the locks held:** Owner role, still a gift, recipient's personal wallet active,
   no expense with money outstanding, balance > 0 and **equal to the amount the Owner reviewed**
   (otherwise "The gift balance changed since you reviewed it…").
4. **Move and complete:** move the whole balance (TRANSFER_OUT + TRANSFER_IN), close pending
   invitations and requests, and set COMPLETED, all in one commit, or roll everything back.

The transfer's `request_id` is `gift-delivery-<wallet id>` (UNIQUE), a second guarantee against
delivering twice. The per-operation $10,000 cap for typed amounts doesn't apply, since delivery
moves the whole existing balance; the receiving wallet's maximum still applies.

**Files**
- New: `V011__add_gift_delivery.sql`, `wallet/GiftDeliveryService`, `ui/GiftDeliveryView`.
- Changed:
  - `SharedWalletService` (lists and opens completed wallets; `Summary.completed`)
  - `SharedWalletFundsService` (history and overview for completed wallets; the read-only message)
  - `ExpenseService` (the read-only message)
  - `LedgerTransfers` (`lockedRoleAnyStatus`)
  - `WalletTransaction` (`KIND_GIFT_DELIVERY`)
  - `TransactionLabels` ("Gift delivered / received", Ref GIFT-)
  - `SharedWalletDetailView` (Send Gift card, completed view)
  - `WalletsView` (Completed gifts), `SharedTransferView`, `AppNavigator`, `SchemaMigrator`, `lamma.css`
  - the UI tests' service setup

### 3. Build and test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `efe884e` (P04C), MySQL and display | 379 | 379 | 0 | 0 |
| No MySQL settings, no display | 390 | 136 | 0 | 254 |
| MySQL settings, no display | 390 | 319 | 0 | 71 |
| **MySQL settings and a display (virtual display)** | **390** | **390** | **0** | **0** |

New tests (11):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `GiftDeliveryIntegrationTest` | 10 | MySQL | **Delivery below the target:** the preview values; the whole balance reaches the recipient's Personal Wallet; COMPLETED with `completed_at`, $0.00; the recipient's history shows a GIFT_DELIVERY credit; members still list, open and read the history; the recipient isn't a member; money constant. **Unauthorized:** Contributor, Spender, the recipient (non-member, learns nothing), a non-gift wallet. **Zero balance**, and a **balance changed after review** (nothing sent; the new amount works). **Invalid recipient** (personal wallet gone). **Repeat and 8 concurrent deliveries → exactly 1**, the rest `alreadyDelivered`. **Contributions and withdrawals racing the delivery (5 rounds):** either the delivery wins and every later operation gets the read-only message (balance stays $0.00), or a contribution wins and the delivery reports the changed balance; never a database error; money constant. **Rollback** when the second row fails: still ACTIVE, balances and invitations unchanged, retry works. **Open expenses block delivery** (pooled and reimbursement; nothing cancelled silently); settled or cancelled ones don't. **Read-only after completion:** contribute, withdraw, send, new expense, invite, role change, remove, leave, new QR code and join by old code all refused; pending invitation and join request closed; the database refuses a balance UPDATE; members kept. **Persists after a restart** (new services), and a retry still says "already delivered" |
| `GiftDeliveryFlowTest` | 1 | MySQL + display | Real screens, from three sides. A Contributor sees no Send Gift. The Owner gets the zero-balance explanation, then the open-expense block naming "Cake", then the review (all seven values, the closing note). A **double-clicked Confirm delivers once**. The completed wallet shows the banner (with Ref GIFT-<id>), the "Delivered" badge, $0.00, no actions or Owner tools, and "Gift delivered" in activity; it appears under Completed gifts. A member can't contribute even by opening the screen directly. The recipient's history shows "Gift received · From … · sent by Lina Haddad". Money constant |

**Mutation checks** (each reverted; all detected):

| Change | Test that failed |
|---|---|
| No "already delivered" check | repeat / concurrent delivery |
| No open-expense check | open expenses block delivery |
| No reviewed-amount check | changed balance |
| No Owner check | only the Owner can send |

**Bug found and fixed:** a gift wallet *without a target* was reported as "not a gift". The JDBC
`wasNull()` check was made after reading a different column, the same mistake as in P04C. All
`wasNull()` uses in the code base were audited and are now checked right after their own column.

**Existing test changed:** `WalletServiceIntegrationTest` simulates a broken schema by renaming
`wallets.balance`. MySQL refuses that while a CHECK uses the column, so the test now also drops the
new `chk_wallets_completed` before renaming (it already dropped the other balance CHECK). The first
full run caught this; the re-run is the one reported above.

**Upgrade check:** a database built by `efe884e` (schema 10) contained users, a top-up, a transfer,
a gift wallet, membership, a contribution, a withdrawal, a contact and a partly paid expense. It
was upgraded to 11:
- All **34 existing rows were identical** (every column; only the `schema_version` row was added).
- Every wallet has `completed_at` NULL, and total money stayed at $80.00.
- On the upgraded data, Send Gift was correctly **blocked** by the partly paid expense; nothing moved.

**Real app on Linux** (the real `lamma` database, upgraded to 11 on launch). Lina opened "Rami's
Graduation" (a gift for Rami, $0.00):
1. **Send Gift** explained there was nothing to deliver.
2. She contributed $25, then reviewed (Recipient Rami Khoury, +961 70 123 456, $25.00 → $0.00,
   target $300 information only) and confirmed.

Afterwards:
- **The wallet** is COMPLETED with $0.00 and is listed under Completed gifts.
- **Rami** (dark, compact) sees "Gift received +$25.00" on Home and in his history.
- **The database** has the `gift-delivery-16` transfer pair. Lina $69.00 → $44.00, Rami $33.50 →
  $58.50; **total $217.50 before and after**.
- Screenshot fixes: the reference read "GIFTDELI" (now **GIFT-16**), and the completed view showed
  a misleading "Target $0.00 of $300.00 (0%)" (removed; the banner says what was delivered).

### 4. Known limitations

1. **No undo.** A delivered gift can't be reopened or reversed. The recipient can send money back
   with Send Money.
2. **Recipient not notified** beyond the incoming transaction in their history.
3. **Delivery needs no open expenses**, even reimbursements that don't involve the gift's balance,
   because their requests couldn't be paid in a read-only wallet.
4. **Members can't leave** a completed gift (it is read-only for membership too); it stays in their
   Completed gifts list.

### 5. Windows manual-testing checklist

1. `mvn clean javafx:run` (or `mvn -q compile exec:java`) → `… schema version 11 (1 migration(s) applied now)`.
2. `mvn test` (with the `LAMMA_TEST_DB_*` variables) → `Tests run: 390, Failures: 0, Errors: 0, Skipped: 0`.
3. Owner A creates a gift wallet for B (P04B), with member C. C sees no **Send Gift**.
4. A → **Send Gift** at $0.00 → the explanation, no Confirm. Contribute some money. Add an expense
   and leave it unpaid → Send Gift lists it; cancel it.
5. A → **Send Gift** → check name, number, balance, amount, $0.00 after → double-click
   **Send $x to B** → "Gift delivered". B's Home shows "Gift received" once.
6. The wallet shows **Delivered**, $0.00, the banner and no actions. Wallets → **Completed gifts**
   lists it. As C, it is read-only.
7. Light/Dark, narrow/wide: the review, banner and Completed gifts list stay readable.

### 6. Commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| P05-1 commit | the commit that adds this section (hash given in the completion message) |
