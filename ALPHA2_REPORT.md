# LAMMA Alpha 2 — Implementation Report

**Current phase:** P01, Transaction Foundation and Cash Top-Up. **Awaiting human review.** P02 has
not been started.
**Branch:** `alpha2-development`, created from `main` at `b9962d9` (the Alpha 1 baseline, which is
unchanged).
**Verification platform:** Linux (Ubuntu 24.04), OpenJDK 21, Maven 3.9, MySQL 8.0.46, JavaFX 21
on a virtual display. **Nothing has been run on macOS.**

All money in LAMMA is simulated. There is no payment processing, cashier or administrator.

---

## P01 — Transaction Foundation and Cash Top-Up

### 1. Features completed

- **Transaction records:** a new `wallet_transactions` table holds one row per balance change,
  with type, direction (CREDIT/DEBIT), amount, the balance afterwards, status, a request id and a
  UTC timestamp. Card top-ups and transfers can be added later by widening the type and status
  lists; a transfer would be one DEBIT row plus one CREDIT row.
- **Receive Money:** the dashboard button is now functional. It offers **Cash Top-Up** (works)
  and "Credit Card Top-Up – Coming Soon", which is a label and can't be clicked.
- **Cash Top-Up flow:** enter a USD amount → review it ("simulated money, no real payment") →
  **Confirm Top-Up** → success screen with the new balance → **Back to Dashboard**. **Edit
  amount** and **Cancel** are available before confirming.
- **Amount rules** (`Amounts`):
  - from $0.01 to **$10,000.00 per top-up**
  - at most 2 decimal places
  - an optional leading `$`
  - rejects zero, negatives, letters, `1,000`, `1e3`, `.5` and more than 9 whole digits
  - a top-up that would exceed the largest balance the database can hold
    (DECIMAL(15,2), about $10 trillion) is refused
- **Recent Activity:** the dashboard lists the 10 newest transactions, each with type, signed
  amount (`+$25.50`), local date and time, and status. A new wallet shows "No activity yet". The
  list fits future incoming (+, green) and outgoing (−, red) entries.
- **Send Money** stays a read-only "Coming Soon" tile.

### 2. Database changes

- **New migration `V003__create_wallet_transactions.sql`** adds one table; no existing table or
  migration was changed.

  | Column | Rule |
  |---|---|
  | `transaction_id` | primary key, auto-increment (also orders history) |
  | `wallet_id` | references `wallets`, so a wallet that has transactions can't be deleted |
  | `transaction_type` | `IN ('CASH_TOP_UP')` |
  | `direction` | `IN ('CREDIT','DEBIT')`, and a cash top-up must be a CREDIT |
  | `amount` | DECIMAL(15,2), must be greater than 0 |
  | `balance_after` | DECIMAL(15,2), can't be negative |
  | `status` | `IN ('COMPLETED')` |
  | `request_id` | **UNIQUE** |
  | `created_at` | DATETIME, UTC |
  | index | (`wallet_id`, `transaction_id`) |

- **Upgrade check:** a database built by the actual Alpha 1 code (`b9962d9`, from a separate
  checkout), containing a user with a $42.10 wallet, upgraded to version 3. The user, lockout
  counter and balance were preserved. Re-running the migration changed nothing.
- **Test helpers:** the two that wipe the test database now also drop `wallet_transactions`
  first. The Alpha 1 report flagged this as a prerequisite.

### 3. Data integrity

`TopUpService.cashTopUp` runs as **one MySQL transaction**:

1. **Lock** the signed-in user's PERSONAL wallet row (`SELECT … FOR UPDATE`, by the session's user
   id; the screen never supplies a wallet id).
2. If the `request_id` was already processed, **return that earlier result** without crediting
   again. Reusing an id with a different amount or user is rejected.
3. `UPDATE wallets SET balance = balance + ?` and `INSERT` the transaction row.
4. **Commit both, or roll both back** on any error.

- **Concurrent top-ups** to one wallet wait for each other, so no update is lost and each
  `balance_after` is correct.
- **Duplicate submissions** are blocked three ways:
  - the Confirm button is disabled while processing
  - the Confirm button disappears after success
  - each review screen gets one request id, reused on retry and replaced when the amount is
    edited

  Even a retry after the database actually committed can't credit twice.
- **Late results:** a top-up result arriving after Sign Out or navigation isn't displayed, using
  the same guard as the dashboard.
- **Error messages** contain no SQL or connection details, for example "The top-up could not be
  completed because of a database problem, so your balance was not changed…".

### 4. Tests executed

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline on the new branch, before any change (MySQL and display) | 100 | 100 | 0 | 0 |
| No MySQL settings, no display | 151 | 85 | 0 | 66 |
| MySQL settings, no display | 151 | 135 | 0 | 16 |
| **MySQL settings and a display (virtual display)** | **151** | **151** | **0** | **0** |

Skipped tests didn't run and are never counted as passed. There are 50 MySQL integration tests
and 16 JavaFX UI tests. In the final configuration all 151 executed, including all 100 Alpha 1
tests.

New tests (51):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `TopUpServiceIntegrationTest` | 13 | MySQL | credit plus correct record; accumulation and newest-first history; invalid amounts change nothing; **two-user isolation**; **rollback** when the record insert fails (then retry works); **same request credits once**; reused id with other details rejected; **20 concurrent top-ups → exact total and 20 distinct running balances**; **10 concurrent repeats of one request → one credit**; **persistence after "restart"** (new connections and services); overflow refused; missing wallet; empty history |
| `TransactionSchemaIntegrationTest` | 8 | MySQL | database checks: amount > 0, `balance_after` ≥ 0, type, direction, status, top-up must be a credit, unique request id, foreign key, wallet with transactions can't be deleted |
| `CashTopUpFlowTest` | 7 | display | Receive and top-up screens need a session; full flow with invalid inputs → review → confirm → success → dashboard balance and activity; **double-click submits once**; retry reuses the request id; editing makes a new one; result after sign-out not shown; Credit Card and Send Money aren't clickable |
| `AmountsTest` | 22 | — | parsing and limits |
| `TopUpServiceConnectionFailureTest` | 1 | — | outage message |

**Proof that the concurrency tests can fail:** with the row locks (`FOR UPDATE`) temporarily
removed, both concurrency tests failed (10 distinct running balances instead of 20, and duplicate
requests erroring). With the locks restored they pass.

**Changes to existing tests:**
- `DashboardNavigationTest` and `AsyncNavigationRaceTest`: their fake wallet services now also
  return an empty activity list.
- The two test-database helpers: drop `wallet_transactions` first.

### 5. Manual verification (Linux, real app, real MySQL, two launches)

The walkthrough was scripted to drive the real controls and checked the database directly. The
script is not committed.

| Step | Result |
|---|---|
| Existing `lamma` database (Alpha 1, version 2) at launch | ✅ "1 migration(s) applied", now version 3 |
| New user's dashboard | ✅ `$0.00`, "No activity yet" |
| Receive Money | ✅ Cash Top-Up button; Credit Card Top-Up is a label reading "Coming Soon" |
| Invalid `12.345` / `-3` | ✅ "at most 2 decimal places" / "must be greater than zero" |
| `25.50` → review → confirm | ✅ review shows `$25.50`; success shows "New balance: $25.50" |
| Dashboard | ✅ `$25.50`, activity "Cash Top-Up · +$25.50 · Completed" with date and time |
| Second top-up of `100` | ✅ `$125.50`, two rows, newest first |
| Second user (isolation) | ✅ `$0.00`, no activity |
| Database contents | ✅ balances 125.50 / 0.00; 2 CREDIT/CASH_TOP_UP/COMPLETED rows with correct `balance_after` |
| **Restart** | ✅ sign-in required; `$125.50` and both rows persisted |
| **MySQL stopped at Confirm** | ✅ friendly message, balance unchanged, Confirm re-enabled |
| **Retry after restarting MySQL** | ✅ "New balance: $130.50". Database: 3 transactions totalling exactly 130.50, so no double credit |
| Visual check | ⚠️→✅ found the activity type label **invisible** (see Known issues, fixed) and re-checked |

### 6. Known issues

- **Fixed during P01:** the transaction type text in Recent Activity was invisible. The theme
  computes text colour from the scroll pane's transparent background; the label now has an
  explicit colour, confirmed by screenshot.
- **macOS not verified.** The Alpha 1 macOS checklist is still pending. It now expects
  `Tests run: 151 … Skipped: 0` and includes a top-up step.
- **History shows the newest 10** entries only, with no paging or "show all".
- **Balances from before Alpha 2 have no transaction rows.** Alpha 1 never changed balances, so
  in practice every wallet is $0.00, unless someone edited the database by hand.
- **Times are stored in UTC** by the app and shown in the computer's time zone. The test machine
  is set to UTC, so local-time display was only exercised with UTC.
- The JavaFX UI tests run without showing a window. Visual layout was checked separately with
  screenshots.

### 7. Commit and branch

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed to `origin`, upstream `origin/alpha2-development`) |
| Base | `main` at `b9962d9`; `main` is unchanged |
| P01 commit | the commit that adds this report (hash given in the completion message) |
| Other branches | `main` and `claude/serene-goldberg-w79epj` untouched |

### 8. Decisions requiring human review

1. **Top-up limit:** $10,000.00 per cash top-up, with no daily or total limit. Change
   `Amounts.MAX_CASH_TOP_UP` if a different limit is wanted.
2. **Schema compatibility between branches:** after V003, the Alpha 1 app on `main` refuses a
   version-3 database by design. Team members switching branches should use a separate database
   per branch (documented in the README). Alternatively, merge Alpha 2 into `main` sooner.
3. **No cashier or approval step:** any signed-in user can add simulated cash, as instructed.
   Confirm that this is acceptable for the simulation.
4. **Amount input format:** thousands separators (`1,000`) are rejected rather than interpreted,
   to avoid misreading decimal commas.
5. **Opening balances:** I did not create "opening balance" transaction rows for existing wallets
   (see Known issues). If balances must always equal the sum of transactions, decide this before
   P02.
6. **Branch name:** `alpha2-development`, intended for all Alpha 2 phases. Merging into `main`
   awaits your approval.
