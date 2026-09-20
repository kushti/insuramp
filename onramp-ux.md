# Onramp UX/UI Design — Insured Cash→USDT Deals

*UX/UI design note for the vault-insured cash→USDT on-ramp described in `onramp-insurance.md` (read that first). Direction: **cash→USDT only** — the buyer hands cash to the seller at an in-person meeting and receives USDT; the seller collects the cash and sends the USDT after the meeting, so it acts last and locks the vault collateral. Covers the two sides of the marketplace — buyer app and seller flow — plus the operator dashboard. All flow timings are consistent with the contract design: `RECLAIM_TIMEOUT` (24h), `CLAIM_MATURATION` (12h) on claims, and the seller-signed handoff record; release is gated on the oracle's attestation alone — there is no receipt signature anywhere in the protocol. Deal state names are owned by `specs/deal-protocol.md` §1 and referenced here by name.*

## 1. Design principles

1. **Hide the machinery.** The buyer never reads the words vault, oracle, Schnorr, ErgoScript. They see collateral lines, progress states, and one button per decision. The entire Ergo side is invisible until — and unless — a dispute happens.
2. **One timeline per deal.** The whole protocol is a state machine; the UI renders it as a single vertical timeline. Every screen answers one question: "what happens now, and what happens if something goes wrong?"
3. **Fail-safe framing, not tech framing.** Never say "escrow smart contract". Say: *"The seller has locked $2,000 you can claim if your USDT never arrives — no trust needed."* The insurance is the product.
4. **Privacy-preserving defaults.** No accounts, no KYC in the app itself (the AML check lives on the seller side, as in the contract design). Deal-scoped identity only: a fresh buyer signing key per deal, discardable. Notifications opt-in.
5. **Bring-your-own-receive-address.** The app never custodies funds, and the buyer never sends crypto. The buyer needs a USDT receive address (their own wallet on Tron or Ethereum) and signs **no attestations**: the only protocol signature the buyer ever handles is the seller's handoff-record signature, which their app *verifies*. The app still generates a burner deal key — it backs the dispute path (claim transactions) and deal recovery, and it never touches funds.
6. **Calm disputes.** The dispute path is a first-class, always-visible button — not hidden behind support tickets. It is displayed with its expected wait time so it never feels like a failure state, just the collateral-backed path.

## 2. Buyer app (native Android)

*Platform (owner decision, 2026-09-17): the buyer app is a **native Android app**, not a
mobile-first PWA — the older PWA wording elsewhere is superseded (`specs/android-app.md`
§1). Deal entry stays link-based: an anonymous link or Telegram-bot message opens the app
straight into the quote flow (first use: an install, once).*

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
│ 1 USDT = 31.2 EGP · $500 locked by seller │
│ [Choose]                 │
│ Cairo · ~90 min          │
│ 1 USDT = 30.9 EGP · $2k locked by seller  │
│ [Choose]                 │
└─────────────────────────┘
```

- Instant-quote matching, not an order book: the operator layer pre-publishes quotes; the buyer never negotiates.
- Every quote carries the **collateral line**: "Up to $X available — the seller has locked
  that much collateral" — the actual vault collateral value, not a marketing claim. (Since
  2026-09-18 the buyer app no longer uses "insured/insurance" wording; the factual
  collateral framing above is the app copy. The seller dashboard and the design docs keep
  the insurance framing — that is deliberate.) Below the quote line, one tappable line:
  *"What does the seller's locked collateral mean?"* → plain-language explainer with an
  optional "show me on-chain" deep link.
- The buyer pastes their **USDT receive address** at quote time (Tron in phase 1 — the deployed deal flow is Tron-only, `srcChainId = 0x01`; the Ethereum path stays defined in `specs/oracle-integration.md` for when its observer lands); it is pinned in the deal terms so the seller pays exactly that address (`specs/deal-protocol.md` §3.1).
- Location selection is coarse (city-level); exact meeting point is only revealed after the seller funds the vault.

### 2.2 Deal timeline (the core screen)

Three states, always rendered top-to-bottom with the pending ones greyed:

```
✓ Seller has locked collateral for your deal  ← FUNDED: vault funded, USE locked
● Hand 15,600 EGP to the seller          ← PAYMENT_PENDING: seller-signed handoff record
○ 500 USDT on its way                    ← PAYMENT_CONFIRMED: oracle confirmed → RELEASED
```

Per-state details:

- **State 2 — hand over cash.** Meeting point revealed at FUNDED; a countdown shows the vault timeout (`RECLAIM_TIMEOUT`, 24h). The handoff record is created live at the meeting: the buyer hands over the cash, watches the seller count it, and the seller signs the record only after the count. The app validates the record against the deal terms (amount, currency, deal id), verifies the seller's signature against the seller key pinned in the deal terms (the vault's R5 key), and shows the **"safe to leave the meeting" indicator** — record received + validated — only once the verified record is persisted. The rule is absolute: **don't leave the meeting without the record.** It is the buyer's only dispute artifact; leaving without it means walking away with no claim if the USDT never arrives.
- **State 3 — waiting for USDT.** The "USDT confirmed" indicator turns green once the oracle has confirmed the seller's USDT transfer to the buyer's pinned address — until then the seller could still stall, and the answer is the dispute button below. Green means the release follows automatically: the seller's backend submits it with the oracle digest alone, no buyer action needed. Checking the wallet is still good advice, but it gates nothing.

The dispute button lives permanently under the timeline:

```
Cash collected but no USDT? You can claim the $500 the seller locked.
[Start claim] — available once cash is collected, pays out after ~12h
```

### 2.3 The one hard moment — the meeting

The on-ramp has exactly **one** hard sequencing rule, and it is physical, not cryptographic:

1. At deal creation the buyer generates (or imports) a **deal key**. If they have an Ergo wallet, use it; otherwise the app generates a burner deal key, held in Android Keystore (`specs/android-app.md` §2.1) — it backs the claim path and recovery, it never touches funds and never signs attestations.
2. **At the meeting, don't leave without the record.** The seller shows a QR (`p2pgate://handoff?m=...`) encoding the handoff record; the app validates amount/currency against the deal terms and displays the record in plain language — *"seller collected 15,600 EGP for deal #A3F9, 14:32"*. The buyer hands over the cash and watches the seller count it; the seller signs only *after* the count, in the seller's wallet/app. The app verifies the seller's signature against the seller key pinned in the deal terms and shows the **"safe to leave" indicator** only once the verified record is persisted locally. A record the app hasn't verified is worthless, so the app never says "safe to leave" before verification — and it says plainly, until then, that leaving means walking away with no dispute artifact at all.

Anti-pattern to avoid: softening the meeting rule. There is no second signature, no post-payment step, nothing the buyer must do after the meeting — which makes the meeting itself the only moment that can go wrong for them. The UX must make "don't leave without the record" unmissable, not merely documented. ("Check your wallet" survives only as guidance copy on the state-3 screen; no signature follows from it.)

### 2.4 Notifications

Optional push or Telegram-bot notifications per state change. Default **off**; a deal link with a random token works fine without them. A pure-Tor / no-notification mode is available for privacy-sensitive buyers.

## 3. Seller flow — the meeting

There is no separate seller consumer app in v1: the seller is the operator side, and the
meeting is run from seller tooling (operator dashboard companion / wallet app) against the
same deal link. The seller-side meeting screen mirrors the buyer's handoff screen:

1. **Meeting details** — the deal's amount, fiat currency, and meeting point; one job per
   screen, operable one-handed.
2. **Handoff screen** —
   - Cash amount large at top (counted twice in practice; the screen shows the agreed figure).
   - "Show handoff QR" button → renders the handoff record (`p2pgate://handoff?m=...`) for the buyer to scan and validate against their deal terms.
   - The seller's signature is enabled only **after physically counting the cash** — signing before collection would hand the buyer a false artifact, and a false artifact only ever unlocks a payout of the seller's own collateral to the buyer. The record is signed with the seller key already in the deal terms (vault R5) — no deal-scoped third-party key exists.
   - Once signed, the seller's screen confirms "record signed — the buyer has verified it"; the buyer's app shows its own "safe to leave" indicator. The meeting ends only when the buyer holds the verified record.
3. **After the meeting** — the seller sends the USDT (exactly the agreed amount, one transaction, to the buyer's pinned address) and the oracle confirmation drives the release; nothing else is required from either party at the meeting.
4. **No-show handling** — if the buyer never shows, the vault times out and the seller reclaims (`RECLAIM_TIMEOUT`); the meeting screen just closes the deal card.

## 4. Operator dashboard

The seller is the capital-heavy side; the dashboard's job is capital efficiency and dispute hygiene.

- **Vault lane** — kanban of deals by state: Quoted → Funded → Cash collected → Payment confirmed → Released/Reclaimed. Each card shows locked collateral, timeout countdown, and the reclaim path available.
- **Collateral management** — pool view: USE balance, utilization %, "capital idle 38% — reclaim 2 expired vaults" nudges. One-tap reclaim of timed-out vaults (the default routine path, also the privacy-preserving one).
- **Quote publishing** — set spread, ETA promise, max deal size = vault capacity. The buyer-side collateral line reads straight from here, so overstating capacity is self-defeating — the line is the vault.
- **AML pre-check panel** — paste address, get risk score, accept/reject. Off-chain tooling; the app records only the accept/reject decision, not the report, into deal metadata.
- **Dispute inbox** — open claims with the evidence view: the **seller-signed handoff record** (the seller acknowledging cash receipt under the same key that reclaims the collateral) versus the **oracle digest of the seller's USDT transfer** (was the buyer paid?), deadlines. Actions: contest (present the oracle digest alone — path C′ — mechanical when the digest exists), wait-for-timeout, escalate to manual review.
- **Infrastructure status** — oracle lag, observer health. If the oracle is lagging, quote publishing pauses automatically — never sell insurance you can't currently verify.

## 5. Per-leg UX deltas

- **USDT (the first leg).** Near-instant source-chain confirmations; the whole flow can complete in under an hour door-to-door. The timeline compresses; the state-2 "don't leave without the record" rule and the 24h `RECLAIM_TIMEOUT` matter most.
- **BTC and XMR legs — deferred.** Extension notes only (`specs/vault-contract.md` §8.1/§8.2), not specced products. Their waiting-room UX (relay-verified "safe to leave" for BTC, tx-key proof flows for XMR) gets designed when — and if — those legs are specced.

## 6. Failure & edge states

- **Buyer never shows up (no-show)** → the meeting never happens, cash never changes hands; the vault times out (`RECLAIM_TIMEOUT`) and the seller reclaims. Deal card closes silently. No penalty; ghosting is the expected mode of a no-reputation market.
- **Cash collected, seller never sends USDT** → dispute button becomes primary; claim timeline shown (`CLAIM_MATURATION` ~12h). The buyer's cash is already handed over in this failure mode — that's what the insurance covers.
- **Partial USDT payment** → the vault keys on the exact amount; the oracle digest amount ≠ expected, so release fails and the deal lands in the dashboard dispute inbox. The seller side is instructed operationally: "send exactly 500 USDT in one transaction". The claim path is unaffected.
- **Seller takes the cash and refuses to sign** → the buyer app never showed "safe to leave" (the §2.3 rule); if the buyer handed the cash over anyway, they hold no artifact and there is no on-chain case — this is the same residual as in any face-to-face cash trade. The mitigation is procedural and product-level: the meeting rule is unmissable, and off-chain escalation (operator investigation, deal-identity blacklisting) is the recourse, not a contract case.
- **Buyer ghosts after the USDT arrives** → there is nothing for the buyer to withhold: the seller releases with the oracle digest alone, no buyer action required. The ghost case is a non-event in v2.
- **Buyer claims without cause** → the seller contests by presenting the oracle digest alone during maturation (path C′); the digest is sufficient, so an honest seller always counters a false claim. The dashboard shows "evidence attached, awaiting timeout".
- **App dies mid-deal** → deal recovery by link token or seed phrase of the deal key; no server-side account to lose.

## 7. Onboarding & market design

- **Buyer entry:** deal entry stays link-based — an anonymous web link or Telegram bot → quote → deal — but the app itself is a native Android install: install once, then every deal link opens straight into the quote flow. The first-run experience is exactly one screen before the first quote.
- **Seller entry:** this is the capital side and can be heavier: dashboard setup, collateral top-up flow, wallet hygiene checklist, oracle-dependency acknowledgment. Onboarding copy is honest about the trust model — *"your outgoing USDT payments are verified by the oracle, and the oracle's attestation alone releases your vault — which is why oracle operator ≠ marketplace operator, and why phase 2 replaces it with a guard threshold"* — because seller operators *are* the technical audience.
- **Market bootstrapping:** quotes are only as good as collateral depth; the operator tooling should make utilization visible so sellers price spread against idle capital. The deck's point stands: this market needs collateral liquidity first, UI second.

## 8. Cross-references

- Contract & trust-model details: `onramp-insurance.md` §2–§5
- Deal protocol (state names, wire formats, sequencing rules): `specs/deal-protocol.md`
- Implementation specs: `specs/README.md`
- Vision context: `pillars.md` pillar 3
