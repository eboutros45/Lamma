# LAMMA Shared Wallets — Implementation Report

**Current phase:** P02, Shared Wallet Membership & Permissions. **Awaiting user review.** P03
(financial operations) has not been started.
**Branch:** `alpha2-development` (P01 is `909efdd`). `main` is unchanged.
**Verification platform:** Linux (Ubuntu 24.04), OpenJDK 21, Maven 3.9, MySQL 8.0.46, JavaFX 21
on a virtual display. **Nothing in P01 or P02 has been run on Windows or macOS.**

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
| P02 commit | the commit that adds this section (hash given in the completion message) |
| `main` | unchanged; nothing merged |
