# Insured Onramp — Business Model (Protocol/Marketplace Layer)

*Business model note for the vault-insured cash→USDT on-ramp described in `onramp-insurance.md`
(contracts & trust models) and `onramp-ux.md` (product): the user hands cash to the courier and
receives USDT; the seller sends after collecting cash and locks the vault
collateral. Focus: the protocol layer — who pays whom, the fee model, capital dynamics, token
tie-ins, and honest volume scenarios. Figures marked [approx]/[spec] are not verified; see
`rosen/deck.html` slide 15 for the repo's sourcing discipline.*

## 1. Value chain and money flow

```
 USER ──hands cash to courier──────────────┐
   ▲                                       ▼
   │         COURIER ──delivers cash──▶ OPERATOR (USDT seller)
   │                                       │
   │                                       ├── pays courier wage (fixed, off-protocol)
   │                                       ├── pays AML/ops costs
   │                                       └── keeps margin
   └────────────receives USDT at the quoted rate (spread)────────────┘

 PROTOCOL FEE (bps of volume, inside the vault contract, at release / reclaim / claim)
   ├── watchers/guards (oracle work)
   ├── protocol treasury
   └── optional RSN burn/stake sink

 USE minters: lock ERG ──▶ mint USE ──▶ collateral pool (operators' vaults)
 Oracle: confirms the seller's USDT transfer to the user — its attestation alone releases the vault
 Rosen: provides the guard-threshold upgrade path (phase 2) and rsBTC wrapping (BTC leg)
```

- The **user** pays the visible price: the quoted cash→USDT rate, whose premium over market is the
  spread. The insurance is what justifies paying it to a no-reputation counterparty.
- The **operator** earns the spread minus courier/ops/fee costs. As the USDT seller it also carries
  the capital cost of the locked vault collateral (§4). Operator economics set the ceiling on the
  protocol's take rate — the protocol tax must fit inside a spread users already pay elsewhere.
- The **protocol** earns a per-deal fee in basis points of deal volume, collected atomically inside
  the vault contract at release, reclaim, or claim — it cannot be bypassed without abandoning the
  insurance.
- **Watchers/guards** (the oracle layers) earn their share for the verification work that makes the
  USDT/XMR legs possible — on-ramp: confirming the seller's USDT transfer to the user, whose
  attestation alone gates the vault's release.

## 2. Protocol revenue model

**Fee:** a flat per-deal fee in bps of volume, deducted from the vault payout path (release to
seller or claim to user — same economic effect). Governance-adjustable, following the deck's
pattern that collateral/permit parameters are governance-controlled.

**Currency:** denominate in the collateral asset (USE for USDT deals, rsBTC for BTC deals). This
avoids forcing operators to source a second token and keeps fee flow aligned with the asset
actually moving.

**Pricing headroom.** The insurance premium only sells if it beats the *trust tax* users pay today
on cash→USDT premiums [approx]:

- India P2P ~0.5–2% [approx]
- Egypt ~5–15% during the 2024 dollar shortage [approx]
- Nigeria 30–70% premium episode, Feb–Mar 2024 [approx]
- Iran single digits, spiking ~10%+ under stress [approx]

A protocol fee of **25–100 bps** is comfortably inside those spreads — in premium markets the
operator spread is 5–30× the protocol fee, so the fee is not the pricing constraint; collateral
availability is (§4).

**Fee floor:** at low volume the fee must still cover oracle operating costs (observer
infrastructure in phase 1, the guard set's at phase 2, reporting permits). This mirrors the deck's
slide-10 fee-floor analysis: a fixed-cost verification layer needs either volume or subsidy, which
drives the bootstrapping sequence in §9.

## 3. Fee distribution and incentive alignment

Suggested split (starting point, governance-adjustable):

| Recipient | Share | Rationale |
|---|---|---|
| Watchers/guards (oracle) | 40–60% | They do per-deal verification work and carry slashable collateral |
| Protocol treasury | 25–45% | Funds audits, operator tooling, collateral subsidies |
| RSN sink (burn or staking reward) | 0–15% | Optional value-accrual leg; see §5 |

Parallels the deck's fee-treasury model (slide 9): fees accumulate in a protocol treasury
denominated in the operating asset, bridging into recurring revenue only when volume exists.

**Collusion economics.** The attack to worry about on-ramp: the oracle co-signs a fake digest of
the seller's own "USDT transfer", releasing the vault without payment. In v2 the attestation
alone is sufficient for release, so in phase 1 there is **no on-chain defense** against a
compromised or seller-captured oracle — that is accepted at launch and must be priced in
honestly. The defense stack is operational and economic: (a) oracle operator ≠ marketplace
operator, so a fake digest is never self-certifying; (b) every attestation is publicly auditable
against source-chain data, so oracle fraud is ex-post provable and each false attestation is
bounded by the per-deal size cap while the oracle is centralized; (c) in phase 2 the single
trusted oracle is replaced by a k-of-n guard set holding slashable collateral — then the attack's
profit is one vault's collateral while the cost is the guards' own locked collateral plus future
fee income. As with Rosen itself: not trustless, but the cost-to-attack should exceed the
extractable value per deal by design. (Phase 1 is honest trust, not cost-to-attack — a single
centralized oracle, mitigated operationally; see `specs/vault-contract.md` §9.)

## 4. Capital dynamics — the real constraint

The vault design is **capital-recycling**: the same collateral is locked for one deal cycle (~24–48h) and freed for the next.

- Throughput per locked unit: ~0.5–1 deal per day.
- Therefore **protocol TVL ≈ outstanding deal volume**, not cumulative volume. Supporting $1M/day of deals requires roughly $1–2M of collateral (USE or rsBTC) locked across operators.

Consequences:

1. **Collateral depth, not user demand, is the binding constraint** — identical to the deck's core constraint slide (slide 10). Every growth plan is a collateral-onboarding plan.
2. The protocol scales linearly in TVL; there is no operating leverage from float. This is a feature (no fractional-reserve risk) and a bug (capital-heavy).
3. Collateral yield for operators comes from deal spread, not from the protocol — so operator ROI must clear their cost of capital with utilization well under 100%. Thin early liquidity → wide spreads → premium-market-first sequencing (§9).

## 5. Token tie-ins (stated honestly)

- **USE demand:** every vault locks USE (USDT leg) — a structural demand sink. USE minting locks ERG as over-collateralized backing, so sustained vault demand is a genuine tailwind for ERG. It is reflexive: minting capacity grows with ERG price, not independent of it. Treat as tailwind, not flywheel — the collateral asset does not re-rate itself; per the analysis elsewhere in this repo's working notes, claims of "tens of billions of ERG locked" fail arithmetic against ERG's actual market cap and are excluded here.
- **RSN demand:** if watchers need RSN reporting permits and fees partially settle in RSN, protocol volume creates RSN buy pressure — the same activity-gated demand model the deck uses for bridge fees. Optional; the deck's stance (slide 14) — "optionality, not a line in the model" — applies verbatim.
- **ERG demand:** second-order, via USE minting above.

## 6. Volume scenarios

Anchored in measured data: Venezuela alone did ~$1.39B of Binance P2P volume in one month (~$16.6B/yr annualized) [solid, mid-2025]; strict global P2P scale is order $50–100B/yr [spec]; broader crypto↔fiat flows (remittances $685B to LMIC, CEX fiat on-ramps multi-$T) are *not* the addressable market for a courier-cash product.

| Scenario | Annual volume | Protocol revenue @50 bps | Collateral needed |
|---|---|---|---|
| Niche launch — one city, early adopters | $1–5M | $5–25K/yr | $5–20K locked |
| City-scale success — premium market (e.g. Cairo/Lagos) | $50–100M | $250–500K/yr | $300K–$1M locked |
| 1% of strict P2P market | $0.5–1B | $2.5–5M/yr | $5–10M locked |

At 25 bps revenue halves; at 100 bps it doubles. Even the bull case needs only ~$10M of collateral — well within reach of a functioning USE/rsBTC ecosystem; the hard part is the operational network, not the capital.

The Reddit post's "billions in USE turnover from 1% of the cash market" is achievable only under the broad remittance-TAM definition and is not used here.

## 7. Competitive frame

- **CEX P2P (Binance et al.):** cheaper spreads in liquid markets, but requires KYC, exchange accounts, and counterparty ratings — exactly the trust/identity exposure this design removes. The wedge markets are where CEX P2P is banned, delisted, or premium-distorted (Nigeria 2024 is the template).
- **Haveno / Bisq:** non-custodial P2P with arbitrator multisig; closest trust-model analog. This design's differentiators: physical-cash courier leg with a courier-signed cash-collection record, oracle-verified settlement of the seller's USDT transfer, and collateralized insurance instead of dispute arbitration after the fact.
- **Informal Telegram dealers:** zero tooling cost, total counterparty risk. "Insured up to $X" vs "trust my rating" is the entire pitch.
- **Regulatory posture:** money-transmitter obligations attach to the operator touching fiat — the courier leg. The protocol itself is neutral vault/oracle tooling: it never touches fiat, never custodies user funds, and its fee is collected in-protocol. That separation is deliberate and should be preserved as the model evolves.

## 8. Risks to the model

- **Fee compression:** uninsured competitors price lower; the insurance premium only survives where counterparty risk is salient. Expect viability in premium/sanctioned/capital-controlled markets first, commodity markets never.
- **Dispute-payout losses:** vault payouts on user-side fraud are covered by collateral by construction. The oracle side is the honest loss case: the attestation alone releases the vault, so an oracle **error** (a false payment confirmation) now *does* release wrongly — and a **compromised** oracle can steal collateral outright; there is no on-chain defense in phase 1. The bound is operational, not cryptographic: deal-size caps while the oracle is centralized (`specs/oracle-integration.md` §5.3) keep any single false attestation small, and every attestation is publicly auditable, so oracle fraud is ex-post provable. Any residual loss lands on the oracle operator and reputationally on the protocol.
- **Courier risk:** courier + seller collusion gains nothing on paper — the claim a fake handoff record unlocks pays the *user* the seller's own collateral, and the handoff record is single-signed (courier key only), so there is no user-side half to extort or forge. The risk that remains is operational: a rogue courier signing records for cash never collected, or pocketing the cash and refusing to sign at the meeting (the user's sequencing rule — don't leave the meeting without the verified courier-signed record — is the primary defense). Mitigations are operational (courier vetting/bonding, GPS, route logging, dual control), not cryptographic — acknowledge in operator onboarding. Courier vetting is the trust boundary of the cash leg.
- **Oracle cost floor:** below a volume threshold, verification costs exceed fees → the deck's fee-floor problem recurs. Bootstrapping subsidy required (§9).
- **Regulatory perimeter drift:** if the protocol's fee collection or treasury becomes administratively identifiable with money transmission, the neutral-tooling posture weakens. Keep fee logic on-chain, parameter governance distributed.
- **Collateral-asset liquidity:** vault collateral must be sellable in a dispute; thin USE/rsBTC DEX depth caps deal size regardless of contract correctness.

## 9. Bootstrapping sequence

1. **Phase 1 — subsidize.** Zero or minimal protocol fee; treasury funds collateral incentives for first operators in one premium market. Ties directly to the deck's use-of-funds slide: audits, liquidity, integrations are the enabling spend.
2. **Phase 2 — monetize the pain.** Turn the fee on where uninsured trust is visibly expensive (premium markets). Price at 25–50 bps; expand operator count, not marketing spend — operators bring the users.
3. **Phase 3 — replicate.** Geographic expansion via operator onboarding tooling (the dashboard in `onramp-ux.md` §4), not consumer growth. Each new city is a collateral pool + courier network + local quotes.

## 10. Cross-references

- Contract design & trust models: `onramp-insurance.md`
- Product flows: `onramp-ux.md`
- Rosen economics, fee treasury, liquidity constraint: `rosen/deck.html` (slides 9–11)
- Vision context: `pillars.md` pillar 3
