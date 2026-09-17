# Android App Implementation Spec — User-Facing Onramp App

*Implementation spec for the user-facing side of the vault-insured cash→USDT on-ramp. Direction: **cash→USDT only** — the user hands cash to the courier and receives USDT; the seller sends the USDT after collecting the cash and therefore locks the vault collateral. Turns the UX design in `onramp-ux.md` §2 into an engineering plan; depends on `onramp-insurance.md` (contract & trust model), `specs/vault-contract.md` (canonical timings: `RECLAIM_TIMEOUT` 24h, `CLAIM_MATURATION` 12h, protocol fee `feeBps` 25–100 bps), and `specs/deal-protocol.md` (state machine & wire formats). Payment proof via the phase-1 NFT-authenticated oracle (`specs/oracle-integration.md`). **Contract status:** the v2 contract rework has landed (2026-09-13): path B is gated on the single courier-signed handoff record (no oracle input), the release paths C/C′ are gated on the oracle's attestation alone — no receipt signature anywhere in the protocol. R7 packs `oracleNftId || courierPubKey` (unchanged), and `dealId` already bound `courierPubKey` via the deal terms, so terms were stable across the revision.*

## 1. Scope & target

**One app, one side of the marketplace.** This spec covers the *user* app only — the person buying USDT with cash. The other two sides are separate deliverables:

- Courier app → `specs/courier-app.md`
- Operator dashboard → `specs/operator-backend.md` (web frontend + backend services)

**Platform decision.** `onramp-ux.md` §2 describes a *mobile-first PWA*. The project owner has chosen **native Android as the primary target** instead. The tension is resolved by layering: everything protocol-shaped (state machine, wire formats, vault interaction, signature flow) lives in pure-Kotlin modules with no Android dependencies, so a later PWA can reuse the same code via Kotlin Multiplatform (Kotlin/JS). Only the UI shell (`:app`) is Android-specific. If the PWA path is ever activated, `:core:*` modules ship as-is and only a new UI module is written.

| Decision | Value | Rationale |
|---|---|---|
| Language | Kotlin everywhere | Owner decision; KMP-ready layering |
| UI toolkit | Jetpack Compose | Current Android standard, single-screen flow-heavy UI |
| Architecture | MVVM + unidirectional state flow | One timeline per deal maps naturally to a single state stream |
| minSdk | **26 (Android 8.0)** — proposal | Covers ~95%+ of devices in target markets (Egypt, Nigeria, Iran — older devices dominate); required for hardware-backed Keystore attestation APIs. Open question §8 if target markets demand lower |
| targetSdk | latest stable | Store policy |
| Package shape | single APK, no accounts, no sign-in | Privacy defaults (§5) |

Non-goals for v1: BTC and XMR legs (extension notes in `specs/vault-contract.md` §8.1–§8.2, deferred), iOS, in-app swap/DEX functionality, any custody of user funds.

## 2. Architecture

### 2.1 Module layout

```
:core:dealprotocol   pure Kotlin, zero Android deps   — landed (2026-09)
:core:ergo           pure Kotlin + ergo-appkit, zero Android deps — landed (2026-09-16, M2; operator-side txs M3-A)
:app                 Android (Compose UI, ViewModels, Keystore, WorkManager) — not started
```

**`:core:dealprotocol`** *(landed, 97 tests green)* — the deal state machine and all wire formats. Pure Kotlin (kotlinx.serialization), so it is the KMP-reusable core. Owns:

- The canonical deal state machine (exact state names):

  ```
  QUOTED → FUNDED → PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED
  timeout branch:      FUNDED → RECLAIMED                (user no-show)
  dispute branch:      PAYMENT_PENDING → CLAIM_OPENED → CLAIMABLE → CLAIMED
  ```

  Quote expiry (`QuoteExpired`) closes a deal that never funded — no on-chain footprint, no dedicated state. `PAYMENT_PENDING` = cash collected (courier-signed handoff record on file, event `CashCollected`); `PAYMENT_CONFIRMED` = the oracle confirmed the seller's USDT transfer (event `PaymentConfirmed`) and the release follows without any user action. On-chain vault box states mirror this: **FUNDED box → PAYMENT_PROVEN box → spent** (RELEASED or CLAIMED). The module's job is to map observed external evidence (handoff records, oracle confirmations, vault box state, courier messages) into transitions; it never performs I/O itself.

- Transition guard: every transition is a pure function `transition(state, evidence) → newState | Invalid`, where `Invalid` carries a user-safe explanation. This is where the sequencing rules are enforced (§3.3, §6): "partial payment forbidden", "no 'safe to leave' before the courier-signed handoff record is verified". A `PaymentConfirmed` arriving while a claim is open is **not rejected** — it sets the deal's contested flag, and the contract side answers it with path C′.
- Wire formats from `specs/deal-protocol.md`: quote payload, deal creation request, handoff record + courier-signed attestation (§3.2), claim metadata. All versioned; unknown fields ignored on read.
- Timings are referenced, not redefined: `RECLAIM_TIMEOUT` (24h), `CLAIM_MATURATION` (12h), `feeBps` (25–100 bps) — constants imported conceptually from `specs/vault-contract.md`; the app displays them but the contract is the source of truth.

**`:core:ergo`** *(landed, 2026-09-16, M2; extended with operator-side txs M3-A — 100 tests green)* — chain interaction, wrapping **ergo-appkit** (JVM Ergo tooling). Pure Kotlin/JVM; Android-injectable via interfaces. Owns:

- Vault box monitoring: given a vault box id / ErgoTree template, poll current box state (FUNDED box, PAYMENT_PROVEN box, spent) and map to deal-machine evidence — implemented as `VaultBoxTracker` (chain facts → `DealEvent`s).
- Transaction building for the two user-side on-chain paths:
  - **Claim transactions** (dispute path): claim-open (spends the FUNDED box via path B with the courier-signed handoff record) and claim payout (spends the PAYMENT_PROVEN box to the user's payout address after `CLAIM_MATURATION`) — see §4.3; implemented as `ClaimTxBuilder` (every built tx is prover-verified against the compiled vault scripts).
  - No release tx is ever built by this app: release pays the *seller's* vault address and is submitted by the seller side (`specs/operator-backend.md`), with the oracle digest alone. (The operator-side builders — fund/reclaim/release/contest — also live here as `OperatorTxBuilder`, used by the backend, M3-A.)
- Schnorr utilities for the handoff record (`P2PH`, `specs/deal-protocol.md` §3.2): **verification** of the courier's signature against the pinned `courierPubKey` at the meeting (the user signs no attestation in v2), using the ErgoScript-verifiable Schnorr scheme (ergoforum.org/t/3407; resolved — see §8.1). The variant and message encoding match `specs/vault-contract.md` §5: `e = blake2b256(a ‖ msg ‖ pubKey)` read as a signed two's-complement integer, `z = r + e·x mod n` ground to ≤254 bits. Reference implementation: `contracts/src/test/kotlin/p2pgate/contracts/Schnorr.kt`. The deal key signs only the claim transactions themselves (ordinary Ergo tx signatures), never attestations. Landed as `SchnorrVerifier` + `CourierRecordVerifier` (decode + deal binding + freshness/skew gate + verify — the meeting gate).
- Explorer API client behind one `ChainSource` interface (`ExplorerChainSource`, mainnet-default base URL, injectable transport), so the app can switch backends (§4.1); a full-node implementation is the future swap behind the same interface. Contract compilation/parameterization lives here too (`ErgoContracts`, incl. `compileFast` small-timeout variants for tests/e2e).

**`:app`** — Android shell. MVVM: one `DealViewModel` per deal exposing a single `StateFlow<DealUiState>`; the timeline screen renders that state directly (§3.2). Responsibilities:

- **Key storage.** Deal keys live in **Android Keystore** (hardware-backed where available): one EC keypair per deal, generated at deal creation, non-exportable, deletable. Export for recovery is an explicit user action producing the deal-key seed (§6.3), never ambient.
- **All signing is client-side.** The app never transmits private key material; backends see deal ids, public keys, signatures, and read-only status queries.
- **No accounts, no KYC, no custody.** There is no login screen, no user identifier beyond deal-scoped keys, and the app holds no funds at any point — the user never sends crypto. The user supplies a USDT receive address at QUOTED (pinned as the vault's R9 `recipientAddr`), the seller's USDT transfer to that address is what the oracle observes, the payout address in a claim is user-supplied, and collateral release always pays the seller.
- Background work (polling, notification fetch) via WorkManager; fully disabled in no-notification mode (§5).

### 2.2 Data flow (one line)

```
ChainSource / operator backend → evidence events
  → :core:dealprotocol state machine → DealState
  → :app DealViewModel → DealUiState → Compose timeline
```

The UI never interprets raw chain data; every render is a projection of the state machine. This keeps "one timeline per deal" structurally true rather than aspirational.

## 3. Screens & flows

Implements `onramp-ux.md` §2. Screen list:

| Screen | Route | Purpose |
|---|---|---|
| Quote | `/quote` | discovery & quote (§3.1) |
| Deal timeline | `/deal/{dealId}` | the core screen (§3.2) |
| Handoff | `/deal/{dealId}/handoff` | meeting flow & record verification (§3.3) |
| Claim | `/deal/{dealId}/claim` | dispute path (§3.4) |
| Settings | `/settings` | privacy & notifications (§5) |
| Recovery | `/recover` | deal-link / seed recovery (§6.3) |

### 3.1 Discovery & quote (`onramp-ux.md` §2.1)

- Inputs: asset (USDT only for v1), fiat amount, fiat currency, and the user's **USDT receive address** (Tron base58 or EIP-55) — shared at QUOTED so the deal terms pin it as the vault's R9 `recipientAddr` (`specs/deal-protocol.md` §3.1). City-level location picker; exact meeting point is revealed only after the vault is funded (FUNDED).
- Quote list from the operator backend (`specs/operator-backend.md`): each row shows ETA, rate, and the **insured badge** = actual vault collateral value, read from the operator's published vault capacity — never a marketing string. "What does insured mean?" opens a plain-language explainer with an optional explorer deep link to the vault box.
- Choosing a quote → deal creation: the app generates the **deal key** (§2.1), builds the deal-creation request per `specs/deal-protocol.md` (the operator backend assigns the deal-scoped courier key and includes `courierPubKey` in the deal terms), and enters the machine at QUOTED. A quote that expires before funding closes the deal (`QuoteExpired`) — silently, with no on-chain footprint. On vault funding confirmation, transition to FUNDED and reveal the meeting-point area.

### 3.2 Deal timeline (`onramp-ux.md` §2.2) — the core screen

Renders the state machine as one vertical timeline; pending states greyed. The three happy-path rows:

```
✓ Seller has insured your deal            ← FUNDED
● Hand 15,600 EGP to the courier           ← PAYMENT_PENDING (courier-signed handoff record)
○ Wait for 500 USDT                        ← PAYMENT_CONFIRMED (oracle-confirmed) → RELEASED
```

Per-state UI rules:

- **PAYMENT_PENDING:** meeting details + the handoff flow. The **"safe to leave the meeting" indicator** stays red until the courier-signed handoff record is received, validated against the deal terms, and persisted: the screen shows *"don't leave without the record — it is your only proof"* until then, and the countdown to `RECLAIM_TIMEOUT` (24h) runs underneath. Once cash is collected, a stall on the USDT leg is a dispute case (§3.4), not a wait state.
- **PAYMENT_CONFIRMED:** the oracle has confirmed the seller's USDT transfer to the user's pinned address; the "USDT confirmed" indicator turns green and the release follows automatically (oracle digest alone, submitted by the seller side — no user action). The app suggests checking the receiving wallet as guidance; nothing is gated on it.
- **RELEASED:** closes with a summary screen: hash-linked, storable, deletable (§5).
- **RECLAIMED** (timeout branch): the user no-show (`FUNDED → RECLAIMED`). The deal card closes silently; no penalty copy — ghosting is the expected mode of a no-reputation market (`onramp-ux.md` §6).

### 3.3 The meeting flow (`onramp-ux.md` §2.3) — the one hard moment

1. **Deal key.** Generated in Android Keystore at deal creation (burner-by-default; import of an existing Ergo wallet key is an advanced option). The key backs the claim path and recovery — it signs no attestations and never touches funds directly.
2. **Record rendering.** At the meeting the courier shows a QR encoding the handoff record (`p2pgate://handoff?m=<base64url(record)>` per `specs/deal-protocol.md` §3.3). The user scans it in-app; the app validates amount/currency/deal id against the deal terms and renders the record in plain language — *"courier 'M. K.' collected 15,600 EGP for deal #A3F9, 14:32"*.
3. **Cash first, then count, then signature.** The user hands over the cash and watches the courier count it; the courier signs only *after* the count (a signature before collection would hand the user a false artifact — the courier-side rule is protocol-level, see `specs/courier-app.md` §3.2).
4. **Verification (the "safe to leave" guard).** The app verifies the courier's Schnorr signature against the `courierPubKey` pinned in the deal terms and persists the verified record locally. **Only then** does it show the "safe to leave the meeting" indicator. This is enforced in `:core:dealprotocol` (meeting-complete evidence rejected while the record is unverified), not just hidden in the UI: until verification, the screen says plainly that leaving means walking away uninsured.

### 3.4 Dispute & claim

The dispute button is permanent, under the timeline on every deal screen — calm, first-class, never a support ticket (`onramp-ux.md` principle 6):

```
Cash collected but no USDT? You can claim the $500 insured amount.
[Start claim] — available once cash is collected, pays out after ~12h
```

- Available from **PAYMENT_PENDING** (the courier-signed handoff record exists and the seller never paid) and from **PAYMENT_CONFIRMED** (claims without cause — the seller counters mechanically with the oracle digest alone; a `PaymentConfirmed` arriving while the claim is open sets the deal's contested flag rather than being rejected). The vault box is still FUNDED on-chain in both; starting the claim is what lands the handoff record on-chain.
- Claim flow: user supplies a USE payout address (own Ergo wallet or one the app helps create via deep link) → app builds the claim-open transaction (vault path B: the courier-signed handoff record — the 84-byte `P2PH` message plus the single Schnorr half `(a_courier, z_courier)` and the timestamp binding as context variables, per the revised `specs/vault-contract.md` §5 — moves the box FUNDED → PAYMENT_PROVEN; no oracle input on this path) → `CLAIM_OPENED` → countdown over `CLAIM_MATURATION` (12h) → `CLAIMABLE` → app builds and broadcasts the claim payout transaction (vault path D, §4.3) → `CLAIMED`.
- Expected wait time is shown at every step: opening the claim, maturation, payout. The fee (`feeBps` 25–100 bps, taken in-protocol per `specs/vault-contract.md`) is disclosed in the claim summary.

## 4. Chain interaction

### 4.1 Learning deal/vault state

Two evidence sources feed the state machine:

1. **Operator backend / oracle feed** (`specs/operator-backend.md`): quote data, handoff-record relay status, USDT payment-confirmation status (oracle-confirmed observation of the *seller's* transfer to the user's pinned address), courier ETA/readiness. Consumed over HTTPS (or the configured privacy transport, §5) as a read-only status API keyed by deal id + recovery token.
2. **Ergo chain directly** via the `ChainSource` interface: vault box state by box id, watched by polling. Default implementation: public explorer API (mainnet — the default target since 2026-09-17; testnet stays selectable via config, same as the operator backend's `network` setting); alternative: user-configured full node. Polling cadence is adaptive — tight (≈30 s) while a state change is expected (payment confirmation window, claim maturation expiry), relaxed (minutes) otherwise, and suspended entirely in no-notification mode when the app is backgrounded.

The app treats the chain as authoritative: if the backend and the chain disagree (e.g., the backend reports RELEASED but the vault box is still unspent on-chain), the UI shows the chain state and flags the discrepancy — the oracle/backend is a convenience, not a root of trust for the user's own money.

### 4.2 Vault box monitoring

On FUNDED, the app records the vault box id. `:core:ergo` polls the box and its successors:

- box unspent, timeout not reached → FUNDED / PAYMENT_PENDING
- PAYMENT_PROVEN successor box exists (user opened a claim, vault path B) → CLAIM_OPENED
- box spent by release path (oracle digest alone) → RELEASED
- box spent by reclaim path (timeout) → RECLAIMED
- box spent by claim path → CLAIMED

### 4.3 Building the claim transaction (dispute path only)

The dispute path is two transactions, both built by `:core:ergo` via ergo-appkit:

1. **Claim-open** (machine at PAYMENT_PENDING or later, user starts a dispute): spends the FUNDED vault box via vault path B, supplying via context variables the courier-signed handoff record — its 84-byte `P2PH` message plus the single Schnorr half `(a_courier, z_courier)` and the timestamp binding (`specs/vault-contract.md` §5). The output is the PAYMENT_PROVEN box. No oracle input is involved on this path: it is the handoff record, not a payment proof, that gates the claim. *Contract status:* path B's single-signature shape landed in `contracts/` (v2, 2026-09-13) and is built by `ClaimTxBuilder` in `:core:ergo` (2026-09-16, prover-verified against the compiled vault scripts).
2. **Claim payout** (machine at CLAIMABLE): spends the PAYMENT_PROVEN box via vault path D.
   - Context: current height ≥ proof height + `CLAIM_MATURATION` (12h in blocks, per `specs/vault-contract.md`).
   - Outputs: user payout output (USE collateral minus `feeBps`, per `specs/vault-contract.md`'s fee distribution), protocol-fee output as defined by the contract.
   - Signed with the deal key from Keystore; broadcast via the configured `ChainSource`.

These two are the *only* transactions this app ever builds or signs.

### 4.4 The USDT receive — bring-your-own-wallet

The USDT leg is sent **by the seller** on Tron or Ethereum to the user's address — the app never constructs, signs, or broadcasts it. What the app owns:

- **Receive address entry** at QUOTED: the user's USDT address (Tron base58 or EIP-55) + expected amount, validated for format, stored in the deal terms and pinned at funding as the vault's R9 `recipientAddr`.
- **Payment status** read from the oracle/backend feed (§4.1): "waiting for seller payment → seen on Tron → confirmed (oracle)". The app watches; it does not participate. The "USDT confirmed" indicator (§3.2) binds to oracle confirmation, and the release follows without any user action.
- **Wallet-check guidance** on the confirmation screen: deep links to the user's installed wallet apps where possible so the balance check is one tap. This is guidance-only — nothing in the protocol is gated on the user checking, and no signature follows from it.

If the user has no USDT wallet at all, the quote screen degrades to guidance for installing one (open question §8.2). The UX doc's burner-key option applies to the *Ergo deal key* only, never to custody of the user's USDT.

## 5. Privacy & notifications

Implements `onramp-ux.md` §2.4 and principle 4:

- **Notifications opt-in, default off.** Two channels: local push (WorkManager-driven, no FCM dependency preferred — open question §8.4) or Telegram-bot notifications handled server-side. A deal works fully without either.
- **Deal-link recovery token.** At deal creation the app shows a recovery link: `https://<operator>/deal/<dealId>#<random-token>` — the token authorizes status reads and (with the deal key) recovery on another device. Stored locally; user prompted once to save it.
- **Tor / no-notification mode.** A single settings toggle: routes all backend/chain traffic over Tor (embedded Orbot-style or SOCKS proxy to a user-run daemon — open question §8.5), disables background polling and all notifications, and suppresses OS-level network identifiers where feasible. Available for USDT v1; designed as the default for any future privacy-focused leg.
- **Deal-scoped identity.** Fresh Keystore keypair per deal; no cross-deal identifiers sent to any backend. Deal keys are discardable; deletion is offered at deal close.
- **Local data deletable.** One "delete all deal data" action: destroys Keystore keys (recovery becomes impossible unless the user exported the deal-key seed), wipes local DB and cached deal artifacts. No server-side account exists to delete.

## 6. Edge & failure states

Mapping `onramp-ux.md` §6 onto the state machine:

| Failure | Machine path | App behavior |
|---|---|---|
| **6.1 User no-show** | FUNDED → RECLAIMED (vault timeout 24h) | The meeting never happens; the deal card closes silently when the reclaim is observed on-chain. No penalty copy. |
| **6.2 Seller never sends USDT** | PAYMENT_PENDING → CLAIM_OPENED → CLAIMABLE → CLAIMED | Dispute button becomes the primary action on the timeline; claim timeline shown with maturation countdown (~12h). The user's cash is already handed over — this is exactly what the insurance covers. |
| **6.3 App dies mid-deal / device lost** | any state | Recovery via (a) deal-link token on a fresh install, plus (b) the deal-key seed the user was prompted to export at deal creation. Re-importing the seed re-derives the deal key; the machine re-syncs from chain + backend evidence. No server-side account to lose. |
| **6.4 Partial payment** | blocked at PAYMENT_CONFIRMED | The state machine rejects oracle confirmations whose amount ≠ deal amount (`digest.amount ≠ R9.expectedAmount` → release fails; the claim path is unaffected, since cash was already collected). The seller side's copy forbids partial sends ("send exactly 500 USDT in one transaction"). If a partial send happens anyway, the app surfaces it as an operator-resolution issue, not an automatic transition. |
| **6.5 User claims without cause** | CLAIM_OPENED contested | A `PaymentConfirmed` arriving during the claim sets the deal's contested flag (it is not rejected); the app shows "evidence attached, awaiting timeout" when the seller contests with the oracle digest alone (path C′). The contract decides, not the app. |
| **6.6 Backend unreachable** | machine frozen at last state | UI marks evidence stale with last-sync time; chain polling continues (it's the authoritative source). Claim path remains fully usable with only a chain connection. |
| **6.7 User ghosts after USDT arrives** | PAYMENT_CONFIRMED → RELEASED | Nothing depends on the user after the meeting: the release follows the oracle attestation without any user action, so ghosting is a non-event. The deal card closes normally. |

## 7. Dependencies & build

Gradle (Kotlin DSL), version catalog, modules as in §2.1:

- `:core:dealprotocol` — `kotlinx.serialization` only. No network, no Android.
- `:core:ergo` — **ergo-appkit 6.0.1** (the JVM Ergo tooling/SDK, pinned in the catalog) for tx building, Schnorr verification/signing, and explorer clients. Landed 2026-09-16 (M2, extended M3-A).
- `:app` — Jetpack Compose, ViewModel/lifecycle, WorkManager, Room (local deal store), Android Keystore (via `androidx.security` or direct API), a QR library for scanning (ML Kit barcode scanning is the presumptive choice — confirmed at implementation time, open question §8.6) and for generation (any small QR-code generator).

Build: standard `./gradlew :app:assembleRelease`; reproducible-build friendliness is desirable but not a v1 gate. No CI exists in this repository today.

**Deliberately not invented here:** no dependency is listed that the team hasn't verified. Anything uncertain — appkit version, QR library, Tor transport, push channel — is an open question (§8), not a silent pick.

## 8. Open questions

1. ~~Signature scheme details~~ **(resolved).** Variant and message-encoding fixed by `specs/vault-contract.md` §5: ergoforum.org/t/3407 Schnorr, `e = blake2b256(a ‖ msg ‖ pk)` as signed two's-complement, `z = r + e·x mod n` ground to ≤254 bits, over the 84-byte handoff record of `specs/deal-protocol.md` §3.2. In v2 the user app only *verifies* this signature (courier half) — the user-side receipt attestation is deleted from the protocol (v2, 2026-09-13). **Landed in `:core:ergo`** (2026-09-16): `SchnorrVerifier` (BouncyCastle secp256k1, `SchnorrVerifierSpec` green) and `CourierRecordVerifier` (decode + deal binding + freshness/skew gate + verify — the meeting gate). No sigma-layer wrapper beyond plain BouncyCastle secp256k1.
2. **USDT-receive UX for wallet-less users.** The user needs a USDT receive address; for v1 the app does not custody USDT. Do we deep-link to partner wallets only, or ship guidance for installing one? Product decision.
3. ~~Claim transaction shape~~ **(resolved).** Two transactions per `specs/vault-contract.md`: claim-open (path B, lands the courier-signed handoff record on-chain) and claim payout (path D, after `CLAIM_MATURATION`). §3.4 and §4.3 updated accordingly. Path B's single-signature shape (context variables for the 84-byte message, the one Schnorr half `(a_courier, z_courier)`, and the timestamp binding, per the revised `specs/vault-contract.md` §5) is implemented by `ClaimTxBuilder` in `:core:ergo` (2026-09-16, `ClaimTxBuilderSpec` green — every built tx is signed by an offline prover running the real compiled vault scripts).
4. **Push channel.** FCM leaks metadata to Google; WorkManager-only local polling is the privacy-preserving default. Confirm battery/timeliness tradeoff for the courier-ETA state.
5. **Tor transport.** Embedded Tor library vs. SOCKS to Orbot/user daemon — size and maintenance cost differ significantly.
6. **QR scanning library.** ML Kit (bundled model, offline, but Google Play Services flavored) vs. ZXing (pure). Privacy default argues ZXing; reliability argues ML Kit.
7. **minSdk.** 26 is proposed from Keystore and market-device considerations; verify against actual device share in launch markets (Egypt first, per `onramp-business-model.md` §9).
8. **KMP scope guard.** ~~`:core:dealprotocol` must stay dependency-clean enough to compile for Kotlin/JS later — kotlinx.serialization is fine; confirm no java.* imports creep in~~ **(amended 2026-09-10, owner decision):** `java.time.Instant`/`Duration` are used in `:core:dealprotocol` today (`fundedTimestamp`, `proofTimestamp`, event instants). At KMP/JS migration these become `kotlinx-datetime` expect/actuals; keep new time APIs behind the same seam.
9. **Explorer API rate limits / reliability.** Public explorer as default `ChainSource` may need a fallback list or a protocol-run mirror; coordinate with `specs/operator-backend.md`.

## 9. Cross-references

- UX design being implemented: `onramp-ux.md` §2 (user app), §6 (failure states)
- Contract & trust model: `onramp-insurance.md` §2, §3.1 (USDT leg)
- Canonical timings & fee parameters: `specs/vault-contract.md`
- State machine & wire formats: `specs/deal-protocol.md`
- Courier-side counterpart: `specs/courier-app.md`
- Operator backend & status API: `specs/operator-backend.md`
- Business context (launch market, fee model): `onramp-business-model.md` §2, §9
