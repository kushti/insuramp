# Seller Dashboard — Implementation Spec

*Implementation specification for the seller/operator-facing UI of the vault-insured
cash→USDT on-ramp: the meeting screen the seller runs at the in-person cash collection,
plus the operator dashboard front-end (vault lane, collateral pool, dispute inbox,
infrastructure status). Depends on: `specs/operator-backend.md` — the dashboard JSON API
and `/events` WebSocket this UI consumes (dashboard UI is explicitly out of that spec's
scope, its §2, so it gets this one); `specs/deal-protocol.md` (canonical state names §1,
handoff record §3.2, QR payloads §3.3); `specs/vault-contract.md` (timings: `RECLAIM_TIMEOUT`
24h, `CLAIM_MATURATION` 12h); `specs/oracle-integration.md` (the phase-1 oracle whose
attestation alone gates release — the oracle is trusted, period, on the release path).
Product context: `onramp-ux.md` §3 (seller flow), §4 (operator dashboard). Direction:
cash→USDT only; the reverse direction is out of scope and not specified.*

**Implementation status (M4, 2026-09-17):** in progress. The UIs are built on the demo
backend; the two handoff endpoints in §4 are treated as landed, everything else consumes
the dashboard API sketched in `specs/operator-backend.md` §9.

## 1. Scope & role

There is no separate seller consumer app: the seller **is** the operator side, and the
seller UI is the operator dashboard front-end (`onramp-ux.md` §3). Shape:

- **Static vanilla JS/CSS app**, no framework and no build step, served by the Ktor
  backend itself (same deployable). A pure consumer of the dashboard JSON API plus the
  `/events` WebSocket — it holds no keys, builds no transactions, invents no state.
- **Server-side QR rendering.** The meeting QR is a server-rendered PNG (§4); the
  front-end embeds no JS QR library — the phone at the meeting shows a plain `<img>`.
- **Live by WebSocket, not polling-first.** Cards, lane moves, and infra status update
  from `/events`; a slow polling fallback keeps the lane fresh if the socket drops.

The dashboard's two jobs, per `onramp-ux.md` §4: capital efficiency (see the money) and
dispute hygiene (never miss a claim deadline). The meeting screen is where the protocol's
one hard sequencing rule is enforced by UI.

## 2. Auth

- **Login screen**, nothing else visible without a token. Auth is the operator **bearer
  token** from `TokenService` (`specs/operator-backend.md` §9): long-lived, scoped to
  dashboard endpoints only, held in session storage.
- The token authorizes *actions*, not keys: vault-signing keys never leave the vault
  manager — the sign endpoint (§4) Schnorr-signs server-side with the deal's seller key,
  the dashboard never sees it. A leaked token is a deal-integrity emergency, not a key
  compromise; revoke and re-issue.

## 3. Screens

### 3.1 Login

Token field, "Open dashboard". Failed auth shows the backend's error verbatim — no custom
error vocabulary (the API exposes canonical state names; the UI invents no synonyms,
`specs/operator-backend.md` §9).

### 3.2 Vault lane

Kanban of deal cards by canonical state (`specs/deal-protocol.md` §1): `QUOTED`,
`FUNDED`, `PAYMENT_PENDING`, `PAYMENT_CONFIRMED`, `CLAIM_OPENED` / `CLAIMABLE`, and a
terminal `RELEASED` / `RECLAIMED` / `CLAIMED` column. Each card shows exactly what
`specs/operator-backend.md` §3 prescribes: **locked collateral amount**, **timeout
countdown**, **the exit path currently available**. Card actions: open the meeting screen
(`FUNDED` — or `PAYMENT_PENDING`, to re-show the QR), manual reclaim where the backend
allows it (`POST /vaults/{id}/reclaim`; from `PAYMENT_PENDING` the button is absent, not
disabled — reclaim from there is a theft path the backend rejects). A card with no exit
and no countdown is a bug indicator, not a normal state.

### 3.3 The meeting screen — the one hard moment

One job per screen, operable one-handed, high sunlight contrast — this runs on a phone
held over a table of banknotes (`onramp-ux.md` §3):

1. **Header: the deal** — the deal's locked collateral amount (large) and short deal id;
   nothing else. (The lane DTO carries no fiat fields — the agreed fiat figure lives in
   the deal terms the buyer holds; the meeting is convened from those terms off-app.)
2. **Counted-cash gate.** "Sign handoff record" stays inert until the seller confirms the
   physical count — a deliberate explicit confirm: count, then sign. The invariant is on
   the screen itself: **signing before the cash is counted hands the buyer a payout key
   against the seller's own collateral.** The record is seller-signed under the vault's
   R5 key — the same key that reclaims the collateral — and it alone moves the vault to
   PAYMENT_PROVEN and opens the buyer's claim path (path B, `specs/deal-protocol.md` §1);
   once signed, the artifact is final.
3. **"Show handoff QR"** — calls the sign endpoint (§4), then displays the server-rendered
   `qr.png`: the `p2pgate://handoff?m=...` payload (`specs/deal-protocol.md` §3.3). The
   buyer's app validates the record against its deal terms and shows its own "safe to
   leave" indicator; the meeting ends only when it does.
4. **Post-sign state.** The screen switches to *"record signed — the buyer verifies"*
   and nothing else: no re-sign, no "close deal" while the buyer's app is mid-verification.
   If the deal is already `PAYMENT_PENDING`, the screen skips step 2 and shows this
   state directly.
5. **No-show close.** If the buyer never appears, the seller closes the card from the
   lane; the vault simply times out (`RECLAIM_TIMEOUT`, 24h) and the automated reclaim
   job takes it from there — the meeting screen takes no further part.

### 3.4 Collateral pool

The pool view (`GET /pool`): USE balance, locked vs free, utilization %, mix-ready balance
tracked separately from total (mixer latency is real, `specs/operator-backend.md` §4).
The DTO exposes the numbers only — nudges like *"capital idle 38% — 2 expired vaults
awaiting reclaim"* are an operator-dashboard future, computed client-side from the lane.
Per the privacy-funding rule, no consolidated operator-wallet graph: amounts, not addresses.

### 3.5 Dispute inbox

Claim rows (`GET /disputes`), sorted by maturation countdown — the deadline is the sort
key. The evidence view juxtaposes the two artifacts (`specs/operator-backend.md` §7): the
seller-signed handoff record (cash receipt acknowledged under the same key that reclaims
the collateral) versus the oracle's payment attestation (did the seller's USDT transfer
confirm?). Exactly three actions (`POST /disputes/{id}/{action}` — the action names are
the API's):

- **Contest** (`contest`) — present the oracle attestation **alone** (path C′): mechanical whenever
  the attestation exists; an honest seller always counters a false claim. The UI copy is
  honest about why it is one click: *the phase-1 oracle's attestation alone releases the
  vault — it is trusted, period, on the release path.*
- **Accept** (`accept`) — concede the claim; the vault pays the buyer path.
- **Investigate** (`investigate`) — route to manual review (key-compromise class; changes
  nothing on-chain).
- *Wait-for-timeout* is not an API action: it is taking no action and letting the clock
  run; the row stays red.

Claims nearing maturation unactioned render fail-loud (banner + row flash), matching the
backend's rule: a matured-unactioned claim is collateral donated.

### 3.6 Infrastructure status

`GET /infra` as health rows: oracle lag, explorer/node sync, wallet daemon, AML scorer
reachability, pause state. When the oracle leg is degraded the banner says why quotes are
withdrawn, quoting the rule verbatim — **never sell insurance you can't currently verify**
(`onramp-ux.md` §4). Every paused hour is idle capital, so the screen also carries the
pause-duration stats.

## 4. Handoff signing endpoints

Operator-token authed; the dashboard calls these, the buyer only ever sees the QR PNG.
The record is the 52-byte `P2PH` handoff record (`specs/deal-protocol.md` §3.2) built
from the deal terms + server clock, Schnorr-signed under the deal's seller key (vault R5).

```
POST /dashboard/deals/{id}/handoff/sign        # operator bearer token; body: {}
  200 → { dealId, state: "PAYMENT_PENDING", recordHex: <hex, 52-byte record>,
          signatureA, signatureZ: <hex, Schnorr half under the deal's seller key (R5)>,
          sellerPubKey: <hex, compressed secp256k1>, qrPayload: "p2pgate://handoff?m=..." }
  409 → { error }        # message carries the deal's current state

GET /dashboard/deals/{id}/handoff/qr.png       # operator bearer token
  200 → image/png               # QR of p2pgate://handoff?m=<base64url(record)>
  409 → { error }               # record not signed yet (deal exists, record doesn't)
  404 → { error }               # deal unknown
```

(Actual route shape: the two handoff endpoints are `/v1/dashboard/deals/{id}/handoff/…`;
every other dashboard endpoint is flat under the version prefix — `/v1/lane`, `/v1/pool`,
`/v1/disputes`, `/v1/infra`, WS `/v1/events`.)

**State gate.** Signing is accepted only in the meeting window: a `FUNDED` deal signs and
the sign drives the `CashCollected` transition to `PAYMENT_PENDING`; a deal already in
`PAYMENT_PENDING` answers 409 with its state (the record exists — never re-sign); every
other state is rejected the same way. Invariant: *a signed record exists exactly when the
deal is `PAYMENT_PENDING`.* The QR endpoint follows the record (409 until signed, 404 for
an unknown deal), and the 409 bodies always name the deal's actual state in the message so
the meeting screen can render "already signed / wrong lane" instead of a bare failure.

## 5. Non-goals

- **No buyer-facing functionality.** No quote browsing, no deal creation, no claim
  buttons — that is the buyer app (`specs/android-app.md`).
- **No off-chain chat** between buyer and seller: the deal link and the meeting are the
  only rendezvous; the dashboard stores no messages.
- **No push notifications.** Dashboard freshness is `/events` WebSocket plus a polling
  fallback only.
- **No keys in the front-end.** Signing happens server-side (§4); a future richer
  dashboard still never ships vault-signing keys to a browser.

## Cross-references

- Dashboard JSON API + WebSocket (consumed here; UI scope deliberately excluded there): `specs/operator-backend.md`
- Canonical deal states, handoff record, QR payloads: `specs/deal-protocol.md` (§1, §3.2, §3.3)
- Vault timings (`RECLAIM_TIMEOUT` 24h, `CLAIM_MATURATION` 12h): `specs/vault-contract.md` (§2)
- Payment-proof oracle (trusted, period, on the release path): `specs/oracle-integration.md`
- Seller flow & dashboard product design: `onramp-ux.md` §3, §4
- Buyer app (the other UI; explicitly not this spec): `specs/android-app.md`
- Capital dynamics that make the pool view matter: `onramp-business-model.md` §4
