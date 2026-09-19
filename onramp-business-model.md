# Insured Onramp — Business Model (Protocol/Marketplace Layer)

*Business model note for the vault-insured cash→USDT on-ramp described in `onramp-insurance.md`
(contracts & trust models) and `onramp-ux.md` (product): the buyer hands cash to the seller at
an in-person meeting and
receives USDT; the seller collects the cash and sends afterwards, locking the vault
collateral. Focus: the protocol layer — who pays whom, the fee model, capital dynamics, token
tie-ins, and honest volume scenarios. Figures marked [approx]/[spec] are not verified; see
`rosen/deck.html` slide 15 for the repo's sourcing discipline.*

## 1. Value chain and money flow

```
BUYER ──hands cash at the meeting───────┐
   ▲                                    ▼
   │                                    OPERATOR (USDT seller)
   │                                    ├── meets the buyer, collects the cash
   │                                    ├── pays meeting logistics + AML/ops costs
   │                                    └── keeps margin
   └────────────receives USDT at the quoted rate (spread)────────────┘

 PROTOCOL FEE — none since 2026-09-18 (the in-contract 25 bps fee was removed,
 owner decision, §2): no protocol-level take inside the vault contract today;
 revenue is undefined/deferred [spec]. Watchers/guards (oracle work) are launch
 infrastructure cost, not fee recipients.

 USE minters: lock ERG ──▶ mint USE ──▶ collateral pool (operators' vaults)
 Oracle: confirms the seller's USDT transfer to the buyer — its attestation alone releases the vault
 Rosen: provides the guard-threshold upgrade path (phase 2) and rsBTC wrapping (BTC leg)
```

- The **buyer** pays the visible price: the quoted cash→USDT rate, whose premium over market is the
  spread. The insurance is what justifies paying it to a no-reputation counterparty.
- The **operator** earns the spread minus meeting-logistics/ops/fee costs. As the USDT seller it also carries
  the capital cost of the locked vault collateral (§4). Operator economics set the ceiling on the
  protocol's take rate — the protocol tax must fit inside a spread buyers already pay elsewhere.
- The **protocol** charged a per-deal fee in basis points of deal volume, collected
  atomically inside the vault contract at release, reclaim, or claim — **removed
  2026-09-18** (owner decision, §2). There is no protocol-level take today;
  protocol revenue is undefined/deferred [spec].
- **Watchers/guards** (the oracle layers) do the verification work that makes the
  USDT/XMR legs possible — on-ramp: confirming the seller's USDT transfer to the
  buyer, whose attestation alone gates the vault's release. At launch they are paid
  from the protocol side as fixed infrastructure cost, not from a per-deal fee
  (`specs/oracle-integration.md` §6); their ongoing compensation is an open
  question [spec], deferred with the fee model.

## 2. Protocol revenue model

**The in-contract protocol fee was removed on 2026-09-18 (owner decision).** The
design had a flat per-deal fee in bps of volume, deducted from the vault payout
path (release to seller or claim to buyer — same economic effect), hardcoded in
the contract scripts at 25 bps since 2026-09-17 and governance-adjustable only by
redeploying the contracts. The removal took the fee out of the contracts
entirely: no treasury output on any collateral-moving path, the recipient is
paid in full, and the ~400-base-unit fee-rounding floor on deal size went with
it. **Protocol-level revenue is now undefined/deferred [spec]** — no replacement
economics are asserted in this document; how (and whether) the protocol
monetizes volume is an open question for later, to be decided with evidence
rather than hardcoded upfront.

**The seller's spread is untouched** and remains the seller's margin: it is the
buyer-visible price premium over market rate, set quote-by-quote by the
operator, and it is not a protocol revenue line. (Its display in the buyer app
UI was removed 2026-09-18 for presentation reasons; the spread itself stays.)

*Pre-removal design, kept for the record:* the fee was denominated in the
collateral asset (USE for USDT deals, rsBTC for BTC deals) to avoid forcing
operators to source a second token, and its distribution was split between
watchers/guards, a protocol treasury, and an optional RSN sink (§3). All of
that presupposed a fee; with the fee gone it is history, not a plan.

**Pricing headroom.** The insurance premium only sells if it beats the *trust tax* buyers pay today
on cash→USDT premiums [approx]:

- India P2P ~0.5–2% [approx]
- Egypt ~5–15% during the 2024 dollar shortage [approx]
- Nigeria 30–70% premium episode, Feb–Mar 2024 [approx]
- Iran single digits, spiking ~10%+ under stress [approx]

The constraint is the seller's spread, not a protocol fee (the in-contract 25 bps
fee existed only 2026-09-17 → 2026-09-18 before its removal): in premium markets the
operator spread is 5–30× what a protocol fee would have been, so pricing room exists;
collateral availability is the binding constraint (§4).

**Cost floor (now a subsidy question).** Without a per-deal fee there is no fee
income to cover oracle operating costs (observer infrastructure in phase 1, the
guard set's at phase 2). Verification is a fixed-cost layer that needs either
volume-linked revenue or subsidy — which no longer exists by design — so
bootstrapping means the protocol side eats these costs (§9). This mirrors the
deck's slide-10 fee-floor analysis with the fee set to zero.

## 3. Fee distribution and incentive alignment

*Superseded by the 2026-09-18 fee removal: with no in-contract fee there is
nothing to distribute. The split below was the pre-removal starting point
(governance-adjustable), kept for the record — any future protocol revenue
model [spec] would reopen these questions, not inherit the answers.*

| Recipient | Share | Rationale |
|---|---|---|
| Watchers/guards (oracle) | 40–60% | They do per-deal verification work and carry slashable collateral |
| Protocol treasury | 25–45% | Funds audits, operator tooling, collateral subsidies |
| RSN sink (burn or staking reward) | 0–15% | Optional value-accrual leg; see §5 |

Parallels the deck's fee-treasury model (slide 9): fees accumulate in a protocol treasury
denominated in the operating asset, bridging into recurring revenue only when volume exists.

**Collusion economics.** The attack to worry about on-ramp: the oracle attests to a fake digest of
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

1. **Collateral depth, not buyer demand, is the binding constraint** — identical to the deck's core constraint slide (slide 10). Every growth plan is a collateral-onboarding plan.
2. The protocol scales linearly in TVL; there is no operating leverage from float. This is a feature (no fractional-reserve risk) and a bug (capital-heavy).
3. Collateral yield for operators comes from deal spread, not from the protocol — so operator ROI must clear their cost of capital with utilization well under 100%. Thin early liquidity → wide spreads → premium-market-first sequencing (§9).

## 5. Token tie-ins (stated honestly)

- **USE demand:** every vault locks USE (USDT leg) — a structural demand sink. USE minting locks ERG as over-collateralized backing, so sustained vault demand is a genuine tailwind for ERG. It is reflexive: minting capacity grows with ERG price, not independent of it. Treat as tailwind, not flywheel — the collateral asset does not re-rate itself; per the analysis elsewhere in this repo's working notes, claims of "tens of billions of ERG locked" fail arithmetic against ERG's actual market cap and are excluded here.
- **RSN demand:** if watchers need RSN reporting permits and fees partially settle in RSN, protocol volume creates RSN buy pressure — the same activity-gated demand model the deck uses for bridge fees. Optional; the deck's stance (slide 14) — "optionality, not a line in the model" — applies verbatim.
- **ERG demand:** second-order, via USE minting above.

## 6. Volume scenarios

Anchored in measured data: Venezuela alone did ~$1.39B of Binance P2P volume in one month (~$16.6B/yr annualized) [solid, mid-2025]; strict global P2P scale is order $50–100B/yr [spec]; broader crypto↔fiat flows (remittances $685B to LMIC, CEX fiat on-ramps multi-$T) are *not* the addressable market for an in-person-cash product.

| Scenario | Annual volume | Collateral needed |
|---|---|---|
| Niche launch — one city, early adopters | $1–5M | $5–20K locked |
| City-scale success — premium market (e.g. Cairo/Lagos) | $50–100M | $300K–$1M locked |
| 1% of strict P2P market | $0.5–1B | $5–10M locked |

(An earlier version of this table carried a "protocol revenue @50 bps" column;
with the in-contract fee removed 2026-09-18 there is no protocol revenue to
project — protocol-level monetization is undefined/deferred [spec], and no
replacement figures are asserted here.)

Even the bull case needs only ~$10M of collateral — well within reach of a
functioning USE/rsBTC ecosystem; the hard part is the operational network, not
the capital.

The Reddit post's "billions in USE turnover from 1% of the cash market" is achievable only under the broad remittance-TAM definition and is not used here.

## 7. Competitive frame

- **CEX P2P (Binance et al.):** cheaper spreads in liquid markets, but requires KYC, exchange accounts, and counterparty ratings — exactly the trust/identity exposure this design removes. The wedge markets are where CEX P2P is banned, delisted, or premium-distorted (Nigeria 2024 is the template).
- **Haveno / Bisq:** non-custodial P2P with arbitrator multisig; closest trust-model analog. This design's differentiators: in-person cash collection with a seller-signed cash-collection record, oracle-verified settlement of the seller's USDT transfer, and collateralized insurance instead of dispute arbitration after the fact.
- **Informal Telegram dealers:** zero tooling cost, total counterparty risk. "The seller has $X locked that you can claim" vs "trust my rating" is the entire pitch.
- **Regulatory posture:** money-transmitter obligations attach to the operator touching fiat — the in-person cash-collection leg. The protocol itself is neutral vault/oracle tooling: it never touches fiat, never custodies buyer funds, and — since the 2026-09-18 fee removal — takes no in-protocol cut either. That separation is deliberate and should be preserved as the model evolves.

## 8. Risks to the model

- **Fee compression:** uninsured competitors price lower; the insurance premium only survives where counterparty risk is salient. Expect viability in premium/sanctioned/capital-controlled markets first, commodity markets never.
- **Dispute-payout losses:** vault payouts on buyer-side fraud are covered by collateral by construction. The oracle side is the honest loss case: the attestation alone releases the vault, so an oracle **error** (a false payment confirmation) now *does* release wrongly — and a **compromised** oracle can steal collateral outright; there is no on-chain defense in phase 1. The bound is operational, not cryptographic: deal-size caps while the oracle is centralized (`specs/oracle-integration.md` §5.3) keep any single false attestation small, and every attestation is publicly auditable, so oracle fraud is ex-post provable. Any residual loss lands on the oracle operator and reputationally on the protocol.
- **Cash-leg risk:** the handoff record is single-signed under the seller key — the same R5 key that reclaims the collateral — so a fake record is self-defeating on paper: the claim it unlocks pays the *buyer* the seller's own collateral. The risk that remains is behavioral and operational: a seller who pockets the cash and refuses to sign at the meeting (the buyer's sequencing rule — don't leave the meeting without the verified seller-signed record — is the primary defense), or a compromised seller key signing records for cash never collected. Mitigations are procedural and operational (the unmissable meeting rule, seller vetting/bonding, deal-identity blacklisting, key hygiene), not cryptographic — acknowledge in operator onboarding. The meeting itself is the trust boundary of the cash leg.
- **Oracle cost floor:** below a volume threshold, verification costs exceed any protocol revenue —
  and there is none since the 2026-09-18 fee removal, so this is a pure subsidy question at launch.
  The deck's fee-floor problem recurs exactly; bootstrapping subsidy required (§9).
- **Regulatory perimeter drift:** now that the protocol takes no in-contract fee, the "protocol
  revenue plumbing becomes identifiable with money transmission" concern is moot at launch;
  if a future revenue model [spec] reintroduces one, keep it administratively separate from the
  vault/oracle tooling and keep parameter governance distributed.
- **Collateral-asset liquidity:** vault collateral must be sellable in a dispute; thin USE/rsBTC DEX depth caps deal size regardless of contract correctness.

## 9. Bootstrapping sequence

1. **Phase 1 — subsidize.** No protocol fee (the in-contract fee was removed 2026-09-18, §2); the
   protocol side funds collateral incentives for first operators in one premium market as pure
   infrastructure spend. Ties directly to the deck's use-of-funds slide: audits, liquidity,
   integrations are the enabling spend.
2. **Phase 2 — monetize the pain [spec, unresolved].** Whether and how the protocol takes revenue
   where uninsured trust is visibly expensive (premium markets) is an open question — the old plan
   ("turn on a 25–50 bps in-contract fee") is retired with the fee removal, and no replacement
   economics are asserted here. What stands regardless: expand operator count, not marketing spend —
   operators bring the buyers.
3. **Phase 3 — replicate.** Geographic expansion via operator onboarding tooling (the dashboard in `onramp-ux.md` §4), not consumer growth. Each new city is a collateral pool + sellers able to run meetings + local quotes.

## 10. Cross-references

- Contract design & trust models: `onramp-insurance.md`
- Product flows: `onramp-ux.md`
- Rosen economics, fee treasury, liquidity constraint: `rosen/deck.html` (slides 9–11)
- Vision context: `pillars.md` pillar 3
