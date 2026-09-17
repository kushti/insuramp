# Courier App — Implementation Spec

*Implementation spec for the courier side of the vault-insured cash→USDT on-ramp. Depends on: `onramp-ux.md` §3 (product requirements for this app), `onramp-insurance.md` §2–§3.1 (vault skeleton and the USDT leg), the canonical deal state machine in `specs/deal-protocol.md` §1, and the wire formats in `specs/deal-protocol.md` §3. First asset leg only: cash→USDT on-ramp, USE collateral, phase-1 NFT-authenticated oracle (`specs/oracle-integration.md`). Companion specs: `specs/android-app.md` (user app, shares the `:core:dealprotocol` module), `specs/operator-backend.md` (the only network peer this app has). The reverse direction (USDT→cash) is out of scope and not specified.*

## 1. Scope & role

The courier is the operator's physical-world actor: the person who meets the customer, counts and collects the cash, and carries it away. On this product the courier app is a **task tool and a signer, not a wallet**. Its entire job surface:

1. Show today's assigned pickups (route list).
2. At each meeting: display the agreed cash amount, receive and count the cash, sign the courier-signed handoff record — **only after the count** — and show the signed record to the customer, whose app verifies it before anyone leaves.

Everything else — vault funding, USDT payment verification, collateral release/reclaim transactions, disputes, collateral accounting — belongs to the operator backend (`specs/operator-backend.md`) and the vault contract (`specs/vault-contract.md`). The courier app has no chain connectivity: it cannot build, sign, or broadcast any on-chain transaction, and its signing key cannot move value (it signs the cash-collection record and nothing else).

**Platform.** Native Android, Kotlin, Jetpack Compose. Shared code with the user app lives in the `:core:dealprotocol` Gradle module (deal state machine, message/signature serialization, QR payload parsing — all defined in `specs/deal-protocol.md`). App-specific code (`:courier-app`) holds only UI, local queue persistence, the courier key storage, and the backend client. Minimum SDK target is chosen so that cheap, older Android phones — the realistic courier hardware — are supported; there is no hard dependency on Google Play services (GPS soft-check must work with the fused provider falling back to raw location).

## 2. Security invariants

This is the section that constrains everything else. If a requirement elsewhere in this spec conflicts with one of these invariants, the invariant wins.

### 2.1 The app holds no funds and no vault keys

- No private key that can spend a vault box, move USE collateral, or sign an Ergo transaction is ever generated, imported, or stored on the courier device.
- The courier app cannot construct, sign, or broadcast any on-chain transaction. It never sees the vault's ErgoScript, its box id beyond a deal reference, or the seller's wallet.
- **New versus the previously documented product: the app holds a deal-scoped courier signing key** (`courierPubKey`, `specs/deal-protocol.md` §3.1). It is issued by the operator backend at dispatch, generated per deal, and exists solely to sign the handoff record (`"P2PH"`, `specs/deal-protocol.md` §3.2) — the single signature that proves cash collection. It is pinned in the deal terms at funding and in the vault's R7 (R7 packs `oracleNftId || courierPubKey`; v2 contract rework landed 2026-09-13). It signs nothing else: no Ergo transactions, no value movement.
- Consequence: **a stolen courier phone can move no funds.** Precisely, in v2 the stolen phone's only cryptographic power is minting **false cash-collection records** — and the claim such a record unlocks pays the deal's *user* address, not the courier (`specs/deal-protocol.md` §2, courier-key bullet). It **cannot release collateral**: release is gated on the oracle's attestation alone and pays the seller. A rogue courier signing records for cash never collected is an operational-security matter (GPS, dual-control, vetting), the same trust the cash leg itself already carries — it is not a fund-theft path.

### 2.2 Courier authentication is deal-scoped

- The courier authenticates to the operator backend with **deal-scoped credentials**: at dispatch time the backend issues a short-lived bearer token bound to (courier device key, deal id, expiry). The token authorizes exactly the courier calls for that deal: fetch the deal payload, submit the handoff record, report the GPS confirmation, sync the offline queue. It is minted at dispatch and expires at deal close (RELEASED or any terminal state) — and in any case no later than the vault timeout window (`RECLAIM_TIMEOUT`, `specs/vault-contract.md`) plus a small grace margin.
- The **deal-scoped courier signing key** travels inside the same deal-scoped channel: the backend generates the keypair when the deal is created (so `courierPubKey` can be pinned in the deal terms at QUOTED) and exposes the credential at dispatch via `GET /v1/courier/jobs/{dealId}/key`, which returns the deal id, courier id, and `courierPubKey` (landed M3-B). Delivery of the private half to the assigned courier's device — encrypted to the courier's device key — is a courier-app concern on the app side of that endpoint; the key is discarded from the device when the deal closes.
- There is **no global courier account with fund access**. A courier-level login exists only to fetch the day's route list, and it is issued and revoked by the operator's own staff tooling, outside this spec's scope. Compromise of one courier's login yields read access to one courier's assignments — no fund movement, no other couriers' deals.
- The device key is a per-device Ed25519 keypair generated on first launch, stored in the Android Keystore where hardware support exists. It identifies the device to the backend and decrypts delivered deal keys; it signs nothing in the protocol.

### 2.3 What the app can and cannot do

| Can | Cannot |
|---|---|
| Fetch today's assigned deals (id, amount, neighborhood, status, meeting point) | Fetch other days, other couriers, or any historical/aggregate data |
| Render the handoff-record QR (`p2pgate://handoff?m=...`, `specs/deal-protocol.md` §3.3) | Alter the record contents — any tampering invalidates the courier signature against the fixed expected message |
| Sign the handoff record — only after physically counting the cash (§3.2) | Sign before the count — signing before collection would hand the customer a false artifact, so "count the cash first" is protocol-level, not visual |
| Collect the cash at the meeting and submit the signed record to the backend | Submit anything else to the vault, or sign anything other than the handoff record |
| Trigger a panic/dispute signal back to the operator | Open or resolve a claim on-chain; sign anything that moves collateral |

The app treats the payloads it relays as opaque bytes. It verifies nothing cryptographically beyond QR payload parsing; the courier's Schnorr signature on the handoff record is verified in-script by vault path B at claim time (`specs/vault-contract.md` §5 — v2 rework landed 2026-09-13), and the release paths need no courier involvement at all (oracle digest alone).

## 3. Screens & flows

Design rules carried over from `onramp-ux.md` §3: **one job per screen, operable one-handed, offline-tolerant.** Large touch targets, bottom-anchored primary buttons, no gestures that require a second hand. Total screen count is four (route list, handoff, help/safety, sync status); no navigation depth beyond two levels.

### 3.1 Route list

```
┌─────────────────────────┐
│ Today — 4 pickups        │
│                          │
│ ┌─────────────────────┐ │
│ │ #A3F9   ₤15,600     │ │
│ │ Nasr City           │ │
│ │ [ READY FOR PICKUP ]│ │
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │ #B210   ₤8,200      │ │
│ │ Heliopolis          │ │
│ │ [ AWAITING PAYMENT ]│ │
│ └─────────────────────┘ │
│ ...                      │
└─────────────────────────┘
```

- One card per deal: **deal id, cash amount, neighborhood, status pill.** Nothing else — no customer name, no crypto-side detail, no running totals.
- Status pill maps 1:1 to the canonical deal state machine (`specs/deal-protocol.md` §1): QUOTED → FUNDED → PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED, with the timeout branch (RECLAIMED), quote expiry (`QuoteExpired` — closes an unfunded deal), and the dispute branch (CLAIM_OPENED → CLAIMABLE → CLAIMED) rendered as terminal red pills. The courier receives deals at FUNDED ("ready for pickup") in practice; PAYMENT_PENDING renders as "cash collected — USDT pending", PAYMENT_CONFIRMED as "USDT confirmed — release pending". Earlier states appear only as "not yet dispatchable" placeholders if the operator pre-assigns a route.
- Ordering: by operator-assigned sequence, not by the courier. No manual reordering in v1.
- Pull-to-refresh; last-sync timestamp shown. Works fully offline against the last snapshot (see §4).

### 3.2 Handoff screen

The core screen, opened from a route card at FUNDED.

```
┌─────────────────────────┐
│ Deal #A3F9               │
│                          │
│   COLLECT                │
│   ₤ 15,600               │
│                          │
│ ● Location check: OK     │
│   (or: [Override — why?])│
│                          │
│ ┌─────────────────────┐ │
│ │ 1. [Receive & count │ │
│ │     cash]           │ │
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │ 2. [Sign handoff    │ │
│ │     record]         │ │
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │ 3. [Show signed     │ │
│ │     record QR]      │ │
│ └─────────────────────┘ │
│ (customer verifying...)  │
│ [Problem? Get help]      │
└─────────────────────────┘
```

Strict sequencing, enforced in UI state (the sequencing itself is protocol-level, not just visual):

1. **Cash amount large at top.** The agreed figure from the deal payload; the courier receives the cash from the customer and counts it physically — the app's job is to keep the number unambiguous. Step 1 is the courier's attestation that the count happened; it gates step 2.
2. **Courier signs the handoff record.** Enabled only after step 1. The record is the 84-byte `"P2PH"` cash-collection message (`specs/deal-protocol.md` §3.2); the signature is a Schnorr signature under the deal-scoped `courierPubKey` (scheme: `specs/vault-contract.md` §5). The courier signs **only after physically counting the cash** — signing before collection would hand the customer a false artifact, so "count the cash first" is the courier-side sequencing rule, and it is the only signature in the whole handoff.
3. **"Show signed record QR" flow.** `p2pgate://handoff?m=<base64url(handoff record incl. signature)>` (`specs/deal-protocol.md` §3.3). Either direction works: the courier shows the QR for the customer to scan, or the customer's app reads it over the device-to-device transport (`:core:dealprotocol`). The customer app validates amount/currency against the deal terms and verifies the courier's signature against the pinned `courierPubKey`. After the exchange both apps display the record in plain language for verbal confirmation — *"Deal #A3F9, ₤15,600, 14:32 — cash collected"* — matching the user app's rendering.
4. **The customer leaves only with the verified record.** The customer-side rule is "don't leave the meeting without the record" (`onramp-ux.md` §2.3); the courier-side mirror is "the meeting isn't done until the customer has verified it". A courier who pockets the cash and refuses to sign leaves the customer without an artifact and is operationally treated as courier theft — GPS, dual-control, vetting/bonding, operator investigation — the same robbery class as any refusal-to-co-sign scheme, mitigated operationally rather than cryptographically. A customer who walked away without the record has no claim path; a courier who signed but never got cash has handed over nothing.
5. **Submit the record.** The signed record goes to the operator backend with the GPS/override record (deal-scoped token, §2.2). The backend marks the deal PAYMENT_PENDING (event `CashCollected`) — cash collected, seller obligated to send USDT — and files the record as the customer's claim evidence if the seller never pays. The courier's on-chain-facing job ends here.

### 3.3 GPS soft-check

- Before step 2 completes, the app compares device GPS against the deal's meeting-point coordinates. Both parties within a few hundred meters → check passes silently.
- **Soft check, overridable with a reason code.** GPS dies indoors; the check must never block a legitimate handoff. On failure the courier picks from a fixed reason list (indoors/no signal, meeting point shifted, GPS hardware fault) — free text is not allowed. The override is attached to the handoff record and visible to the operator, so override rate is an auditable per-courier signal.
- The user's coarse location (shared by the user app at the meeting, per `specs/deal-protocol.md`) is the second factor; GPS-denied on one side with GPS-OK on the other still passes. GPS-denied on both sides requires the reason code.
- No continuous tracking: location is sampled only at handoff, never in the background.

## 4. Offline mode

Couriers work in basements, metro stations, and dead zones. Offline is a first-class mode, not an error state — but the on-ramp adds a hard freshness constraint (below).

- **Queue locally, sync when connected.** The signed handoff record and the GPS/override record persist in an on-device queue (Room database, encrypted at rest with the device key) and sync in order when connectivity returns. The sync status screen shows per-deal pending/acked state.
- **Freshness is the tight constraint.** The handoff record carries its own timestamp, and the vault's claim path (path B) checks it against `HANDOFF_RECORD_MAX_AGE` at *claim* time — a stale record cannot be replayed into a later dispute (`specs/vault-contract.md` §3.3). Operationally: if the seller has not sent the USDT and the customer is to claim, the claim transaction must land within the freshness window of the record's timestamp. The app therefore treats an unsynced record approaching the freshness window as a loud warning (and the deal record as a priority sync), not a silent queue entry.
- **Conflict/ordering rules when a deal times out while the courier is offline:**
  - If `RECLAIM_TIMEOUT` expires and the backend's auto-reclaim settles the deal *before* the queued record syncs, the late handoff submission is rejected by the backend with a terminal "deal closed" status. This is a real failure mode, not a hypothetical: on-chain, a FUNDED box past timeout is indistinguishable from a no-show, so the backend's reclaim job can only exclude deals it *knows* reached PAYMENT_PENDING — knowledge that arrives with the sync. The courier app renders the closed red card; there is no retry and no local override. The ground truth is the chain state as reported by the backend, never the courier's local queue.
  - Queue entries for terminal-state deals are marked dead and purged after operator-defined retention; they are evidence records, not retryable work.
- Route list works offline from the last snapshot with a visible staleness indicator; new deal assignments obviously require connectivity.

## 5. Chain/backend interaction

- **The courier app talks to the operator backend (`specs/operator-backend.md`), and only to it.** It has no Ergo node connection, no explorer API calls, no Rosen endpoints. Chain state (deal states, vault status, release confirmation) reaches the app exclusively as backend-rendered deal records.
- **The backend builds and broadcasts.** Collateral release: the operator backend assembles the release transaction (paths C/C′ — the oracle digest of the seller's USDT transfer, alone) and broadcasts it. Reclaims, dispute responses, claim handling: entirely backend/operator dashboard, invisible to the courier except as status pills.
- **API surface (all calls under the deal-scoped token from §2.2, plus the route-list login; paths per `specs/operator-backend.md` §9):**
  - `GET  /v1/courier/jobs` → assigned deals: deal id, cash amount, neighborhood, meeting-point coordinates, status, sequence.
  - `GET  /v1/courier/jobs/{dealId}/key` → the deal-scoped courier credential at dispatch: `{dealId, courierId, courierPubKey}` (§2.2 — private-half delivery to the device is the app-side concern).
  - `GET  /v1/courier/jobs/{dealId}/handoff` → the handoff-record template (the `"P2PH"` QR content).
  - `POST /v1/courier/jobs/{dealId}/handoff` → the signed handoff record, verbatim bytes, plus the GPS/override record.
  - `POST /v1/courier/jobs/{dealId}/panic` → safety trigger (§6).
  - `GET  /v1/deals/{id}/status` → poll/push for state transitions (deal-scoped token).
- **Data minimization.** The app sees *today's deals and nothing else*: no historical aggregates, no earnings dashboards, no customer history, no collateral pool information. This is deliberate — the courier phone is the most physically exposed device in the system, so it is also the most information-starved. Server-side, the backend enforces this scoping; the app merely benefits from it.

## 6. Safety features

The courier meets strangers and carries physical cash away from the meeting; safety is a protocol concern, not an app extra.

- **No phone numbers exchanged.** All identity between courier and customer is deal-scoped: the customer sees the courier's first name + a deal-scoped photo to verify in person (`onramp-ux.md` §2.2 state 3), and the courier verifies the customer via the QR exchange of the handoff record. Neither side learns a phone number, handle, or reusable identifier. Contact, if coordination is unavoidable, goes through the operator backend's relayed messaging — scoped to the deal, expiring with it.
- **Panic/dispute trigger.** A persistent [Problem? Get help] affordance on the handoff screen opens the safety screen with two big actions:
  - **Panic** — silent signal to the operator (`POST /v1/courier/jobs/{dealId}/panic`): deal flagged, operator's own escalation procedure takes over (that procedure is operator policy, out of scope here). Works offline as a queued payload, sent on first connectivity.
  - **Dispute** — hands the deal back to the operator's dispute inbox (`onramp-ux.md` §4). The courier app does not participate in dispute resolution; it reports and exits.
- **No cash-float tracking in-app.** The app never displays or records how much cash the courier carries in total — one deal amount at a time only. A stolen phone must not double as a robbery target list; this matters more on the on-ramp, where the courier leaves the meeting with the cash.
- **Meeting-point hygiene.** Exact meeting point is shown only while the deal is active (FUNDED through PAYMENT_PENDING); it disappears from the device when the deal closes.

## 7. Open questions

- **Record transport when both sides are offline.** The signed handoff record must move phone-to-phone (QR round-trip or Bluetooth/NFC handoff) if neither device has connectivity at the meeting — `:core:dealprotocol` needs to define that transport; this spec assumes the payload format but not the radio path. Note the sequencing consequence: with the courier offline, the signed record cannot reach the backend either, so a fully offline meeting still completes device-to-device (the customer verifies the signature locally against the pinned `courierPubKey`), but PAYMENT_PENDING only lands when one side syncs.
- **Freshness window vs offline meetings.** `HANDOFF_RECORD_MAX_AGE` binds the record timestamp at claim time, while `RECLAIM_TIMEOUT` sizes the deal window — a deal where the seller simply never pays must still be claimed inside the freshness window. Whether the claim UX should auto-file at window expiry (rather than waiting for an angry customer) is a product question for `onramp-ux.md` §6 and touches `specs/deal-protocol.md` §4.
- **Reason-code taxonomy for GPS overrides.** Fixed list proposed in §3.3; the operator dashboard's audit view may demand finer granularity. Needs a shared enum in `specs/deal-protocol.md`.
- **Panic semantics.** What the operator does on panic (call back? dispatch? notify?) is policy; whether the protocol needs an on-chain footprint of a panicked deal (e.g., blocking routine reclaim) is undecided and touches `specs/vault-contract.md`.
- **Courier bonding.** `onramp-business-model.md` §8 lists courier collusion as an operational, not cryptographic, risk — and the on-ramp's deal-scoped courier key makes rogue-courier record signing a concrete case (§2.1). Whether couriers post bonds, and whether the app surfaces bond status, is an operator-business decision deferred to `specs/operator-backend.md`.
- **Multi-deal batching.** v1 is one courier, one deal at a time, in operator-assigned order. Whether route optimization or concurrent active deals are ever allowed is a product question for a later iteration.

## 8. Cross-references

- Product requirements for this app: `onramp-ux.md` §3 (courier app), §2.3 (meeting sequencing), §2.2 state 3 (deal-scoped identity)
- Vault contract, deal state machine, timeouts & maturation: `specs/vault-contract.md`; contract design rationale: `onramp-insurance.md` §2, §3.1
- Wire formats (handoff record, QR payloads, signature serialization): `specs/deal-protocol.md` §3
- Shared module & user-side screens: `specs/android-app.md`
- Backend API, release-transaction construction, dispute inbox, courier credential issuance: `specs/operator-backend.md`
- Courier economics & collusion risk: `onramp-business-model.md` §1, §8
