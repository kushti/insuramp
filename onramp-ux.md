# Onramp UX/UI Design — Insured Cash→USDT Deals

*UX/UI design note for the vault-insured cash→USDT on-ramp described in `onramp-insurance.md` (read that first). Direction: **cash→USDT only** — the user hands cash to the courier and receives USDT; the seller sends the USDT after collecting the cash, so it acts last and locks the vault collateral. Covers all three sides of the marketplace: user app, courier app, operator dashboard. All flow timings are consistent with the contract design: `RECLAIM_TIMEOUT` (24h), `CLAIM_MATURATION` (12h) on claims, and the courier-signed handoff record; release is gated on the oracle's attestation alone — there is no receipt signature anywhere in the protocol. Deal state names are owned by `specs/deal-protocol.md` §1 and referenced here by name.*

## 1. Design principles

1. **Hide the machinery.** The user never reads the words vault, oracle, Schnorr, ErgoScript. They see "insured" badges, progress states, and one button per decision. The entire Ergo side is invisible until — and unless — a dispute happens.
2. **One timeline per deal.** The whole protocol is a state machine; the UI renders it as a single vertical timeline. Every screen answers one question: "what happens now, and what happens if something goes wrong?"
3. **Fail-safe framing, not tech framing.** Never say "escrow smart contract". Say: *"The seller has locked $2,000 you can claim if your USDT never arrives — no trust needed."* The insurance is the product.
4. **Privacy-preserving defaults.** No accounts, no KYC in the app itself (the AML check lives on the seller side, as in the contract design). Deal-scoped identity only: fresh signing keys per deal (user *and* courier), discardable. Notifications opt-in.
5. **Bring-your-own-receive-address.** The app never custodies funds, and the user never sends crypto. The user needs a USDT receive address (their own wallet on Tron or Ethereum) and signs **no attestations**: the only protocol signature the user ever handles is the courier's handoff-record signature, which their app *verifies*. The app still generates a burner deal key — it backs the dispute path (claim transactions) and deal recovery, and it never touches funds.
6. **Calm disputes.** The dispute path is a first-class, always-visible button — not hidden behind support tickets. It is displayed with its expected wait time so it never feels like a failure state, just the insured path.

## 2. User app (mobile-first PWA)

### 2.1 Discovery & quote

```
┌─────────────────────────┐
│ Buy USDT with cash       │
│                          │
│ You hand    [EGP ▼]      │
│   amount    [ 15,600 ]   │
│ You get     [USDT ▼]     │
│   amount    [   500  ]   │
│ To address  [T9yD...9f]  │
│                          │
│ ── Best quotes ──        │
│ Cairo · ~40 min          │
│ 1 USDT = 31.2 EGP · insured $500 │
│ [Choose]                 │
│ Cairo · ~90 min          │
│ 1 USDT = 30.9 EGP · insured $2k  │
│ [Choose]                 │
└─────────────────────────┘
```

- Instant-quote matching, not an order book: the operator layer pre-publishes quotes; the user never negotiates.
- Every quote carries the **insured badge**: "insured up to $X" — the actual vault collateral value, not a marketing claim. Below the quote line, one tappable line: *"What does insured mean?"* → plain-language explainer with an optional "show me on-chain" deep link.
- The user pastes their **USDT receive address** at quote time (Tron base58 or EIP-55); it is pinned in the deal terms so the seller pays exactly that address (`specs/deal-protocol.md` §3.1).
- Location selection is coarse (city-level); exact meeting point is only revealed after the seller funds the vault.

### 2.2 Deal timeline (the core screen)

Three states, always rendered top-to-bottom with the pending ones greyed:

```
✓ Seller has insured your deal          ← FUNDED: vault funded, USE locked
● Hand 15,600 EGP to the courier         ← PAYMENT_PENDING: courier-signed handoff record
○ 500 USDT on its way                    ← PAYMENT_CONFIRMED: oracle confirmed → RELEASED
```

Per-state details:

- **State 2 — hand over cash.** Meeting point revealed at FUNDED; a countdown shows the vault timeout (`RECLAIM_TIMEOUT`, 24h). The handoff record is created live at the meeting: the user hands over the cash, watches the courier count it, and the courier signs the record only after the count. The app validates the record against the deal terms (amount, currency, deal id), verifies the courier's signature against the pinned courier key, and shows the **"safe to leave the meeting" indicator** — record received + validated — only once the verified record is persisted. The rule is absolute: **don't leave the meeting without the record.** It is the user's only dispute artifact; leaving without it is leaving uninsured.
- **State 3 — waiting for USDT.** The "USDT confirmed" indicator turns green once the oracle has confirmed the seller's USDT transfer to the user's pinned address — until then the seller could still stall, and the answer is the dispute button below. Green means the release follows automatically: the seller's backend submits it with the oracle digest alone, no user action needed. Checking the wallet is still good advice, but it gates nothing.

The dispute button lives permanently under the timeline:

```
Cash collected but no USDT? You can claim the $500 insured amount.
[Start claim] — available once cash is collected, pays out after ~12h
```

### 2.3 The one hard moment — the meeting

The on-ramp has exactly **one** hard sequencing rule, and it is physical, not cryptographic:

1. At deal creation the user generates (or imports) a **deal key**. If they have an Ergo wallet, use it; otherwise the PWA generates a burner key, and the key can live entirely in-browser — it backs the claim path and recovery, it never touches funds and never signs attestations.
2. **At the meeting, don't leave without the record.** The courier shows a QR (`p2pgate://handoff?m=...`) encoding the handoff record; the app validates amount/currency against the deal terms and displays the record in plain language — *"courier 'M. K.' collected 15,600 EGP for deal #A3F9, 14:32"*. The user hands over the cash and watches the courier count it; the courier signs only *after* the count. The app verifies the courier's signature against the pinned deal-scoped courier key and shows the **"safe to leave" indicator** only once the verified record is persisted locally. A record the app hasn't verified is worthless, so the app never says "safe to leave" before verification — and it says plainly, until then, that leaving means walking away uninsured.

Anti-pattern to avoid: softening the meeting rule. There is no second signature, no post-payment step, nothing the user must do after the meeting — which makes the meeting itself the only moment that can go wrong for them. The UX must make "don't leave without the record" unmissable, not merely documented. ("Check your wallet" survives only as guidance copy on the state-3 screen; no signature follows from it.)

### 2.4 Notifications

Optional push or Telegram-bot notifications per state change. Default **off**; a deal link with a random token works fine without them. A pure-Tor / no-notification mode is available for privacy-sensitive users.

## 3. Courier app

One job per screen, operable one-handed, offline-tolerant:

1. **Route list** — today's pickups: deal id, amount, neighborhood, status pill.
2. **Handoff screen** —
   - Cash amount large at top (counted twice in practice; the app shows the agreed figure).
   - "Show handoff QR" button → renders the handoff record (`p2pgate://handoff?m=...`) for the customer to scan and verify.
   - The courier's signature is enabled only **after physically counting the cash** — signing before collection would hand the customer a false artifact. GPS places both parties within a few hundred meters of the meeting point (soft check, overridable with a reason code — couriers' GPS dies indoors).
   - Once signed, the app confirms "record signed — show it to the customer"; the customer's app verifies it and shows its own "safe to leave" indicator. The meeting ends only when the customer has the verified record.
3. **Offline mode** — the signed record payload queues locally and syncs; the vault accepts the record whenever it lands at claim time, inside its freshness window.
4. **Deal-scoped keys.** The courier app holds a **deal-scoped signing key** issued by the operator backend — new versus the old design, where the courier held no keys. It signs the handoff record and nothing else; the matching public key is pinned in the deal terms and in vault R7 (`specs/vault-contract.md` §2). A stolen courier phone can **mint false cash-collection records** — but the claim a fake record unlocks pays the deal's *user* address, not the courier — and it **cannot release collateral**: release is oracle-only and pays the seller. A rogue courier signing records for cash never collected is operational security (GPS, dual-control, vetting), the same trust the cash leg itself carries: a colluding seller+courier gains nothing, because the claim pays the *user* the seller's own collateral.

## 4. Operator dashboard

The seller is the capital-heavy side; the dashboard's job is capital efficiency and dispute hygiene.

- **Vault lane** — kanban of deals by state: Quoted → Funded → Cash collected → Payment confirmed → Released/Reclaimed. Each card shows locked collateral, timeout countdown, and the reclaim path available.
- **Collateral management** — pool view: USE balance, utilization %, "capital idle 38% — reclaim 2 expired vaults" nudges. One-tap reclaim of timed-out vaults (the default routine path, also the privacy-preserving one).
- **Quote publishing** — set spread, ETA promise, max deal size = vault capacity. The insured badge on the user side reads straight from here, so overstating capacity is self-defeating — the badge is the vault.
- **AML pre-check panel** — paste address, get risk score, accept/reject. Off-chain tooling; the app records only the accept/reject decision, not the report, into deal metadata.
- **Dispute inbox** — open claims with the evidence view: the **courier-signed handoff record** plus the GPS/override record (did cash change hands?) versus the **oracle digest of the seller's USDT transfer** (was the user paid?), deadlines. Actions: contest (present the oracle digest alone — path C′ — mechanical when the digest exists), wait-for-timeout, escalate to manual review.
- **Infrastructure status** — oracle lag, observer health. If the oracle is lagging, quote publishing pauses automatically — never sell insurance you can't currently verify.

## 5. Per-leg UX deltas

- **USDT (the first leg).** Near-instant source-chain confirmations; the whole flow can complete in under an hour door-to-door. The timeline compresses; the state-2 "don't leave without the record" rule and the 24h `RECLAIM_TIMEOUT` matter most.
- **BTC and XMR legs — deferred.** Extension notes only (`specs/vault-contract.md` §8.1/§8.2), not specced products. Their waiting-room UX (relay-verified "safe to leave" for BTC, tx-key proof flows for XMR) gets designed when — and if — those legs are specced.

## 6. Failure & edge states

- **User never shows up (no-show)** → the meeting never happens, cash never changes hands; the vault times out (`RECLAIM_TIMEOUT`) and the seller reclaims. Deal card closes silently. No penalty; ghosting is the expected mode of a no-reputation market.
- **Cash collected, seller never sends USDT** → dispute button becomes primary; claim timeline shown (`CLAIM_MATURATION` ~12h). The user's cash is already handed over in this failure mode — that's what the insurance covers.
- **Partial USDT payment** → the vault keys on the exact amount; the oracle digest amount ≠ expected, so release fails and the deal lands in the dashboard dispute inbox. The seller side is instructed operationally: "send exactly 500 USDT in one transaction". The claim path is unaffected.
- **Courier pockets the cash and refuses to sign** → the user app never showed "safe to leave" (the §2.3 rule); if the user left anyway, they hold no artifact and the case is operationally treated as courier theft — GPS, dual-control, courier vetting/bonding, operator investigation, not a contract case. The mitigation is the same class as in any co-signing design; only the signature count changed.
- **User ghosts after the USDT arrives** → there is nothing for the user to withhold: the seller releases with the oracle digest alone, no user action required. The ghost case is a non-event in v2.
- **User claims without cause** → the seller contests by presenting the oracle digest alone during maturation (path C′); the digest is sufficient, so an honest seller always counters a false claim. The dashboard shows "evidence attached, awaiting timeout".
- **App dies mid-deal** → deal recovery by link token or seed phrase of the deal key; no server-side account to lose.

## 7. Onboarding & market design

- **User entry:** anonymous web link or Telegram bot → quote → deal. Zero install required (PWA). The first-run experience is exactly one screen before the first quote.
- **Seller entry:** this is the capital side and can be heavier: dashboard setup, collateral top-up flow, wallet hygiene checklist, oracle-dependency acknowledgment. Onboarding copy is honest about the trust model — *"your outgoing USDT payments are verified by the oracle, and the oracle's attestation alone releases your vault — which is why oracle operator ≠ marketplace operator, and why phase 2 replaces it with a guard threshold"* — because seller operators *are* the technical audience.
- **Market bootstrapping:** quotes are only as good as collateral depth; the operator tooling should make utilization visible so sellers price spread against idle capital. The deck's point stands: this market needs collateral liquidity first, UI second.

## 8. Cross-references

- Contract & trust-model details: `onramp-insurance.md` §2–§5
- Deal protocol (state names, wire formats, sequencing rules): `specs/deal-protocol.md`
- Implementation specs: `specs/README.md`
- Vision context: `pillars.md` pillar 3
