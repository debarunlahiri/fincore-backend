# Banking features: payees, OTP, deposits, loans, cards, security

All paths go through the gateway (`http://localhost:9780`). Unless noted, they need a **CUSTOMER** token
(`Authorization: Bearer <accessToken>`). Money is INR.

## Identifiers: what a customer sees

The API never returns internal database ids. Customers see only:

| Thing | Public identifier | Example |
| --- | --- | --- |
| Account | account number | `FC1000000001` |
| Transfer / statement line | reference (same on both statements) | `TXN2611278F3K2Q9A` |
| Payment | reference | `PAY261127K7M2XQ4D` |
| Standing instruction | reference | `SIN2611272PQ8WTNB` |
| Fixed deposit | reference | `FDR261127H4RT9ZYA` |
| Loan | reference | `LON261127M3VCEX8K` |
| Card | reference (+ masked number) | `CRD261127B6NQ2WLP` |
| One-time password challenge | reference | `OTP2611279YHDKR3F` |
| Payee | account number | `FC1000000002` |

References are `PREFIX + yyMMdd + 8 random characters` (no I, O, 0 or 1). Lookups of another customer's reference
return `404`, not `403`.

## One-time passwords (OTP)

Sensitive actions need a 6-digit code (simulated SMS). Flow: ask for a challenge, then send the code with the action.
A code is bound to one user, one purpose and the exact action (a transfer's source, target and amount), is valid for
5 minutes, works once, and locks after 5 wrong guesses.

In development the response contains `devCode` so you can test without an SMS gateway. Set `OTP_EXPOSE_CODE=false`
for anything real.

```json
{ "challengeReference": "OTP2611279YHDKR3F", "expiresAt": "2026-11-27T10:05:00Z", "devCode": "483920" }
```

| Error code | Meaning |
| --- | --- |
| `OTP_REQUIRED` (428) | the action needs a code and none was sent |
| `OTP_INVALID` (400) | wrong, already used, or issued for a different action |
| `OTP_EXPIRED` (400) | request a new one |
| `OTP_LOCKED` (400) | too many wrong attempts |

## Payees

Three kinds of saved payee:

| `type` | Needs | Used for |
| --- | --- | --- |
| `UPI` | `upiId` (`name@bank`) | UPI payments (`/api/v1/payments`, rail UPI) |
| `BANK` | `accountNumber` (9 to 18 digits) and `ifsc` (`HDFC0001234`) | IMPS / NEFT payments |
| `FINCORE` | `accountNumber` (another customer's FinCore account) | transfers (`/api/v1/transfers`) |

Adding a payee needs a one-time password bound to that exact payee, and the payee becomes usable after a cooling period
(`fincore.beneficiary.cooling-period`, default 1 minute; real banks use 30 minutes to 24 hours). Details are validated
first, so a typo is reported before any OTP is sent. The bank name is derived from the IFSC (or the UPI handle) using a
small built-in directory that stands in for the RBI IFSC register and the NPCI handle list.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/v1/beneficiaries/ifsc/{code}` | `{ ifsc, valid, bankName }`; `bankName` is null for a well-formed code the directory does not know |
| `POST` | `/api/v1/beneficiaries/otp` | body `{ type, accountNumber?, ifsc?, upiId? }` returns a challenge |
| `POST` | `/api/v1/beneficiaries` | body `{ type, name, nickname?, accountNumber?, ifsc?, upiId?, otpChallenge, otpCode }`, `201` |
| `GET` | `/api/v1/beneficiaries` | each has `reference` (`BEN...`), `type`, `bankName`, `state` (`PENDING` or `ACTIVE`) and `activeFrom` |
| `DELETE` | `/api/v1/beneficiaries/{reference}` | `204` |

Response example:

```json
{ "reference": "BEN261127K3M9XQ2P", "type": "BANK", "name": "Asha Traders", "nickname": null,
  "accountNumber": "123456789012", "ifsc": "HDFC0001234", "bankName": "HDFC Bank", "upiId": null,
  "state": "ACTIVE", "activeFrom": "2026-11-27T10:01:00Z", "createdAt": "2026-11-27T10:00:00Z" }
```

Errors: `INVALID_UPI_ID`, `INVALID_IFSC`, `INVALID_ACCOUNT_NUMBER`, `MISSING_*` (400), `OWN_ACCOUNT` (400, you do not need a
payee for your own account), `BENEFICIARY_EXISTS` (409), `BENEFICIARY_LIMIT` (422, max 25), `NOT_FOUND`.
Only `FINCORE` payees can be the target of `/api/v1/transfers`.

## Transfers

`POST /api/v1/transfers` (headers `Idempotency-Key`, and `X-Otp-Challenge` + `X-Otp-Code` when required)

```json
{ "sourceAccountNumber": "FC1000000001", "targetAccountNumber": "FC1000000002", "amount": 5000.00, "currency": "INR", "remarks": "rent" }
```

Rules, in order:

1. a single transfer may not exceed `per-transaction-limit` (default 2,00,000): `PER_TRANSACTION_LIMIT` (422)
2. the target must be one of your own accounts or a saved payee whose cooling period is over:
   `BENEFICIARY_REQUIRED` / `BENEFICIARY_COOLING` (422)
3. from `otp-threshold` (default 10,000) upwards an OTP is required: `OTP_REQUIRED` (428)
4. then the existing checks: fraud rules, daily limit, balance, currency, frozen accounts

Get the OTP for exactly this transfer with `POST /api/v1/transfers/otp` and the same body. A retry with the same
`Idempotency-Key` after success returns the stored receipt (`200` + `Idempotent-Replayed: true`) without a new code.

Response (receipt):

```json
{ "reference": "TXN2611278F3K2Q9A", "sourceAccountNumber": "FC1000000001", "targetAccountNumber": "FC1000000002",
  "amount": 5000.00, "currency": "INR", "status": "COMPLETED", "remarks": "rent", "createdAt": "2026-11-27T10:00:00Z" }
```

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/v1/transfers/{reference}` | receipt (only for transfers you sent) |
| `GET` | `/api/v1/transfers` | history of sent and received. Filters: `account`, `from`, `to` (dates), `minAmount`, `maxAmount`, `q` (reference or remarks), `page`, `size`. Each row has `direction`: `SENT` or `RECEIVED` |

## Statements

`GET /api/v1/accounts/{accountNumber}/statement?page=0&size=20` returns lines
`{ reference, description, direction, amount, balanceAfter, createdAt }`.
`GET /api/v1/accounts/{accountNumber}/statement.csv?from=YYYY-MM-DD&to=YYYY-MM-DD` (max one year) has the columns
`date,reference,description,direction,amount,currency,balance_after`.

Deposits and withdrawals: `POST /api/v1/accounts/{accountNumber}/deposits|withdrawals` returns
`{ "reference", "accountNumber", "balance" }`.

## Fixed deposits

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/v1/deposits/quote?principal=&tenureMonths=` | rate, maturity date, maturity amount |
| `POST` | `/api/v1/deposits` | `{ accountNumber, principal, tenureMonths }`, `201`. Debits the account |
| `GET` | `/api/v1/deposits` , `/{reference}` | status `ACTIVE`, `MATURED`, `CLOSED_EARLY` |
| `POST` | `/api/v1/deposits/{reference}/close` | early closure |

Principal 1,000 to 1,00,00,000, tenure 3 to 120 months. Rates: up to 6 months 6.00%, up to 12 months 6.75%,
up to 24 months 7.00%, up to 60 months 7.25%, longer 7.00% (annual, compounded quarterly). Early closure pays interest
at 1 percentage point less for the full months elapsed, and no interest before 3 months. A scheduled job credits
deposits that reach maturity (`fincore.deposit.maturity-cron`, default hourly).

## Loans

Types: `PERSONAL` 12.50% (up to 10,00,000, 6 to 60 months), `HOME` 8.75% (up to 1,00,00,000, 60 to 360),
`AUTO` 9.50% (up to 25,00,000, 12 to 84), `EDUCATION` 9.00% (up to 20,00,000, 12 to 120). Minimum 10,000.
Processing fee 1% (personal, auto) or 0.5% (home, education), deducted from the amount disbursed.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/v1/loans/quote?type=&principal=&tenureMonths=` | EMI, fee, total interest, total payable |
| `POST` | `/api/v1/loans` | `{ accountNumber, type, principal, tenureMonths, monthlyIncome }`. Instant decision: `APPROVED` or `REJECTED` (with `rejectionReason`) |
| `GET` | `/api/v1/loans`, `/{reference}` | detail includes the repayment `schedule` once disbursed |
| `POST` | `/api/v1/loans/{reference}/otp` | challenge for accepting |
| `POST` | `/api/v1/loans/{reference}/accept` | `{ otpChallenge, otpCode }`. Disburses to the account, status `ACTIVE` |
| `POST` | `/api/v1/loans/{reference}/decline` | before accepting |
| `POST` | `/api/v1/loans/{reference}/pay-emi` | pays the earliest unpaid instalment plus any late fee |
| `POST` | `/api/v1/loans/{reference}/foreclose` | outstanding principal + one month's interest + late fees |

Underwriting (simulated): KYC verified; total EMIs of active loans plus the new one at most 50% of declared monthly
income; at most 3 open loans. Instalments unpaid after their due date become `OVERDUE` with a one-time 2% late fee
(job `fincore.loan.overdue-cron`, daily). Statuses: `APPROVED`, `REJECTED`, `DECLINED`, `ACTIVE`, `CLOSED`, `FORECLOSED`.

## Cards

Debit cards on one of your accounts. The full number and CVV are returned **once**, at issue.

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/cards` | `{ accountNumber, kind: VIRTUAL or PHYSICAL }`, `201`. The name on the card is the customer's registered name (never supplied by the client). Returns `{ card, cardNumber, cvv }` |
| `GET` | `/api/v1/cards`, `/{reference}` | masked view (`**** **** **** 1234`) |
| `POST` | `/api/v1/cards/{reference}/block`, `/unblock`, `/close` | |
| `PUT` | `/api/v1/cards/{reference}/limits` | `{ dailyLimit (max 2,00,000), onlineEnabled, internationalEnabled }` |
| `POST` | `/api/v1/cards/{reference}/pin` | `{ pin }` 4 digits, `204` |
| `GET` | `/api/v1/cards/{reference}/transactions` | approved and declined attempts |
| `POST` | `/api/v1/cards/{reference}/purchase` | simulated merchant: `{ merchant, amount, channel: POS or ONLINE, international, pin?, cvv? }` |

A purchase is declined (`422 CARD_DECLINED`, reason in the message and in the history) for: blocked or expired card,
international use while off, online use while off, wrong CVV (online), PIN missing or wrong (POS), daily limit, or
`INSUFFICIENT_FUNDS`. Three wrong PINs block the card. Approved purchases post to the ledger and appear on the
statement with `statementReference`.

## Security (auth-service)

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/security/change-password` | `{ currentPassword, newPassword }`, `204`; signs out every device |
| `GET` | `/api/v1/security/login-history?limit=20` | `{ success, reason, ipAddress, userAgent, createdAt }` |
| `GET` | `/api/v1/security/sessions` | the active device |
| `DELETE` | `/api/v1/security/sessions` | sign out everywhere |

- **Lockout:** 5 wrong passwords lock the login for 15 minutes (`423 ACCOUNT_LOCKED`); a correct login resets the count.
- **One device at a time:** signing in on a new device ends the session on the old one (`fincore.security.single-session`,
  default `true`). The old device's refresh token stops working; its access token lapses within 5 minutes
  (`access-ttl`). The web app then returns to the sign-in page with an explanation.
