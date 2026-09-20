# Operator Backend — Implementation Spec

*Implementation specification for the operator-side backend of the vault-insured cash→USDT on-ramp. Depends on: `specs/deal-protocol.md` (canonical deal state machine), `specs/vault-contract.md` (ErgoScript vault and its timings: `RECLAIM_TIMEOUT` 24h, `CLAIM_MATURATION` 12h — and, since the 2026-09-18 fee removal, no in-contract protocol fee on any path), `specs/oracle-integration.md` (payment-proof oracle for the USDT leg: phase-1 centralized NFT-authenticated oracle gating release, phase-2 Rosen-derived threshold). Covers the first asset leg only — cash→USDT on-ramp with USE collateral; the seller locks the vault and physically meets the buyer to collect the cash. Product context: `onramp-ux.md` §4 (operator dashboard); economics context: `onramp-business-model.md` §4 (capital dynamics) and §7 (regulatory posture). The reverse direction (USDT→cash) is out of scope and not specified.*

**Implementation status (M3-B, 2026-09-17; M4 seller-meeting additions same week):** the
backend has landed in `backend/` (Kotlin/Ktor pinned 3.0.3, 93 tests green across 11
suites): deal engine as sole transition authority, chain watcher, vault manager
(fund/reclaim/release/contest), quote publisher with capacity + pause gates,
buyer/dashboard `/v1` APIs + WebSockets, deal-scoped bearer auth, dispute inbox
(contest/accept/investigate + escalation), fail-closed AML `RiskScorer` recording
decisions only, infra monitor + auto-pause, plus the M4 seller-meeting pair
(`POST /v1/dashboard/deals/{id}/handoff/sign` — Schnorr-signs the 52-byte record with the
vault R5 key, driving `CashCollected` FUNDED → PAYMENT_PENDING; and
`GET /v1/dashboard/deals/{id}/handoff/qr.png`, ZXing-rendered) and the static seller
dashboard front-end served at `/dashboard/` (public shell, token-gated API — see
`specs/seller-dashboard.md`). Documented deviations from this spec:
- **Persistence:** in-memory `DealStore` behind the JDBC-shaped interface; a restart
  loses state. The PostgreSQL implementation is the documented follow-up.
- **Broadcast:** the `TxSubmitter` seam; `NoOpTxSubmitter` is the default (txs are
  built and prover-verified, not broadcast) and `NodeTxSubmitter` is the production shape.
- **Timeout ownership:** the chain watcher mirrors timeout facts only from *observed
  spends*; the reclaim scheduler solely owns timeout transitions for unspent boxes —
  it must build the reclaim tx in the same breath as the state change, and a
  watcher-driven `RECLAIMED` would strand the vault with no spend (§10).
- **Oracle wiring:** release/contest txs attach the oracle's attestation box as a
  **data input** (`OperatorTxBuilder.buildRelease/buildContest(oracleDataInput, signer)`
  — operator wallet only, no oracle co-signature, no oracle-key fee inputs), resolved
  via `OracleClient.attestationBoxFor(dealId)` with the in-process `DevOracle` in
  dev/tests. The oracle service serializes attestation postings per pending release
  (`specs/oracle-integration.md` §3.1); the operational consequence for this backend is
  in §2.
- **Handoff-record upload:** the buyer-authed `POST /v1/deals/{id}/handoff` accepts
  the seller-signed record (+ optional GPS reference) and drives the `CashCollected`
  transition (§9, buyer API).

## 1. Scope & role

The operator is the **capital-heavy side** of the marketplace: it locks USE collateral into vaults, publishes quotes, and — as the seller — meets the buyer, collects the cash, and sends the USDT after the cash is in hand. The backend is the operator's tooling; it is not protocol infrastructure. Per `onramp-business-model.md` §7: the protocol never touches fiat and never custodies buyer funds; money-transmitter obligations attach to the operator's cash-collection leg alone. The backend therefore handles fiat only as *records* (amounts, handoff records), never as money movement.

The backend has three jobs, in priority order:

1. **Capital efficiency.** Collateral is the binding constraint (same collateral locked ~24–48h per deal cycle, throughput ~0.5–1 deal/day per locked unit — `onramp-business-model.md` §4). Every module exists to keep utilization high and idle time low: fast release on confirmed payment, accurate capacity reporting, honest quotes sized to real vault depth.
2. **Deal orchestration.** Drive each deal through the canonical state machine from `specs/deal-protocol.md` §1: `QUOTED → FUNDED → PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED`, with quote expiry (`QuoteExpired`) closing a deal that never funded, the timeout branch `FUNDED → RECLAIMED` (buyer no-show), and the dispute branch `PAYMENT_PENDING → CLAIM_OPENED → CLAIMABLE → CLAIMED`. `PAYMENT_CONFIRMED` means the oracle confirmed the seller's USDT transfer; the release follows without any buyer action. On-chain, the vault box either spends directly `FUNDED box → spent` (routine release via path C or reclaim via path A) or detours `FUNDED box → PAYMENT_PROVEN box → spent` (dispute); the backend mirrors, never invents, these states.
3. **Dispute hygiene.** Claims must be visible, evidence-bearing, and actioned before deadlines. A missed claim deadline is an unforced capital loss.

One on-ramp-specific orchestration rule binds several modules: **reclaim is valid only from FUNDED and PAYMENT_CONFIRMED.** From PAYMENT_PENDING, reclaim is a theft path — cash has changed hands and no payment proof exists — and the protocol rejects it (`specs/deal-protocol.md` §1). From PAYMENT_CONFIRMED the release is preferred and normally immediate (oracle digest alone, no buyer action); the timeout reclaim remains only as the fallback for an attested-but-unreleased vault. On-chain, a FUNDED box cannot tell these states apart, so the reclaim automation must use *off-chain* deal state to exclude any deal that has progressed past FUNDED without a confirmed payment. This is a hard invariant of the vault manager and the dispute inbox, not an optimization.

Technology: **Kotlin/Ktor service**, single deployable, PostgreSQL for deal state, an embedded wallet (or external node wallet) for vault transactions, web dashboard front end as API consumer (dashboard UI itself is out of scope for this spec — it has its own: `specs/seller-dashboard.md`).

## 2. Architecture

Module decomposition of the Ktor service. Modules communicate over an internal event bus; deal state transitions are the only events that matter and are all persisted before dispatch.

```
┌──────────────────────────────────────────────────────────┐
│                     Ktor service                          │
│                                                           │
│  ┌─────────────┐   ┌──────────────┐   ┌────────────────┐ │
│  │ deal engine │◀─▶│ chain watcher│◀─▶│ vault manager  │ │
│  │ (state mach.)│  │ (box states) │   │ (fund/reclaim) │ │
│  └─────┬───────┘   └──────┬───────┘   └───────┬────────┘ │
│        │                  │                    │          │
│  ┌─────▼───────┐   ┌──────▼───────┐   ┌───────▼────────┐ │
│  │ quote       │   │ oracle client│   │ infra monitor  │ │
│  │ publisher   │   │ (oracle svc) │   │ + auto-pause   │ │
│  └─────┬───────┘   └──────────────┘   └───────┬────────┘ │
│        │                                       │          │
│  ┌─────▼─────────┐                    ┌───────▼────────┐ │
│  │ buyer deal API │                    │ dispute inbox  │ │
│  └───────────────┘                    └────────────────┘ │
└──────────────────────────────────────────────────────────┘
         │ REST/WS                       │ dashboard (operator keys)
```

- **Deal engine.** Owns the canonical state machine. All transitions are validated: only the transitions named in §1 are legal (including the reclaim gating rule); anything else is rejected and logged as an invariant violation. State is persisted transactionally (DB row = source of truth; the chain watcher can *cause* transitions but never holds state).
- **Chain watcher.** Polls the chain through the `ChainSource` interface — Ergo explorer API (default) or any node with the extra indexer (`P2P_CHAIN_SOURCE=node`, `P2P_NODE_URL`) — for the operator's vault boxes. Maps on-chain reality to the deal machine's events: vault box found → `VaultFunded` (→ `FUNDED`); box moved to `PAYMENT_PROVEN` (buyer opened a claim with the seller-signed handoff record) → `ClaimOpened` (→ `CLAIM_OPENED`); box spent via the release path (oracle digest alone) → `ReleaseObserved` (→ `RELEASED`); box spent via the timeout path → `ReclaimTimeoutElapsed` (→ `RECLAIMED`); box spent via the claim path → `ClaimPaid` (→ `CLAIMED`). (`PAYMENT_PENDING` is an off-chain transition driven by the buyer API's handoff-record upload — event `CashCollected(recordTimestamp=…)`; `PAYMENT_CONFIRMED` is an off-chain transition driven by the oracle client — event `PaymentConfirmed`; the FUNDED box is untouched in both. A `PaymentConfirmed` arriving while a claim is open sets the deal's contested flag rather than being rejected.) Confirmation-depth policy per `specs/oracle-integration.md`.
- **Vault manager.** Builds and submits vault transactions: fund (create `FUNDED` box with deal parameters, R7 = the bare 32-byte `oracleNftId`), reclaim (timeout path after `RECLAIM_TIMEOUT`), release (path C: the oracle attestation box as data input — the digest alone, no receipt signature to collect, no oracle co-signature to stage), contest (path C′: the same data input from the PAYMENT_PROVEN box during a claim). Reclaim is **automated as one job**, but it is state-aware: the scheduler reclaims vaults past `RECLAIM_TIMEOUT` **only for deals in FUNDED or PAYMENT_CONFIRMED** — never PAYMENT_PENDING (§1) — which is also the privacy-preserving default path (routine reclaims leave no on-chain link between vaults).
- **Oracle client.** No oracle co-signature exists anywhere in the tx path: the backend builds and signs release/contest txs with the operator wallet alone and only needs the oracle's **current attestation box** (`OracleClient.attestationBoxFor(dealId): ChainBox?`), which it attaches as the release's data input. **Serialization constraint (hard operational rule):** the attestation box is a singleton — the oracle spends it to post the next attestation, which invalidates any still-mempool release that referenced it. So `attestationBoxFor` returns *the previous deal's* box until its release confirms, and a new deal's attestation simply is not available until then; the vault manager must treat "attestation pending; previous release unconfirmed" as a queue condition and re-try, not as an error. Prompt release submission is therefore part of oracle liveness: a release left unconfirmed blocks every later attestation. The oracle service holds the posting side of the same rule (it re-checks by deal id / box id before posting; `specs/oracle-integration.md` §3.1).
- **Quote publisher.** Maintains the operator's live quotes (spread, ETA promise, min/max deal size, fiat currency) and serves them to the buyer-facing API. Hard-gated by the infra monitor (§8): no quotes while verification is degraded.
- **buyer-facing deal API.** Deal status feed, quote feed, handoff-record upload (`POST /v1/deals/{id}/handoff`, buyer-authed — the buyer relays the seller-signed record they obtained at the meeting), and an **oracle attestation proxy**: the buyer app never talks to the oracle directly; the backend proxies and caches confirmation status so the buyer's "USDT confirmed" indicator and the operator's are the same fact.
- **Dispute inbox.** Claim tracking and evidence assembly (§7).
- **Infra monitor.** Health of oracle, explorer/node, wallet daemon; drives auto-pause (§8).

Operational note: the backend holds the operator's signing keys (hot wallet for vault transactions). Key management — hardware wallet for treasury top-ups, limited hot balance — is operator policy, but the spec requires the reclaim/release signing path to be automatable without manual key ceremony, or reclaim latency eats the capital-efficiency gains.

## 3. Vault lane

The dashboard's primary view is a kanban mapping one-to-one onto the canonical state machine (names unchanged; the lane labels are the state names):

| Lane (state) | Meaning | Card shows | Available reclaim/exit path |
|---|---|---|---|
| `QUOTED` | Buyer selected a quote and shared their USDT receive address; vault funding in progress or awaiting confirmation | quoted amount, quote expiry, receive-address fingerprint | cancel/expire before funding (`QuoteExpired`; no on-chain footprint) |
| `FUNDED` | Vault box on-chain, USE locked, awaiting the meeting | locked collateral, timeout countdown (`RECLAIM_TIMEOUT` from funding height) | timeout reclaim — auto-job, but only while no handoff record exists (§1) |
| `PAYMENT_PENDING` | Cash collected (seller-signed handoff record on file); the seller must send the USDT | record timestamp, freshness countdown, meeting status | **none** — reclaim from here is a theft path and is rejected; the buyer's answer is the claim |
| `PAYMENT_CONFIRMED` | Oracle confirmed the seller's USDT transfer to the buyer (off-chain); release pending — no buyer action is required or awaited | oracle digest reference, timeout countdown | release via oracle digest alone (preferred, automatic), else timeout reclaim (attested but unreleased) |
| `RELEASED` | Vault spent via the release path | release tx id, cycle duration (feeds utilization stats) | terminal — collateral returns to the pool in full |
| `RECLAIMED` | Vault spent via the timeout path (buyer no-show, or attested-but-unreleased fallback) | idle time regained | terminal |
| `CLAIM_OPENED` | Buyer opened a claim with the seller-signed handoff record | evidence view (§7): handoff record vs oracle digest, contest deadline | contest (path C′), or accept (let it mature to CLAIMED) |
| `CLAIMABLE` | Claim matured (`CLAIM_MATURATION` elapsed), payout imminent | — | last-chance contest only |
| `CLAIMED` | Collateral paid out to the buyer (seller never paid, or contest lost) | loss recorded | terminal |

Every card, in every lane, shows three things (per `onramp-ux.md` §4): **locked collateral amount**, **timeout countdown**, and **the exit path currently available**. A card with no available exit and no countdown is a bug indicator, not a normal state — and a FUNDED-lane card with a handoff record on file must not show "timeout reclaim" (§1).

## 4. Collateral management

**Pool view.** Aggregate USE position: total balance, locked in open vaults, free, utilization %. Derived nudges in the style of `onramp-ux.md` §4: *"capital idle 38% — 2 expired vaults awaiting reclaim"* (though reclaim is automated, so the nudge usually means the automation failed, a deal is mid-confirmation, or the state-aware exclusion of §1 is holding a PAYMENT_PENDING vault).

**Capital-recycling dynamics.** Per `onramp-business-model.md` §4: the same collateral is locked ~24–48h per deal cycle, so throughput is ~0.5–1 deal/day per locked unit [approx]. The backend tracks per-unit cycle time (fund → spend) and reports:

- realized deals/day per locked USE (actual vs the 0.5–1 [approx] planning figure);
- average lock duration by exit path (a release on confirmed payment should be ≪ a timeout reclaim — that gap is the margin argument for prompt meeting scheduling and for prompt release submission once the oracle attests);
- projected capacity: free collateral ÷ average deal size × recycling rate = supportable deals/day.

These numbers feed the quote publisher's max deal size directly (§5). **Max deal size = vault capacity is a hard constraint, not a default** — the operator can set it lower, never higher.

**Privacy-preserving funding.** Per `onramp-insurance.md` §2, vaults are funded and reclaimed through mixer outputs / stealth addresses, so routine deals (closed via timeout reclaim) leave no link between the operator's vaults. Implementation requirements:

- the vault manager draws funding inputs from a privacy-pool wallet partition, not from the operator's main balance;
- reclaim outputs return to the privacy partition;
- the address set used for vault funding is deal-scoped or periodically rotated;
- the pool view UI deliberately does not render a consolidated "operator wallet" graph — aggregation happens at the accounting level (amounts), not the address level.

The trade-off to surface to the operator: privacy funding adds mixer latency to vault setup. If the quote promises a fast ETA, funding must come from pre-mixed reserves — so the pool view tracks *mix-ready* balance separately from total balance.

## 5. Quote publishing

A quote is: **spread**, **ETA promise**, **min/max deal size**, **fiat currency**, plus
an optional **seller location**. The currency is part of the quote (2026-09-20): a
3-letter code (uppercase-normalized at publish; malformed codes rejected), the buyer app
shows only quotes matching its selected currency, and deal creation rejects a
currency/quote mismatch. The min/max range (2026-09-19) lets sellers refuse too-small
deals; the location is a nullable `lat`/`lon` pair (complete pair or neither;
lat ∈ [−90, 90], lon ∈ [−180, 180]; a half-pair is a rejection). When present it lets the
buyer app render quotes on a map (List/Map toggle on the quote screen); absent, the quote
is list-only. The feed is **multi-quote** (2026-09-19): several operators' quotes coexist,
capacity is validated per publish against free collateral *minus the sum of the other
active quotes' max amounts*, and quotes expire or are withdrawn (`POST
/v1/dashboard/quotes/{id}/withdraw`). `P2P_DEMO_QUOTES=true` seeds one located quote per
app currency (Cairo USD, Nairobi KSH, Mumbai INR, Moscow RUB) on demo startup.
Semantics:

- **Spread** — the operator's margin over reference rate, in bps (the seller's margin, not a
  protocol fee — the in-contract protocol fee was removed 2026-09-18). Must internally
  cover meeting logistics and ops costs; the publisher warns if spread < configured cost floor.
- **ETA promise** — minutes from `FUNDED` (deal accepted, vault locked) to the cash-collection meeting. This is an operator-network property, not a chain property; the publisher derives the default from recent realized meeting times.
- **Deal size range** — max is vault capacity (§4), period; min is the operator's floor for refusing deals too small to be worth a meeting (publish validation: `0 < min ≤ max ≤ capacity`).

The buyer-side collateral line reads the actual vault collateral ("Up to X USDT
available — the seller has locked that much collateral"; the buyer app dropped
"insured" wording 2026-09-18 — `onramp-ux.md` §2.1 and §4). The line is the
vault: overstating capacity is self-defeating because it is verifiable on-chain
and a quote that outruns collateral either fails to fund (deal stuck in
`QUOTED` until the quote expires — `QuoteExpired`) or ships a smaller
collateral line than promised. The backend enforces this structurally — it
refuses to publish a quote whose max size exceeds mix-ready collateral —
rather than trusting operator discipline.

Quotes are versioned and expire (default TTL aligned with, and shorter than, `RECLAIM_TIMEOUT`; a quote must never outlive the vault funded from it). Quote feed deltas are pushed over WebSocket to the buyer app; full snapshot on connect.

## 6. AML pre-check hook

Per `onramp-ux.md` §4 and `onramp-insurance.md` §3.1: the AML step is **off-chain by design** — the one irreducible piece of off-chain reputation in the system.

- Integration point: a pluggable `RiskScorer` interface (one method: `score(address, chain) → score`), with an HTTP adapter for external risk-scoring providers and a stub for manual review.
- Trigger: at `QUOTED`, before vault funding, on the buyer's declared USDT **receive** address (the address pinned as R9 `recipientAddr` — the seller pays the buyer). A re-check is triggered if the address submitted at funding differs from the declared one (address-swap defense).
- **Recording rule (hard requirement):** the deal metadata records only the **accept/reject decision**, the scorer identifier, and a timestamp. The risk report itself is never stored, never logged, never proxied to any client. `onramp-ux.md` §4: "the app records only the accept/reject decision, not the report."
- Fail-closed: if the scorer is unreachable, the deal does not fund. This mirrors §8's auto-pause principle — don't sell insurance against a check you can't currently run.

## 7. Dispute inbox

One row per open claim (deals in `CLAIM_OPENED` / `CLAIMABLE`). A claim asserts "cash was collected and the seller never paid"; it is gated on-chain by the seller-signed handoff record (path B — the v2 single-signature shape, landed in `contracts/` 2026-09-13). The evidence view from `onramp-ux.md` §4 shows the two sides of the story:

- **Handoff record** — the seller-signed `"P2PH"` record and its timestamp: proof the cash was collected. Path B verifies the seller's Schnorr signature in-script against the R5 `sellerPubKey`, so a valid record is what put the box in PAYMENT_PROVEN; the view still renders the record and the meeting metadata for the operator. Note what the artifact is: the seller acknowledging cash receipt under the same key that reclaims the collateral — a seller-repudiation case (cash taken, record refused) produces no on-chain artifact at all and is a procedural/off-chain matter, not an inbox row.
- **Oracle payment status** — whether the oracle has confirmed the seller's USDT transfer to the buyer (digest reference, attestation id, confirmation depth per `specs/oracle-integration.md`). If yes, the claim is without cause and the contest path is mechanical. A `PaymentConfirmed` event arriving while a claim is open sets the deal's contested flag — it is never rejected — and this row is where the operator sees it.
- **Deadline** — time until the claim matures (`CLAIM_MATURATION`, 12h) and becomes `CLAIMABLE`. This countdown is the inbox's primary sort key.

Actions, exactly three:

1. **Contest** — present the oracle digest of the seller's USDT transfer (vault path C′): the box pays the seller, the claim is defeated. **Mechanical whenever the digest exists — the digest alone is sufficient, so an honest seller always counters a false claim.** There is no withheld-signature corner in v2: nothing the buyer can do or refuse blocks path C′.
2. **Accept** — concede the claim; take no action; the claim matures and path D pays the buyer. Rational when the seller genuinely did not pay, or when contesting costs more than the collateral at stake. The loss is recorded against the deal.
3. **Escalate / investigate** — a record verifies but the operator's evidence says the cash was never collected (e.g. a coerced or stolen seller key). This is an operational-security matter — meeting records, device hygiene, key compromise response — not a contract case; the action routes the deal to the operator's internal investigation. It changes nothing on-chain.

The inbox is fail-loud: any claim approaching maturation without an operator decision escalates (dashboard alert + configurable webhook). A claim that matures unactioned is collateral donated.

## 8. Infrastructure status & auto-pause

Monitored signals:

| Signal | Source | Degraded when |
|---|---|---|
| Oracle lag | Oracle health endpoint (`specs/oracle-integration.md` §4.1) | observer lag exceeds configured threshold, event stream stalls, or oracle signer unreachable |
| Explorer / node sync | chain watcher | tip height stale beyond threshold |
| Wallet daemon health | vault manager heartbeat | signing/broadcast failing |
| AML scorer reachability | §6 hook | unreachable (funding halts; existing deals unaffected) |

**Auto-pause rule (per `onramp-ux.md` §4, verbatim intent): never sell insurance you can't currently verify.** When the oracle leg is degraded, the quote publisher withdraws all quotes (buyer feed shows no quotes, not stale quotes) and no new vaults are funded. In-flight deals are unaffected — USDT confirmations and release attestations queue and apply when the oracle recovers, and the buyer's claim path never depends on oracle liveness (it is gated on the seller-signed handoff record, not the oracle). Auto-pause events are recorded with cause and duration; they feed the operator's realized-uptime stats, since every paused hour is idle capital.

The principle extends by analogy: stale explorer → pause new funding (can't confirm vault boxes); dead wallet daemon → full pause and page the operator.

## 9. API surface sketch

REST unless noted; JSON. All endpoints versioned under `/v1`. Sketches, not schemas.

**buyer-facing deal API** (consumer: the buyer app — native Android, `specs/android-app.md`; the earlier "buyer PWA" sketch is superseded by that decision; auth: deal-scoped bearer token — random token in the deal link per `onramp-ux.md` §2.4, minted at `QUOTED`, expires at deal close):

```
GET  /v1/quotes                      # quote feed snapshot: {quotes: [...]} (no auth; multi-quote since 2026-09-19)
WS   /v1/quotes/stream               # full-snapshot pushes on publish/withdraw/expire
POST /v1/deals                       # create an OFFER from quote id + USDT receive address → {dealId, dealToken}
                                     # (2026-09-20: deal creation is offer-only — the deal sits in QUOTED
                                     #  until the seller accepts; offer TTL = the quote's expiry)
GET  /v1/deals/{id}                  # status: canonical state, vault ref, insured amount, timers,
                                     # sellerPubKey (the vault R5 key the app verifies the handoff signature against)
WS   /v1/deals/{id}/stream           # state-change push (replaces polling)
GET  /v1/deals/{id}/attestation      # oracle attestation proxy (USDT payment confirmation + evidence pointer)
POST /v1/deals/{id}/handoff          # upload the seller-signed handoff record (+ optional GPS ref):
                                     # drives CashCollected → PAYMENT_PENDING (landed M3-B)
POST /v1/deals/{id}/claim            # open claim (guides buyer to on-chain claim tx with the handoff record)
```

Deal creation returns **only a buyer deal token** — there is no third-party key
provisioning anymore; the seller signs the handoff record with the seller key already in
the deal terms (vault R5). The API exposes canonical state names verbatim — no parallel
UI vocabulary to drift.

**Dashboard API** (consumer: operator dashboard; auth: operator keys — long-lived, scoped; vault-signing keys never leave the vault manager, the dashboard gets read + action endpoints, not keys):

```
GET  /v1/lane                          # kanban: deals by canonical state (§3)
GET  /v1/pool                          # collateral pool view (§4)
POST /v1/vaults/{id}/reclaim           # manual reclaim trigger (state-aware; auto-job handles routine)
GET  /v1/dashboard/quotes ; PUT /v1/dashboard/quotes     # quote publishing (§5; optional lat/lon seller location; multi-quote)
POST /v1/dashboard/quotes/{id}/withdraw                  # withdraw one quote
POST /v1/aml/check                     # paste address → accept/reject (§6)
GET  /v1/disputes ; POST /v1/disputes/{id}/{contest|accept|investigate}   # dispute inbox (§7)
GET  /v1/infra                         # monitor signals + pause state (§8)
WS   /v1/events                        # all deal/infra events (dashboard live view)

# offer flow (2026-09-20): deal creation is offer-only; the seller agrees by accepting
# (which funds the vault — the only caller of fundDeal). Unanswered offers expire with
# the quote; decline/abandon closes them with no on-chain footprint.
POST /v1/dashboard/deals/{id}/accept   # → funds the vault → FUNDED; 409 with reason (state/AML/capacity/infra)
POST /v1/dashboard/deals/{id}/decline  # → offer closed (QuoteExpired-style abort; no on-chain footprint)

# M4 seller-meeting pair (state gate: FUNDED signs and drives CashCollected → PAYMENT_PENDING;
# any other state → 409 naming the state; see specs/seller-dashboard.md §4)
POST /v1/dashboard/deals/{id}/handoff/sign   # → {dealId, state, recordHex, signatureA, signatureZ, sellerPubKey, qrPayload}
GET  /v1/dashboard/deals/{id}/handoff/qr.png # image/png (409 unsigned, 404 unknown deal)

# static seller dashboard front-end (public shell; the API above stays token-gated)
GET  /dashboard ; /dashboard/app.js ; /dashboard/styles.css
```

**Demo run recipe (M4, demo-backend mode):** `export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1`,
then `./gradlew :backend:run` — in-memory `DealStore`, `NoOpTxSubmitter`, in-process
`DevOracle`, mainnet chain-source defaults; `P2P_NETWORK=testnet` for testnet;
`P2P_CHAIN_SOURCE=node` + `P2P_NODE_URL=…` to poll a node with the extra indexer instead
of the explorer. Dashboard auth: set `P2P_OPERATOR_KEY` (bearer token for every dashboard
endpoint and the `/v1/events` socket); **when it is unset the dashboard API is
unauthenticated — demo-open mode, never deploy it so.** Explorer outages only degrade the
`EXPLORER_SYNC` infra signal; the server keeps serving. Dashboard at
`http://localhost:8080/dashboard`.

## 10. Open questions

- ~~**Withheld-signature-plus-claim corner**~~ **— ELIMINATED in v2 (2026-09-13).** In the old design a buyer who both withheld the USDT-receipt signature and opened a without-cause claim blocked path C′ (digest **+ signature** required) and the claim matured and paid — a known residual, deliberately not contract-prevented. The v2 release paths are gated on the oracle attestation **alone**, so path C′ needs only the digest: an honest seller mechanically counters any false claim, and there is nothing for the buyer to withhold. No known residual remains on the contest path. The surrounding posture is unchanged for *other* fraud classes: oracle fraud stays ex-post provable (public attestation, per-deal bounds — `specs/oracle-integration.md` §5.3), and a verifying record for cash never collected is now strictly a seller-side matter (the record is seller-signed) — real-world recourse runs against the seller's deal identity, not a third party.
- ~~**Third-party dispatch**~~ **— RESOLVED by removal (2026-09-17).** The old three-role design is gone: the seller meets the buyer and signs the handoff record with the seller key already in the deal terms. There is no dispatch, no reassignment, and no deal-scoped third-party key to re-mint. Reassignment questions reduce to the seller cancelling before funding (a `QuoteExpired`-style abort, no on-chain footprint).
- **Chain watcher source of truth.** Explorer API is operationally easy but adds a third-party dependency into the deal loop and leaks the operator's vault set to the explorer. Node polling is private but heavier. Default explorer, or default node? [spec: node becomes mandatory at any serious volume] **Implementation settled the shape (M3-B, 2026-09-17):** the watcher polls through the `ChainSource` interface with the explorer client as the default (`P2P_NETWORK`-selectable, mainnet default). The node adapter has since landed (same day): `NodeChainSource` (`apps/core/ergo`) speaks the node's `/blockchain` extra-indexer API (`/blockchain/box/byId`, `/blockchain/transaction/byId`, `/blockchain/indexedHeight`, `/blockchain/box/unspent/byAddress`) with multi-URL failover (404 = absence, never fails over) and is selected via `P2P_CHAIN_SOURCE=node` + `P2P_NODE_URL` (comma-separated base URLs). Any public node running the extra indexer qualifies — no explorer dependency, no vault-set leak to a third party; the explorer client stays as the default and as a fallback implementation. Notable follow-ups the node API unlocks: `/blockchain/box/unspent/byTokenId` (oracle singleton lookup) and `/blockchain/box/unspent/byTemplateHash` (all-vault watching in one query). Timeout transitions for *unspent* boxes are owned by the reclaim scheduler, not the watcher (see the status note above); the watcher mirrors timeout facts only from observed spends. Default-explorer vs. default-node as a *deployment* choice remains operator policy.
- **Oracle attestation proxy caching.** How stale may a cached attestation be before the buyer app must show "verification delayed" rather than the last-known state? Couples to the auto-pause thresholds in §8.
- **Concurrent vaults per deal size.** One vault per deal at 100% collateral ratio (`onramp-insurance.md` §5) is capital-simple but operationally rigid. Splitting a large deal across several vaults improves reclaim granularity at the cost of more boxes, more fees, more watcher load. Worth speccing only if deal sizes outgrow single-vault liquidity.
- ~~**Fee accounting**~~ **— RESOLVED by removal (2026-09-18).** The in-contract
  protocol fee (25 bps, `PROTOCOL_FEE_BPS`) was removed: no treasury output on any
  path, nothing deducted in-contract, so there is no on-chain fee to reconcile.
  `QuotePublisher.protocolFeeBps` and the `P2P_TREASURY_SECRET` wiring are gone
  with it. If a future revenue model reintroduces a protocol take, accounting for
  it becomes an open question again.
- **Privacy funding latency.** *(Still open.)* Pre-mixed reserve sizing: how much mix-ready USE to hold vs. mixer throughput? No data yet; the pool view should measure this from day one so the sizing rule can be evidence-based. [spec]

## Cross-references

- Deal state machine: `specs/deal-protocol.md`
- Vault contract, timings (`RECLAIM_TIMEOUT` 24h, `CLAIM_MATURATION` 12h): `specs/vault-contract.md` (§2)
- Payment-proof oracle (USDT leg): `specs/oracle-integration.md`
- Operator dashboard product design: `onramp-ux.md` §4
- Dashboard front-end consuming this API (separate spec, M4): `specs/seller-dashboard.md`
- Vault design, privacy funding, trust models: `onramp-insurance.md` §2, §3.1
- Capital dynamics, regulatory posture: `onramp-business-model.md` §4, §7
