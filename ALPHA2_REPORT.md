# LAMMA Alpha 2 — Implementation Report

**Current phase:** P03, Send Money. **Awaiting product-owner review.** P04 has not been started.
P02 was reviewed and approved by the product owner.
**Branch:** `alpha2-development`, created from `main` at `b9962d9` (the Alpha 1 baseline, which is
unchanged).
**Verification platform:** Linux (Ubuntu 24.04), OpenJDK 21, Maven 3.9, MySQL 8.0.46, JavaFX 21
on a virtual display. **Nothing has been run on macOS or Windows.**

All money in LAMMA is simulated. There is no payment processing, card authorisation, gateway,
bank connection, cashier or administrator.

---

## P03 — Send Money

### 1. Implemented functionality

- **The dashboard's Send Money tile is now a button.** The flow:
  1. **Recipient:** enter a phone number in any format accepted at registration and sign-in
     (the same `PhoneNumbers.normalize`) → **Find Recipient**. A card shows "You are sending to:
     **Rami Khoury** +961 70 123 456" before you can continue.
  2. **Amount:** "Sending to …" and "Available balance: $X" (loaded in the background) are shown.
     The rules are USD, $0.01–$10,000.00, at most 2 decimals; amounts over the shown balance are
     refused here too.
  3. **Review:** amount, recipient's full name, formatted phone, "Your available balance", and
     "This moves simulated funds between LAMMA wallets. No real money is sent." The buttons are
     **Confirm and Send**, **Back** (edit amount) and **Cancel** (dashboard). Opening the review
     deducts nothing (verified in the database).
  4. **Success:** "Money sent", "$60.00 was sent to Rami Khoury.", "New balance: $150.50", and
     **Back to Dashboard**, which reloads the balance and activity.
- **Recipient checks:** unknown number ("No LAMMA account uses this phone number…"), invalid
  number (registration's messages), and own number ("You can't send money to yourself.").
  **Editing the number after a lookup clears the recipient** and requires a new lookup. A lookup
  answer that arrives for a number the user has since changed is ignored. The lookup returns only
  user id, first and last name and phone; there's no PIN, hash or balance (tested).
- **Recent Activity** (10 newest, unchanged limit):
  - sender: **Money Sent**, `-$60.00` in red, "To Rami Khoury · date and time", Completed
  - recipient: **Money Received**, `+$60.00` in green, "From Lina Haddad · date and time",
    Completed
- **No fees and no daily limits.** Transfers go only to registered personal users.

### 2. Database changes

**Migration `V005__add_wallet_transfers.sql`**, one `ALTER` of `wallet_transactions`. V001–V004
are unchanged, and no new table was added.

- **New types:** `TRANSFER_OUT` (must be DEBIT) and `TRANSFER_IN` (must be CREDIT).
- **`transfer_id` (VARCHAR(36))** links the two rows of one transfer; it is the sender's request
  id. **UNIQUE (`transfer_id`, `transaction_type`)** allows exactly one sent and one received row
  per transfer.
- **`counterparty_wallet_id`** references `wallets` and points at the other wallet.
- **CHECK:** transfer rows must have both `transfer_id` and `counterparty_wallet_id`, and the
  counterparty can't be the row's own wallet. All other types must have both empty.
- **Names aren't copied** into transactions. The history query reads the counterparty's name
  through `counterparty_wallet_id` → `wallets` → `users`.
- **Accounting model unchanged:**
  - `wallets.balance` is authoritative
  - `wallet_transactions` is the history
  - nothing is derived from the ledger
- **Upgrade check:** a database built by the actual P02 code (`995fbe4`) contained two users, a
  saved card, a cash and a card top-up, and a $40 opening balance not covered by transactions. It
  upgraded to version 5 with **all balances, transactions and the card byte-for-byte identical**,
  and no existing row got transfer fields. Re-running changed nothing. A transfer then worked on
  the upgraded data, and a P02 request id was still honoured.
- **Real `lamma` database** (P02 data, version 4) upgraded on app launch: "1 migration(s)
  applied".

### 3. Atomic transfer processing

`TransferService.send(senderUserId, recipientUserId, amount, requestId)` is **one MySQL
transaction**:

1. **Resolve both PERSONAL wallets.** The sender comes from the session; the recipient's wallet
   is re-checked, and if it's gone the error is "This recipient can no longer receive money".
2. **Lock both wallet rows with `SELECT … FOR UPDATE` in ascending `wallet_id` order,** the same
   order for every transfer, so opposite transfers can't deadlock.
3. **Idempotency:** if the request id already exists as this sender's `TRANSFER_OUT` to this
   recipient for this amount, the original result is returned and nothing moves. Reusing an id
   with other details is rejected.
4. **Check the sender's actual locked balance:** "Insufficient funds. Your available balance is
   $X." The recipient's balance limit is also checked.
5. **Debit the sender,** `UPDATE … balance = balance - ?`.
6. **Credit the recipient,** `UPDATE … balance = balance + ?`.
7. **Insert `TRANSFER_OUT`** (sender's request id, `transfer_id`, counterparty = recipient).
8. **Insert `TRANSFER_IN`** (its own new unique request id, the same `transfer_id`,
   counterparty = sender).
9. **Commit all of it,** or roll everything back on any error.

The database's `balance >= 0` and the "one row per side" uniqueness remain as backstops.

**UI protection:**
- Confirm, Back and Cancel are disabled while sending.
- One request id is created per review screen and reused on retry, so a retry after a lost
  response can't send twice.
- Back to the amount step creates a new id.
- No success is shown until the database has confirmed.
- Every result passes the existing "same sign-in and same screen" guard.

### 4. Verification results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `995fbe4`, before any change (MySQL and display) | 206 | 206 | 0 | 0 |
| After the shared-code refactor, before any transfer code (MySQL, no display) | 206 | 177 | 0 | 29 (JavaFX) |
| No MySQL settings, no display | 248 | 111 | 0 | 137 |
| MySQL settings, no display | 248 | 206 | 0 | 42 |
| **MySQL settings and a display (virtual display)** | **248** | **248** | **0** | **0** |

Skipped tests did not run and are never counted as passed. There are 95 MySQL integration tests
and 42 JavaFX UI tests. In the final configuration all 248 executed, including every Alpha 1, P01
and P02 test.

New tests (42):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `TransferServiceIntegrationTest` | 19 | MySQL | **Lookup:** any phone format; unknown, invalid and self; recipient holds no private data. **Correctness:** money moves and **both linked records** are correct (type, direction, amount, balance after, counterparty and name, same `transfer_id`); sending the whole balance; **money conserved** across several transfers, an unrelated user sees only their own, and each wallet's ledger matches its balance; **persistence after restart**. **Validation:** zero, negative, too large, 3 decimals, null; **insufficient funds**; **balance changed between review and confirmation**; self-transfer by id; **recipient no longer eligible**; recipient balance limit. **Reliability:** **failure on the second record rolls back debit, credit and the first record**; **repeated confirmation**; **retry after a lost response from a new connection**; request id can't be reused with other details or across types; **25 concurrent $10 sends from a $100 wallet → exactly 10 succeed, 15 "Insufficient funds", balance 0, money conserved**; **60 simultaneous opposite transfers between the same two wallets → no deadlock, exact balances, money conserved** |
| `TransferSchemaIntegrationTest` | 5 | MySQL | valid pair; direction per side; transfer link required for transfers and forbidden otherwise; no self-counterparty; one row per side; counterparty must exist |
| `SendMoneyFlowTest` | 13 | display | Send Money needs a session; **full flow** (lookup → name and formatted phone → amount with balance → review with no deduction → confirm → success → dashboard "Money Sent · To Rami Khoury · -$25.00"); **recipient view "Money Received · From Lina Haddad · +$25.00"**; lookup errors (unknown, invalid, blank, self); **editing the number clears the recipient**; **late lookup answer for an old number ignored**; amount validation including over-balance; **double click sends once**; **lost response then retry → same request id, debited once**; insufficient funds at confirm → Back → corrected amount gets a new request id; **sign-out during send shows no success**; **account switch during lookup never shows the previous user's result**; Cancel sends nothing |
| `TransferServiceConnectionFailureTest` | 1 | — | outage messages for lookup and send |
| `PhoneNumbersTest` (+3), `AmountsTest` (+1) | 4 | — | phone display format; transfer limits and message |

**Proof that the key tests can fail:** each safeguard was temporarily removed and the test
re-run, then restored.

| Safeguard removed | Result |
|---|---|
| Lock the sender's wallet first instead of the lower `wallet_id` | MySQL `Deadlock found`; the opposite-direction test fails |
| Remove the `FOR UPDATE` row locks | The overspend test fails: overdrafts were rejected only by the database's `balance >= 0` rule, as a generic database error instead of "Insufficient funds" |
| Remove the "phone changed during lookup" check | The stale-lookup UI test fails |

**Existing tests changed (intended):**
- `CashTopUpFlowTest`: "Send Money is not clickable" became "Send Money is a button".
- No other existing test changed. The shared transaction insert and read code was moved, with no
  behaviour change (same 177 passing before any transfer code was added).

### 5. Manual testing walkthrough (two registered accounts)

This was run on Linux against the real `lamma` database with the real app, across two launches.
The walkthrough was scripted to drive the real controls and checked the database directly. The
script is not committed. The accounts were **Lina** (`03 123 456`, $210.50 before) and **Rami**
(`70 123 456`, $0.00). Total money in all wallets was **$210.50 before and $210.50 after**.

| # | Step | Result |
|---|---|---|
| 1 | Launch (database at version 4) | ✅ "1 migration(s) applied", now version 5 |
| 2 | Lina → Send Money → `03 999 999` | ✅ "No LAMMA account uses this phone number…" |
| 3 | `+961 3 123 456` (her own) / `7012` | ✅ "You can't send money to yourself." / invalid-number message |
| 4 | `70 123 456` → Find | ✅ "Rami Khoury / +961 70 123 456" |
| 5 | Edit the number to `70 123 45` | ✅ recipient card disappears; a new lookup is needed |
| 6 | `+961 70 123 456` → Continue | ✅ "Sending to Rami Khoury · +961 70 123 456", "Available balance: $210.50" |
| 7 | Amount `210.51` | ✅ "Insufficient funds. Your available balance is $210.50." |
| 8 | Amount `60` → review | ✅ "$60.00 to Rami Khoury +961 70 123 456 / Your available balance: $210.50"; **database balance still 210.50** |
| 9 | Confirm and Send | ✅ "Money sent / $60.00 was sent to Rami Khoury. / New balance: $150.50" |
| 10 | Lina's dashboard | ✅ $150.50; top row "Money Sent · To Rami Khoury · -$60.00 · Completed" |
| 11 | Sign out → Rami signs in | ✅ $60.00; "Money Received · From Lina Haddad · +$60.00 · Completed" |
| 12 | Rami sends $15 to Lina (`03123456`) | ✅ "$15.00 was sent to Lina Haddad. / New balance: $45.00" |
| 13 | **Restart** → Lina | ✅ sign-in required; $165.50; top row "Money Received · From Rami Khoury · +$15.00" |
| 14 | Lina → Rami $5.25, **MySQL stopped at Confirm** | ✅ "The transfer could not be completed… no money was sent…", buttons re-enabled |
| 15 | Start MySQL → Confirm again | ✅ "$5.25 was sent to Rami Khoury. / New balance: $160.25" |
| 16 | Database | ✅ Lina $160.25 + Rami $50.25 = $210.50; three transfers, each **exactly one OUT and one IN row with the same `transfer_id`** and correct counterparties; the failed attempt left nothing |

**To repeat this by hand** (any OS):
1. Register two accounts.
2. Top up the first with cash.
3. Do steps 2–15 above.
4. Check both dashboards and that the sum of the two balances is unchanged apart from top-ups.

### 6. Limitations and known issues

**Fixed during P03, found by screenshot:** on the recipient card and review screen, explanatory
lines were left-aligned under centred titles. They're now centred, on the Send Money screens only.

**Open:**
- **macOS and Windows not verified.** The README checklist now expects
  `Tests run: 248 … Skipped: 0` and has a Send Money step. The JavaFX tests need a display; on a
  headless Linux machine use `xvfb-run`.
- **The review balance is guidance only.** It's loaded when the amount step opens. The database
  re-checks at Confirm and shows "Insufficient funds" if it changed.
- **Recipient lookup reveals registration.** It shows whether a phone number has an account and
  that person's name, which is inherent to "see the recipient's name before sending". There is no
  rate limit on lookups; that's acceptable on a single computer, but worth revisiting before any
  networked use.
- **Counterparty names are read at display time.** Names can't be edited today; if editing is
  added later, history would show the current name.
- **No reversal or cancellation** after completion, as specified.
- **History** still shows the 10 newest entries only.

### 7. Commit reference

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed) |
| Previous commit | `995fbe4` (P02) |
| P03 commit | the commit that adds this section (hash given in the completion message) |
| `main` | unchanged at `b9962d9`; nothing merged |

---

## P02 — Saved Cards & Credit Card Top-Ups

### 1. Implemented functionality

- **Payment Methods.** A new button on the dashboard, next to Receive Money.
  - Lists the user's saved cards. Each shows the nickname (prominent), then the cardholder name,
    then `Visa •••• 4242 · Expires 08/29`, and "Expired" when applicable.
  - **Rename** edits the nickname only, inline, with Save or Cancel. The card details are
    unchanged.
  - **Remove** asks for confirmation ("Past top-ups stay in your activity").
  - **Add Card** takes a required nickname plus card details.
  - With no cards, it shows "You have no saved cards yet…".
- **New card entry** (`CardForm`, shared by Add Card and the top-up flow): cardholder name, card
  number, expiration `MM/YY`, and CVV (masked). Validation (`CardValidator`):

  | Field | Rule |
  |---|---|
  | All fields | required |
  | Cardholder name | 2–60 characters: letters, spaces, `' . -` |
  | Card number | 12–19 digits (spaces and dashes allowed); must pass the Luhn checksum; brand recognised from its first digits (Visa / Mastercard incl. 2-series / Amex / Discover, else "Card") with a matching length |
  | Expiry | `MM/YY` or `MM/YYYY`; not before the current month; at most 20 years ahead |
  | CVV | 3 digits, or 4 for American Express |

  All problems are reported at once. Any structurally valid number is accepted; there is no list
  of demo cards.
- **Credit Card Top-Up** (Receive Money → Credit Card Top-Up):
  - **No saved cards:** the new-card form opens immediately.
  - **Saved cards:** a selection list shows each card's nickname, cardholder, masked number and
    expiry. Expired cards are listed but disabled. **Use a New Card** opens the form, which has a
    "Back to saved cards" link.
  - Then amount (same rules as cash: $0.01–$10,000.00) → review (amount, "Paying with Whish Card
    (Visa •••• 4242)" or "Paying with new card Visa •••• 4242 · cardholder", and "This is a
    simulated payment. No real card is charged.") → **Confirm Top-Up** → success with the new
    balance → **Back to Dashboard**, which reloads the balance and Recent Activity.
- **Save Card after payment** (new cards only): "Would you like to save this card for future
  top-ups?"
  - **Save Card** asks for a required nickname and saves the metadata.
  - **Not Now** returns to the dashboard, and nothing is kept.
  - If saving fails, the message says "Your top-up of $X is complete and was not affected", and
    the user can try again or choose Not Now.
- **Recent Activity:** card top-ups appear as "Card Top-Up", with "Visa •••• 4242 · date and time"
  underneath.

### 2. UX flows

```
Dashboard ─ Payment Methods ─┬─ Rename (inline) / Remove (confirm)
                             └─ Add Card (nickname + card) ─> back to list ("…was saved.")

Dashboard ─ Receive Money ─ Credit Card Top-Up
   ├─ no saved cards ──> New card form ─┐
   └─ saved cards ──> Choose a Card ────┼─ (Use a New Card) ─> New card form
                                        v
                       Amount ─> Review ─> Confirm ─> Success
                                                        ├─ saved card: Back to Dashboard
                                                        └─ new card: Save Card? ─ Save (nickname) ─> Card saved
                                                                               └─ Not Now ─> Dashboard
```

- Every screen requires an active session.
- All database work runs in the background.
- Each background result is applied only if the same sign-in is still active and the same screen
  is still showing. This is the existing P03/P04 guard, reused in every new screen.

### 3. Database changes

**Migration `V004__add_payment_cards_and_card_top_ups.sql`** (V001–V003 unchanged):

- **New table `payment_cards`:**

  | Column | Rules |
  |---|---|
  | `card_id` | primary key |
  | `user_id` | references `users` |
  | `nickname` | VARCHAR(40), not blank |
  | `cardholder_name` | VARCHAR(60), not blank |
  | `brand` | one of VISA, MASTERCARD, AMEX, DISCOVER, OTHER |
  | `last4` | CHAR(4), digits only |
  | `exp_month`, `exp_year` | 1–12, 2000–2099 |
  | `created_at` | |

  A **UNIQUE** rule on (`user_id`, `brand`, `last4`, `exp_month`, `exp_year`) stops a user saving
  the same card twice. **There is no column for a card number or CVV.**
- **`wallet_transactions` (one `ALTER`):**
  - the type list is widened to `CASH_TOP_UP`, `CARD_TOP_UP`
  - "top-ups must be CREDIT" now covers both types
  - new nullable `card_brand` and `card_last4` columns, which are **required for `CARD_TOP_UP`
    and must be NULL otherwise**; `card_last4` must be 4 digits
- **Accounting model unchanged:**
  - `wallets.balance` stays the authoritative balance
  - `wallet_transactions` records the activity
  - nothing is recalculated from history
- **Opening balances: no schema change was needed.** Existing balances aren't touched by V004.
  Verified: a P01-era wallet holding $142.10 with only $100.00 of transactions upgraded with its
  balance and transaction intact.
- **Upgrade check:** a database built by the actual P01 code (`442ccf9`), with a user, balance
  and cash transaction, upgraded to version 4. Data was preserved, the existing cash row got NULL
  card columns, no cards were created, and re-running changed nothing. The **P01 request id is
  still recognised after the upgrade** (`alreadyProcessed=true`, no second credit).
- **Real `lamma` database** (P01 data, version 3) upgraded on app launch: "1 migration(s)
  applied".
- **Test helpers** drop `payment_cards` along with the other tables.

### 4. Card-data handling and security constraints

- **The full card number and CVV never leave `CardValidator.validate`.** That method returns
  `CardMetadata` (cardholder, brand, last four, expiry). The record has no field for the number or
  CVV, and its `toString` contains neither (tested).
  - Services, MySQL and background tasks receive only metadata.
  - So do log messages and later screens.
- **The form clears the number and CVV fields as soon as the card is accepted.** In Add Card,
  where a save can fail and be retried, the fields are cleared and locked, and the retry uses the
  validated metadata.
- **Verified absent from storage and logs:**
  - **Automated:** a test dumps every column of `payment_cards`, `wallet_transactions`, `users`
    and `wallets` after saved-card and new-card top-ups. It checks that neither full number, no
    8-digit prefix and neither CVV appears, and that no column name contains cvv, number or pan.
  - **Manual:** a full `mysqldump` of `lamma` and the app's complete console output from both
    walkthrough launches were searched for every card number used, in raw and spaced form, for
    8-digit prefixes and for the CVV values. **None was found.** The only "cvv" hits were the word
    "CVV" in on-screen text that my script printed.
- **Isolation:** every card query includes `user_id = ?` with the **session's** user id, so a card
  id from the screen can only reach the user's own cards. Using another user's card id for a
  top-up is refused with no credit (tested).
- **Saved cards are labels for simulated payments,** not reusable payment credentials, and the
  screens say so.
- **No real payment code:** no gateway, authorisation or external verification.

### 5. Transaction integrity (card top-ups)

- **Shared path:** `TopUpService.savedCardTopUp` and `newCardTopUp` use the same transaction as
  cash. It locks the user's wallet row (`FOR UPDATE`), checks the request id, loads the saved card
  (own cards only) and checks it hasn't expired, then updates the balance and inserts the record
  with the card snapshot. Everything is committed or rolled back together.
- **Idempotency is checked before the card is looked up,** so a retry after the card was removed
  still returns the original result rather than failing or crediting twice.
- **Request ids** can't be reused across top-up types or amounts.
- **No double credits:** the Confirm button is disabled while processing, the same request id is
  reused on retry, and a new id is created when the amount is edited.
- **Save Card is a separate operation** after the top-up has committed. Its failure can't reverse
  or repeat the top-up (tested through the UI).
- **Removing a card** deletes only its `payment_cards` row; history keeps brand and last four
  (tested).

### 6. Automated test results

| Configuration | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline at `442ccf9`, before any change (MySQL and display) | 151 | 151 | 0 | 0 |
| No MySQL settings, no display | 206 | 106 | 0 | 100 |
| MySQL settings, no display | 206 | 177 | 0 | 29 |
| **MySQL settings and a display (virtual display)** | **206** | **206** | **0** | **0** |

Skipped tests did not run and are never counted as passed. There are 71 MySQL integration tests
and 29 JavaFX UI tests. In the final configuration all 206 executed, including every Alpha 1 and
P01 test.

New tests (55):

| Class | Tests | Needs | Covers |
|---|---|---|---|
| `CardValidatorTest` | 20 | — | 8 valid brands/formats keep only last four; invalid numbers (Luhn, length, letters, brand length); expiry (current month OK, expired, bad formats, too far ahead); CVV (Amex 4); cardholder rules; all problems at once; masking helpers |
| `PaymentCardServiceIntegrationTest` | 8 | MySQL | add/list; **required nickname**; duplicate card; **rename keeps metadata**; **remove keeps past top-ups**; **user isolation** (no list/rename/remove across users); **persistence after restart**; **no full number or CVV stored** (all four tables) |
| `CardTopUpIntegrationTest` | 11 | MySQL | **saved-card top-up** and **new-card top-up** (balance and record with snapshot); cash plus card share balance and history; **another user's card refused**; expired or removed cards refused; **invalid amounts**; **repeat and retry idempotency, even after card removal**; request id not reusable across types; **rollback on failure**; **30 concurrent cash/saved/new-card top-ups → exact total, 30 distinct running balances**; cash rows have no card data |
| `CardSchemaIntegrationTest` | 2 | MySQL | `payment_cards` rules; card-detail rules on transactions |
| `CardFlowsTest` | 13 | display | screens need a session; **no cards → form → amount → review → confirm → Save Card** (nickname required); **Not Now**; **save failure doesn't reverse or repeat the top-up**; saved-card selection shows nickname, brand, masked number, expiry and cardholder; Use a New Card and back; expired card disabled; **double-click submits once, retry reuses the request id**; **account switch during card loading never shows the previous user's cards**; Add Card needs a nickname; Add Card database failure clears sensitive fields; rename; remove with confirmation. **No number or CVV visible on screen after submission** (whole-window text scan) |
| `PaymentCardServiceConnectionFailureTest` | 1 | — | outage message |

**Existing tests changed** (intended behaviour changes only):
- `CashTopUpFlowTest`: the P01 assertion "Credit Card Top-Up is a Coming Soon label" became
  "Credit Card Top-Up is a button", since P02 enables it. The test that Send Money isn't clickable
  is kept.
- `TopUpServiceIntegrationTest`: the rollback test's override of `insertTransaction` was updated
  to the hook's new signature, which carries card details. The test's logic is unchanged.
- `TestDatabase` and `SchemaConstraintsIntegrationTest`: also drop `payment_cards`.

**Regression:** all Alpha 1 tests (registration, authentication, lockout, dashboard, isolation,
migrations) and all P01 tests (cash top-ups, history, concurrency) pass unchanged apart from the
items above.

### 7. Manual verification (Linux, real app, real MySQL, two launches)

The walkthrough was scripted to drive the real controls and checked the database directly. The
script is not committed.

| Step | Result |
|---|---|
| Launch on the real `lamma` database (P01 data, version 3) | ✅ upgraded to version 4; Lina's $130.50 preserved |
| Payment Methods with no cards | ✅ empty-state message |
| Card top-up with no saved cards | ✅ new-card form opened directly |
| Invalid card (bad checksum, `08/20`, 2-digit CVV) | ✅ three clear errors at once |
| Visa `4242…4242`, $50 | ✅ review "Paying with new card Visa •••• 4242 · Lina Haddad", simulated notice; success "New balance: $180.50" plus save prompt |
| Save Card → "Whish Card" | ✅ "Card saved"; dashboard $180.50, activity "Card Top-Up · Visa •••• 4242" |
| Card top-up again | ✅ **Choose a Card** shows Whish Card with cardholder, masked number and expiry |
| Use a New Card (Mastercard), $20 → **Not Now** | ✅ $200.50; card not saved |
| Add Card (Amex, "BLOM Card"); rename Whish → "Personal Visa"; remove BLOM | ✅ each confirmed on screen and in the database |
| Second user (Rami) | ✅ Payment Methods empty; card top-up opens the new-card form (no access to Lina's cards) |
| **Restart** | ✅ sign-in required; $200.50 and "Personal Visa" persisted |
| Saved-card top-up with **MySQL stopped at Confirm** | ✅ friendly error, balance unchanged; after restarting MySQL the retry gave "New balance: $210.50", a single credit |
| Database afterwards | ✅ `payment_cards` = one row (nickname, holder, VISA, 4242, 8/2029); card transactions show brand and last four only; Lina's balance $210.50 = sum of her transactions |
| Sensitive-data scan of database dump and console logs | ✅ nothing found (§4) |
| Visual check | ⚠️→✅ found and fixed two layout defects (§8) |

### 8. Known issues and assumptions

**Fixed during P02, found by screenshot:**
- **Dashboard action row:** the third button made all three labels truncate ("Receive Mo…",
  "Payment Meth…"). The actions now get equal widths, and the Send Money badge sits under its
  title.
- **Choose a Card:** each card button measured about 600 px tall, giving a huge white box and a
  truncated hint. Measuring the screen showed the cause: wrapping labels inside a button's graphic
  are measured at a tiny width. The summary lines no longer wrap, cards are now 77 px tall, and
  long hints wrap.

**Open:**
- **macOS not verified.** The README checklist now expects `Tests run: 206 … Skipped: 0` and
  includes a card step.
- **Account-switch tests:** each navigation builds a new screen, which is the primary protection
  against showing another user's data. The "still current" guard is a second layer. The
  account-switch UI test checks the visible outcome, which the first layer already guarantees
  (the same situation documented in P03).
- **Card expiry** is checked against the computer's clock in the form and against UTC in the
  service. At a month boundary these could differ by a few hours.
- **Saved-card selection** has no search or sorting; cards are listed in the order they were
  added.

**Assumptions:**
- Card top-ups follow the cash limits ($0.01–$10,000.00 per top-up, no daily limit).
- Expired saved cards stay listed, marked "Expired", and can't be used. They aren't removed
  automatically.
- Removing a card deletes its row; there is no soft delete. History keeps its own snapshot.
- Two different real cards with the same brand, last four and expiry can't both be saved by one
  user. This is rare, and the user gets a clear message.
- Cardholder names allow letters, spaces, `' . -` (any language), but not digits.

### 9. Git branch and commit

| Item | Value |
|---|---|
| Branch | `alpha2-development` (pushed, upstream `origin/alpha2-development`) |
| Previous commit | `442ccf9` (P01) |
| P02 commit | the commit that adds this section (hash given in the completion message) |
| `main` | unchanged at `b9962d9`; nothing merged |

### 10. Remaining work and notes for P03

These are notes, not a plan.

- **Transfers:** the model already has `direction` (DEBIT/CREDIT) and a widenable type list. A
  transfer would add types such as `TRANSFER_OUT` and `TRANSFER_IN` in V005, and probably a link
  between the two rows (e.g. a shared transfer id or counterparty wallet).
- **Two-wallet locking:** lock both wallets with `FOR UPDATE` in a fixed order (e.g. by
  `wallet_id`) to avoid deadlocks, and re-check the sender's balance inside the transaction. The
  `balance >= 0` constraint already blocks overdrafts.
- **Idempotency:** the existing `request_id` uniqueness applies per row. A transfer writes two
  rows, so it needs one shared idempotency key (e.g. on the DEBIT row) or a separate column.
- **Recipient by phone:** reuse `PhoneNumbers.normalize`. Decide how much to reveal about whether
  a number has an account; the same trade-off exists in registration.
- **History:** the activity list already shows red `-$` amounts for debits.
- **Test helper drop lists:** add any new tables to `TestDatabase` and
  `SchemaConstraintsIntegrationTest`, child tables first.
- **Still pending:** macOS verification, and the P01 decisions (top-up limit, branch/database
  compatibility, no cashier).

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
