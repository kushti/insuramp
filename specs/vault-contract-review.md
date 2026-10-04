# Vault Contract Review — Findings (2026-09-26)

*An adversarial review of the shipped phase-1 vault contracts — `contracts/src/main/ergoscript/`
`vault_funded.es`, `vault_payment_proven.es`, `oracle.es` — against `specs/vault-contract.md`,
`specs/deal-protocol.md` and the Kotlin layer that builds spends for them
(`apps/core/ergo/`, `backend/`). Depends on `vault-contract.md` (the owned spec: registers,
paths, parameters, test matrix) and `deal-protocol.md` (wire formats, state machine); feeds back
into both. Nothing in this file is normative — where a finding contradicts the spec, the spec is
still the source of truth until its owner edits it; §8 lists the exact edits this review believes
are needed.*

*Method: every claim marked [solid] was verified by compiling the shipped `.es` sources with
sigma-state 6.0.6 and running spend spends through the same prover/interpreter driver the §7
matrix uses (`contracts/src/test/kotlin/p2pgate/contracts/VaultFixture.kt`), plus reading the
tx builders, the deal state machine and the app-side record verifier. The probe suite used for
the [solid] items was scratch and has been removed; §7 offers to land it as numbered §7 tests.
Baseline before and after the review: `:contracts:test` green, 55 tests, no source changes.*

## 1. Verdict

The contracts are **sound for what they actually claim**. No path lets the buyer take `FUNDED`
collateral, nothing moves collateral without the matching key, cross-deal replay is blocked on
every path, and token conservation on payout paths is exact. The release direction's total trust
in the phase-1 oracle is documented honestly (`vault-contract.md` §9), and the deliberate
weaknesses there are labelled as such in the test matrix (test 20 pins the NFT-custody semantic,
test 46 pins the seller co-signature).

The material weakness is **not** in the script logic. It is that the *claim direction's
availability* is attacker-controllable: the buyer's only on-chain remedy is time-boxed by a
timeout the seller side sets at funding, and the seller also controls the timestamp inside the
handoff record that makes the claim possible at all. Post-timeout, the buyer's claim and the
seller's reclaim are simultaneously valid spends of the same box, so the buyer's protection is a
mempool race it can lose. §3 states this; the current spec does not.

The remaining findings (§4) are small: one permanently-brickable-box hazard with no
funding-time guard, one false invariant that three files rely on, one payout path that ignores
ERG value, and two spec sentences that misdescribe the script.

## 2. Summary table

| # | Finding | Severity | Kind |
|---|---|---|---|
| P1 | Post-timeout, claim-open and reclaim are both valid spends of the same box — the buyer's remedy is a race the seller can win | **high** | design / docs |
| P2 | The seller picks the claim window's far end (record timestamp); the app checks *age*, not *remaining validity* | **high** (cheap fix) | **resolved 2026-10-04** (the freshness window is gone; the late-meeting core lives on under P1) |
| P3 | Claim freshness is judged against the **miner's** block timestamp | low | **resolved 2026-10-04** (moot — the check is gone) |
| P4 | The claim-open is free and permissionless — it can force a routine release into a contest | medium | design |
| C1 | The stated reason for the branch structure is false: sigma `val`s are lazy | medium (doc) | docs |
| C2 | A malformed R5 bricks the vault permanently; nothing validates the curve point at funding | medium | code |
| C3 | No ERG conservation on any payout path (path B is the only one that checks value) | low | contract |
| C4 | The proven script is pinned byte-exactly inside every FUNDED box → no migration path for phase 2 | low (known) | design note |
| C5 | Two sources of truth for the oracle NFT id (R7 per box vs. compile-time pin) | low | **resolved 2026-10-03** (PROVEN R9 per-box pin) |
| C6 | The 52-byte handoff-record length is not enforced in-script | low | contract |
| C7 | §3.3 claims path B checks the record's amount/currency against the deal terms — it does not | low (doc) | docs |

## 3. Protocol-level findings

### P1 — Post-timeout the claim-open and the reclaim are both live [solid]

> **Superseded in part 2026-10-04.** The FUNDED box now takes an explicit action byte in
> context var 0 (`ACTION_CLAIM` / `ACTION_RECLAIM` / `ACTION_RELEASE`, see
> `vault-contract.md` §8.5). The *observation below stands* — post-timeout a claim and a
> reclaim are still both live — but the line-number citations and the branch-ordering
> discussion no longer describe the shipped script: the paths no longer compete, because the
> spender names which one is being invoked. What did change as a side effect: a post-timeout
> **release** is no longer spent as an attestation-free reclaim (old test 51), so the oracle's
> signal cannot be skipped by a nominally-release tx.

`vault_funded.es` path A and path B: once `HEIGHT > SELF.R8`, **path A** (`proveDlog(sellerKey)`, full
collateral to any address) and **path B** (the seller-signed handoff record, no seller signature
in the tx) are *both* valid spends of the same input at the same height. Test 49 pins this as a
feature — "late disputes must stay possible" — and it is possible on chain. What the spec does not
say is that the claim is only worth anything if it **confirms before the seller's reclaim**, and
that ordering is not the protocol's to decide:

- the operator runs an automatic sweep — `BackendApp.kt:109-115` calls `watcher.tick(now)` then
  `vaultManager.tickReclaims(now)` on a fixed-delay loop — and `tickReclaims` builds and
  broadcasts a reclaim for any deal the mirror still calls `FUNDED` past
  `fundedAt + RECLAIM_TIMEOUT` (`VaultManager.kt:237-250`);
- `watcher.tick` only knows about **mined** boxes, so a claim-open that is in the mempool does not
  stop the sweep in the same interval;
- a seller/operator watching the mempool can therefore see a buyer's post-timeout claim-open and
  front-run it with the (already-eligible) reclaim.

The exclusion of reclaim-from-`PAYMENT_PENDING` that makes the routine case safe is enforced
**only off-chain** — `DealStateMachine.kt:126-141` rejects it, and `RECLAIMABLE_STATES`
(`VaultManager.kt:415`) omits `PAYMENT_PENDING` — while the *operator* is the holder of the R5 key.
So the claim direction's security is custodial, not cryptographic. `vault-contract.md` §3.3 does
say the contract "relies on `RECLAIM_TIMEOUT` plus operational deterrence"; the missing half is
that the deterrence is a race the operator wins on its own infrastructure.

Root cause: ErgoScript cannot prove a negative ("no record was ever signed"), so *any*
timeout-based reclaim is structurally racy against a claim-open. `timeoutHeight` is chosen by the
funder at funding (`OperatorTxBuilder.kt:211`, `currentHeight + reclaimTimeoutBlocks`).

Options, in the order I would take them:

1. **Make the record a veto on reclaim.** Add a buyer-signed *no-show acknowledgment* as a
   path A alternative condition: reclaim requires `proveDlog(buyerKey)` (the buyer confirms no
   meeting happened) **or** a long final backstop height (e.g. timeout + 30 days) after which the
   seller reclaims unilaterally. A buyer holding a record simply never signs, so the race
   disappears; the backstop preserves the ghosting-buyer case that path A exists for. Cost: one
   more signing path, one more app/UX step, and the honest no-show flow gains a buyer signature.
2. **Keep the design, fix the documentation.** State in §3.3 and §9 that a post-timeout
   claim-open is a race, that the operator's sweep is the seller's side of it, and that the
   buyer's remedy requires the claim to confirm first. Cheapest, and it stops the design being
   read as stronger than it is.
3. **Operational only** (publish reclaim intent, wait out a dispute window before broadcasting).
   Weakest — it does not survive a hostile operator, which is the case that matters.

### P2 — The seller picks how long the claim window lasts [solid]

> **Resolved 2026-10-04.** The freshness window (and the `tsMs` var, and the app-side
> max-age gate) is gone entirely — `vault-contract.md` §8.8. There is no expiry for the
> seller to steer anymore. The deeper exposure this finding pointed at — a seller holding
> the meeting until just before `timeoutHeight` — survives unchanged and is P1's
> territory: the record's timestamp is now signed evidence only.

Path B's freshness is `tsMs > CONTEXT.preHeader.timestamp - %%HANDOFF_RECORD_MAX_AGE_MS%%`
(`vault_funded.es:85-88`), and the record's timestamp lives at bytes 48..52 of the message the
seller signs. So the *seller* chooses when the record expires.

`HandoffRecordVerifier.kt:49-52` rejects only `age > HANDOFF_RECORD_MAX_AGE`; it never checks
**remaining** validity. A record with `age = 3h59m` therefore passes every app-level check and
leaves the buyer roughly a minute to land the claim in a block. Paired with P1 — the reference
backend lets the seller sign at any point while the deal is `FUNDED`
(`Server.kt:407-418`), so the meeting can be held until just before `timeoutHeight` — the
buyer's effective on-chain window can be driven to approximately zero while the record remains
fully valid and app-accepted.

The shipped backend happens to close this by construction: it builds the record itself with
`Instant.now()` (`Server.kt:598-603`), so the seller cannot move the timestamp. The exposure is
that the *buyer app* — the last gate before cash changes hands, and the component the design puts
in front of the buyer — does not defend against it, and the design explicitly contemplates the
seller signing under R5 from its own client.

Fix (one line, app layer): require a minimum remaining validity, e.g. reject records with
`age > HANDOFF_RECORD_MAX_AGE - safetyMargin` (an hour is ample; the claim should be broadcast
immediately after the meeting). Symmetrically, the seller-side signer should refuse to sign a
record it is stamping with a stale clock. Note this is a mitigation, not a cure: a seller who
signs a *fresh* record one minute before `timeoutHeight` has the same problem, and only P1's
option 1 closes that.

### P3 — Freshness is judged against the miner's clock [solid]

> **Resolved 2026-10-04.** Moot — the freshness check no longer exists (§8.8), so no
> claim validity depends on `CONTEXT.preHeader.timestamp`.

The same claim transaction, same height, same context variables, flips from valid to invalid when
only the including block's timestamp moves past the 4h boundary — the check reads
`CONTEXT.preHeader.timestamp` (`vault_funded.es:87-88`). Ergo's block-time adjustment window
bounds how far a miner can push this [approx], but the miner still chooses which side of the
boundary a marginal claim lands on. One more reason the P2 margin matters, and a sentence the spec
should carry in §5.

### P4 — The claim-open is free, so it can force a contest [solid]

The buyer holds a valid record from the moment of the meeting, and the claim-open needs no
signature of the buyer's own (path B's only gate is the record). So the buyer can convert
`FUNDED → PAYMENT_PROVEN` at any height, invalidating the seller's in-mempool release, and the box
is then no longer reclaimable at all. The seller recovers via path C′, but only through an oracle
round trip inside 360 blocks. The buyer gains fee-cost nuisance plus a tail risk if the contest
does not land; the seller loses its timeout fallback. This is a consequence of design choice 3 in
v2 (the buyer-half signature and the receipt signature were both deliberately removed —
`vault-contract.md` §8.4) and it should be priced in: **any `PAYMENT_PROVEN` sighting must be
treated by the seller as an emergency contest**, not as something to observe. The dashboard's
dispute inbox is the right surface for that rule; `specs/seller-dashboard.md` should say it.

## 4. Contract-surface findings

### C1 — The documented reason for the branch structure is false [solid]

Three places state that *"the proof reducer evaluates every val of every block it enters"*:
`vault_funded.es:44-53`, `vault_payment_proven.es:35-39`, and `specs/vault-contract.md` §5's
sigma-6 constraint list. It does not. A `PAYMENT_PROVEN` box whose R6 is **not a valid curve
point** still spends through path C′ — the outer block's
`val buyerKey = decodePoint(SELF.R6[Coll[Byte]].get)` is never forced on that path. Sigma `val`s
are lazy, which is the only reason the current code is safe.

That matters beyond pedantry: if the invariant were true, an R6 problem would brick the seller's
escape hatch along with the buyer's payout. The rules in §5 are still *correct as rules* — the
actual failure mode is `.get` on an undefined context variable throwing, and `isDefined` is the
safe way to test presence — but the justification is wrong, and three files currently lean on it.
Correct the wording in all three; do not restructure the branches on the strength of it.

### C2 — A malformed R5 bricks the vault forever, and funding does not validate it [solid]

`decodePoint(SELF.R5[Coll[Byte]].get)` sits in the outer block of **both** scripts
(`vault_funded.es:55`, `vault_payment_proven.es:41`). A FUNDED box whose R5 is 33 bytes with an
`0x02`/`0x03` prefix but not a curve point throws on *every* path — reclaim, release, claim-open
and contest alike. The collateral is unrecoverable on chain, forever.

Nothing prevents such a box from being created:

- `DealTerms.requireCompressedKey` (`DealTerms.kt:154-160`) checks length 33 and the
  `0x02`/`0x03` prefix only — it never decodes the point;
- `OperatorTxBuilder.buildFund` never decodes it either, it just writes `dealTerms.sellerPubKey`
  into R5 (`OperatorTxBuilder.kt:101`).

R6 is worse than self-inflicted: `buyerPubKey` originates in the **buyer app** and lands in R6, so
a buggy or hostile buyer can have the operator mint an unclaimable vault. The same hazard applies
to a wrong-typed R8 (`SELF.R8[Long].get`) or an empty token list (`SELF.tokens(0)`), both of which
throw on all paths.

Fix: one `require` per key in `buildFund` using the existing `ErgoValues.decodePoint` (it already
throws on off-curve bytes) — the operator learns at funding time instead of the collateral being
lost later. This is the highest value-per-line item in the review.

### C3 — No ERG conservation on any payout path [solid]

`payoutOk` in both scripts checks tokens only. A path-A spend with a **0-ERG** payout output is
accepted, so the box's entire ERG balance can be paid to the miner in the same transaction as a
payout — on path D that is the buyer sweeping dust the seller funded. Path B is the only path that
checks value (`OUTPUTS(0).value == SELF.value`, `vault_funded.es:113`), which is why the
`PAYMENT_PROVEN` box carries the dust forward and the payouts do not have to.

Economically this is milli-ERG, so it is not a theft path. It is an asymmetry and a doc gap:
`vault-contract.md` §3.1 says the box holds "enough to cover the box itself and the two future
spends… surplus is reclaimed by the seller", and nothing enforces that. Either add
`OUTPUTS(0).value >= SELF.value` to A/C/C′/D (cheap, and it makes the invariant total), or state
in §3.1 that ERG is unconstrained on payout paths by design.

### C4 — The proven script is pinned byte-exactly, so there is no migration path [solid]

`OUTPUTS(0).propositionBytes == %%PAYMENT_PROVEN_SCRIPT%%` (`vault_funded.es:105`) means a claim
can only ever open into the one proven tree that was compiled at funding time — including its
compile-time `ORACLE_NFT_ID`. A claim-open into any other proven script is rejected (verified),
which is the right design: it makes "the box's proposition bytes *are* the deal's on-chain state"
unforgeable, and it removes the C′/D NFT-pin mismatch class.

The cost is an upgrade constraint the spec does not state. Phase 2 (test 45, `vault-contract.md`
§7) rewrites `vault_payment_proven.es` to check a guard-set box instead of the phase-1 NFT. Every
in-flight FUNDED box can then only open claims into the **phase-1** proven tree, so after the
upgrade the operator must keep compiling and serving the phase-1 trees *and* keep the phase-1
oracle NFT attestable, or those vaults become claimable-but-unresolvable (the seller can recover
only via path A, after the timeout). Add an explicit "no in-place migration" note to §8.4 and a
runbook item: ship the phase-2 parameter set alongside the phase-1 one, and keep the phase-1
oracle live until every phase-1 FUNDED box is terminal.

### C5 — Two sources of truth for the oracle NFT id [solid]

> **Resolved 2026-10-03.** The PROVEN box now pins the NFT per-box in R9, copied from the
> FUNDED box's R7 at claim-open (path B enforces `OUTPUTS(0).R9 == SELF.R7`; tests 60–61).
> There is one source of truth again — the FUNDED box's R7 — and neither tree embeds the
> NFT. The operational note below still stands: a wrong pin strands the release/contest
> paths, never the claim (test 44).

The FUNDED contract reads the pin per box (`SELF.R7`, `vault_funded.es:138`); the PROVEN contract
pins it at compile time (`vault_payment_proven.es:84`). This is not a free choice — it falls out
of the register budget once the box is split (§5.3 below) — and `ErgoContracts.compile` builds
both trees from one parameter set, with the proven tree embedded into the funded tree, so the
tested scripts are the deployed ones.

What is missing is an operational invariant: **the oracle NFT id is immutable for the life of a
deployment**. Tests 43/44 already show the failure mode — a vault whose R7 does not match the
embedded pin is still claimable but can never be released, and its contests require the *other*
NFT. Rotating the NFT (a new deployment, a new network) therefore strands every in-flight box.
State it in §3.3 next to the existing "an operator must deploy both contracts from one parameter
set" note.

### C6 — The 52-byte record length is not enforced [solid]

`deal-protocol.md` §3.2 fixes the handoff record at 52 bytes and `HandoffRecord.decode` enforces
that app-side, but the contract does not: a 60-byte seller-signed record (the extra bytes inside
the signed message) opens a claim normally. No attacker gains — only the seller can sign, and the
claim pays the buyer — but the on-chain evidence blob can deviate from the documented format,
and R8's record id is computed over the carried bytes, so the dashboard's evidence view will show
a record the decoder rejects. One condition in the path B guard (`msg.size == 52`) closes it.

### C7 — §3.3 misdescribes what path B checks [solid]

`vault-contract.md:181-182` says path B checks "the handoff record's `dealId == R4.dealId` **and its
amount/currency match the deal terms** hashed into R4". Only the `dealId` is checked
(`msg.slice(5, 37) == SELF.R4`, `vault_funded.es:80`); the record's amount and currency are signed
but compared to nothing — and they *cannot* be compared to anything, because the deal terms are
only present on chain as a hash. A record for `1 ZZZ` instead of `250_000 EGP` opens a claim
(verified). There is no attacker gain (the seller chose to sign it, and the payout is the buyer),
but the spec sentence is wrong, and the next reader may rely on it. The same sentence's own
continuation gets it right — "the `dealId` equality is the binding" — so this is a trimming job.

## 5. Why there are two `vault_*.es` contracts at all

They are not two versions of the same contract and not a layering convenience: they are **two
states of one state machine**, and the box's proposition bytes are the state variable. Three
independent reasons force the split, and the third one is an accident that later findings bill for.

### 5.1 The maturation clock must be stamped on chain, and a register cannot be written twice

`CLAIM_MATURATION` is measured from *the claim landing on chain* — not from the meeting, and not
from the handoff record's timestamp. The reason is the one the design docs state: ErgoScript
cannot timestamp a past off-chain event, and the only on-chain clock a box can read is `HEIGHT`
(`vault_funded.es:109` writes `HEIGHT` into R7; `vault_payment_proven.es:64` reads
`HEIGHT > proofH + CLAIM_MATURATION_BLOCKS`). A register, however, is fixed when the box is
created — and the FUNDED box is created at *funding*, when the claim does not exist and its height
is unknowable.

So the claim-open cannot be a spending path of the FUNDED box; it has to be a **transition that
creates a new box with a new register** (`vault_funded.es:104-113`). That is what
`vault_payment_proven.es` is: the same collateral, the same keys, a fresh `proofHeight`, plus the
record id for the evidence view. One height register, two meanings, two lifetimes:
`R8 = timeoutHeight` (measured from funding) in one tree, `R7 = proofHeight` (measured from the
claim) in the other. Each is written exactly once, by the party whose clock it measures.

### 5.2 The split is also what makes the seller's reclaim right *disappear*

Path A is `HEIGHT > SELF.R8` plus `proveDlog(sellerKey)` — unconditionally available once the
timeout passes. If the PROVEN contract kept it, a seller could reclaim out of a box the buyer had
successfully claimed, which is precisely the theft path the off-chain state machine forbids
(`DealStateMachine.kt`: reclaim is rejected from `PAYMENT_PENDING`, and "reclaim path no longer
exists" from `CLAIM_OPENED`/`CLAIMABLE`).

**A script cannot remove one of its own spending paths.** The only way to make the reclaim right
end at the moment the claim begins is to change the script — which is exactly what the claim-open
does. `vault_payment_proven.es` has no path A at all, and the instant the claim confirms, the
seller's fallback is gone and only the buyer's maturation clock and the seller's oracle contest
remain. The buyer's core on-chain protection *is* the script change; that is the strongest reason
the two-contract shape is right.

### 5.3 What the split costs: register pressure, then the pin asymmetry

Both trees need R4 `dealId` and R5/R6 keys — three registers, irreducible. On top of that the
FUNDED box needs the oracle NFT id *and* `timeoutHeight`; the PROVEN box needs `proofHeight` *and*
the record id. Ergo boxes have R4–R9 and no R10 (`vault-contract.md` §3.2), so:

- the FUNDED tree can pin the oracle NFT **per box** in R7 and read the timeout in R8;
- the PROVEN tree has no register left — R7 is `proofHeight`, R8 the record id — so the oracle NFT
  is pinned **at compile time** instead (`vault-contract.md` §8.3 item 3).

That asymmetry is finding C5, and it is why the two trees can disagree about which oracle is
authoritative. The byte-exact pin on the transition (C4) and the doubled branch-discriminator
maintenance (the 2026-09-26 fix had to be reasoned about in both trees) are the other two
invoices for the split. They are cheap; the design is still right.

### 5.4 Why not one script

For completeness, the single-script alternatives and why each loses:

- **Overload R8** (`HEIGHT > R8` meaning "timeout" in one state and "maturation" in the other):
  needs a state discriminator anyway, and R8 still has to be rewritten on the claim-open — the
  mutation the split exists to avoid.
- **Two height registers in one box** (R8 timeout, R9 proof, R9 absent until the claim): an absent
  register throws on `.get` in every path, so each path needs `isDefined` guards and the tree
  becomes a two-dimensional branch product (state × path) with a larger proof; and it *still*
  cannot remove path A after the claim, so finding P1's remedy (a buyer-signed no-show, or a
  longer backstop) would have to live in the same tree as the racing reclaim.
- **Off-chain state only** (one contract, the backend decides): that is a custodial vault — the
  whole point of the exercise is that the collateral rules are on chain.

The split is the cheapest correct encoding available in ErgoScript. It is worth stating that
explicitly in `vault-contract.md` §4, because "why two contracts" is currently answered only
implicitly, and the C4/C5 costs look like oversights rather than consequences.

## 6. Checked and found sound

- **No unauthorised extraction.** The buyer has no path on the FUNDED box; paths A/C/C′ need the
  seller's key, path D needs the buyer's; the attestation alone never directs funds (test 46).
- **Cross-deal replay** is blocked on every path: `dataInput.R4 == SELF.R4` on C/C′ and
  `msg.slice(5, 37) == SELF.R4` on B (tests 11, 36, 37, plus the dealId's inclusion of both keys
  and the nonce per `deal-protocol.md` §3.1).
- **The Schnorr check is sound**: the challenge binds the public key
  (`blake2b256(a‖msg‖sellerKey)`, `vault_funded.es:93-97`), so no key substitution; the signed
  two's-complement scalar conventions are covered by tests 39–41, and no forgery path exists
  without the R5 private key.
- **Token conservation on payouts is exact** — `OUTPUTS(0).tokens(0)` must carry the whole
  collateral under the same id, so no partial drain, no split, no second-output siphon; path B
  cannot be routed into a non-PROVEN output (test 12) and its output's proposition is byte-pinned
  (verified).
- **The D/C′ discriminator is sound in both directions**: a stray data input diverts a claim into
  C′, which then demands the seller's key (test 52), and C′ stays reachable past maturation
  (test 50) so an honest-but-slow seller can always counter.
- **Numeric typing is correct.** The two scripts write the height checks differently
  (`HEIGHT > timeoutH` vs `HEIGHT.toLong > proofH + N.toLong`); dumping both compiled trees shows
  `GT(Upcast(Height → SLong), …)` in each — the register is never truncated to Int.
- **Off-chain guards are consistent with the contract**: reclaim is refused from
  `PAYMENT_PENDING` and from `CLAIM_OPENED`/`CLAIMABLE`, claim-open requires a collected handoff,
  and the sweep ingests chain facts before it proposes reclaims.
- **Production compilation matches the tested scripts** — `ErgoContracts.compile` compiles the
  proven tree first and embeds its bytes into the funded tree, exactly as the fixture does, so
  the §7 matrix covers what actually deploys.
- ~~Dead substitution worth deleting: `%%HANDOFF_RECORD_MAX_AGE_MS%%` is injected into
  `vault_payment_proven.es` by both `ErgoContracts.compile` and `VaultFixture` but is never used
  by that script~~ — **done 2026-10-04**: the freshness window was removed entirely (§8.8 of
  the spec), so the substitution is gone from both scripts, both compile maps, and
  `ContractParams`.

## 7. Suggested fix order

| Order | Item | Effort | Why first |
|---|---|---|---|
| 1 | **P2** — minimum remaining-validity margin in `HandoffRecordVerifier` | one line | closes a theft path for a buyer-side component that is already the last gate |
| 2 | **P1** — choose: buyer-signed no-show + backstop, or document the race in §3.3/§9 | design | the only finding that changes the protocol's guarantees; everything else is hygiene |
| 3 | **C2** — validate both keys in `OperatorTxBuilder.buildFund` | ~3 lines | prevents a permanently unspendable vault |
| 4 | **C1 + C7** — correct the false lazy-eval invariant and the §3.3 path-B sentence | prose | three files currently reason from a wrong premise |
| 5 | **P4** — seller-dashboard rule: any `PAYMENT_PROVEN` sighting is an emergency contest | prose + UX | turns a known grief into a procedure |
| 6 | **C3 / C6** — optional hardening (`value >= SELF.value`, `msg.size == 52`) | small | consistency and evidence-blob integrity |
| 7 | **C4 / C5** — record the no-migration and NFT-immutability invariants | prose | the phase-2 upgrade depends on knowing them |

Landable as tests: the ten probes behind the [solid] markers above. They slot into `§7` as new
numbered cases (P5–P9/C1–C2 style), which means the test counts in `AGENTS.md`, `specs/README.md`
and `vault-contract.md` §7's status paragraph need updating in the same change, per the repo's
one-change rule. Still unlanded — the second pass (§10) added a second probe batch of the same
shape, and only its §10.1 tracker fix landed, as `:core:ergo` tests rather than §7 cases (it is
off-chain, not a contract behavior).

## 8. Suggested spec edits (for the spec owner — not applied here)

This review changed no spec text. The edits it believes are needed, with targets:

1. `specs/vault-contract.md:181-182` — drop "and its amount/currency match the deal terms hashed
   into R4" from path B's in-script conditions (C7).
2. `specs/vault-contract.md` §5, sigma-6 bullet — replace "The proof reducer evaluates every val
   of every block it enters" with the lazy-`val` reality plus the actual failure mode (`.get` on an
   undefined var), and mirror the correction in both `.es` header comments (C1).
3. `specs/vault-contract.md` §3.3 (path A / branch ordering) and §9 — state the P1 race, that the
   `PAYMENT_PENDING` reclaim exclusion is custodial, and that the seller side chooses both
   `timeoutHeight` and the record timestamp (P1, P2).
4. `specs/vault-contract.md` §3.3 — add "the oracle NFT id is immutable for a deployment's life"
   next to the existing one-parameter-set note (C5).
5. `specs/vault-contract.md` §8.4 — add the no-in-place-migration note for the phase-2 guard
   swap, with the "keep the phase-1 trees and NFT live until every phase-1 FUNDED box is terminal"
   runbook item (C4).
6. `specs/vault-contract.md` §3.1 — say whether ERG conservation on payout paths is intended
   (C3).
7. `specs/vault-contract.md` §4 — one paragraph on why the contract is split in two (§5 here), so
   C4/C5 read as consequences rather than oversights.
8. ~~`specs/deal-protocol.md` §3.2 — note that the claim must be broadcast promptly: the effective
   on-chain window is `[record ts, min(record ts + HANDOFF_RECORD_MAX_AGE, timeoutHeight)]`, and
   the record's timestamp is the seller's to choose (P2).~~ — **moot 2026-10-04**: there is no
   record-expiry window anymore (§8.8); the only deadline is the box's `timeoutHeight` itself.
9. `specs/seller-dashboard.md` — the P4 emergency-contest rule.

## 9. Cross-references

- Owned spec: `specs/vault-contract.md` (registers §3.1/§4.1, paths §3.3/§4.2, parameters §2,
  sigma-6 constraints §5, matrix §7, revisions §8.4, trust model §9)
- Deal states and wire formats: `specs/deal-protocol.md` §1, §3.1, §3.2, §3.4
- Oracle trust model and phase 2: `specs/oracle-integration.md` §2.2, §3.1, §3.2
- Off-chain enforcement: `apps/core/dealprotocol/.../DealStateMachine.kt`,
  `backend/.../vault/VaultManager.kt`
- Implementation: `contracts/src/main/ergoscript/`, `apps/core/ergo/.../{OperatorTxBuilder,
  ClaimTxBuilder,ErgoContracts,HandoffRecordVerifier}.kt`, `backend/.../api/Server.kt`
- Design rationale: `onramp-insurance.md` §2, `onramp-ux.md` §2/§6

## 10. Second pass (2026-09-30 → 10-01): the Kotlin layer, and one finding the first pass missed

*Same method against the same prover driver, plus the chain-reading and tx-building Kotlin. Verdict
unchanged on the scripts: no unauthorized extraction, no cross-deal replay, no forgery path, no
partial token drain; every collateral-moving path requires the matching key. The Schnorr check
survives scrutiny — 24 honest signatures driven through the real prover with 9 negative challenges,
all 24 verified, so sigma's `exp` handles the signed two's-complement `e` correctly.*

### 10.1 L1 — `VaultBoxTracker` could not see key-rotated payouts — **fixed 2026-10-01**

The 2026-09-24 payout-freedom change left the payee unpinned on every payout path, and
`VaultBoxTracker` still classified by destination tree (deriving the expected tree from the box's
R5/R6). A payout to a fresh key is valid on chain and was silently invisible to the operator: no
`RELEASED`/`CLAIMED`, the deal stuck in a locking state inside `openDeals()`, so `CollateralPool.locked`
kept counting collateral that was already gone and every `maxDealSize` cap derived from it drifted
downward permanently. `OperatorTxBuilder` actively exposes `sellerPayoutAddress` to produce exactly
such a payout, so this was reachable on the happy path, not only adversarially.

Classification now derives from what the contracts actually pin — full-collateral conservation —
and the *shape* of the spend (attestation data input, data-input count, heights) rather than its
destination. That is strictly more faithful than the tree comparison it replaces: the old read could
not tell a post-maturation contest from a claim payout at all, since both pay to the buyer's key.
Table and reasoning in `specs/android-app.md` §4.2; regression cases in `VaultBoxTrackerSpec` cover
rotated-key release/claim/contest, conservation, and pre-maturation path-D impossibility.

### 10.2 Second token kind escapes conservation — open, low

`payoutOk` pins only `OUTPUTS(0).tokens(0)` in both scripts. A vault box carrying two token kinds
lets the second kind be paid to any address — routed to the buyer in a reclaim and to the seller in
a claim payout in the probes. Not reachable through the shipped builders (both create single-kind
boxes), and the seller/buyer can only do it to themselves, but the contract's conservation claim is
narrower than "the full collateral" reads. Fix is `OUTPUTS(0).tokens.size == SELF.tokens.size`
plus a per-kind loop, at the cost of two more `tokens(i)` evaluations per path.

### 10.3 Record format unenforced; the R8 evidence id is computed over garbage — open, low

The 52-byte length, the `P2PH` magic, the version byte and the amount/currency fields are all
unvalidated: a 60-byte record, magic `ZZZZ`, and `fiatAmount = 2^64-1` with currency `ZZZ` each open
a claim. All are seller-signed, so no attacker gains, but R8's record id is `blake2b256(a‖z‖msg)`
over whatever bytes rode along — the dashboard's evidence view can display a blob
`HandoffRecord.decode` rejects. `msg.size == 52` plus a magic check closes it. Truncation *is*
already rejected (the `slice` throws).

### 10.4 `oracle.es` does not pin the reproduction's script — open, low, new

The contract checks the NFT id and the amount into `OUTPUTS(0)` but never
`OUTPUTS(0).propositionBytes`. The oracle can re-script its singleton freely, which means the NFT id
can exist in two boxes at once and the release path accepts either as a data input — so the
"one attestation in flight" serialization the whole release design rests on is a behavioral
convention, enforced by nothing on chain. `specs/oracle-integration.md` should say so.

### 10.5 Two doc errors about data inputs — open, prose

- `vault-contract.md` §8.3 item 2 and §7 test 13 both say an oracle box in a *claim transaction*
  breaks the spend. True for a full input only (`oracle.es` pins its reproduction at `OUTPUTS(0)`);
  attached as a **data input** the claim-open passes. §10.4's script pin is what would make the
  stronger statement true.
- Three files reason from "the proof reducer evaluates every val of the block it is in" (C1). The
  probes confirm sigma `val`s are lazy — an off-curve R6 does not block the contest.

### 10.6 Confirmed non-issues, worth recording

`timeoutHeight = Long.MAX_VALUE` closes the reclaim permanently while release still works (operator
footgun — `buildFund` should bound it). An off-curve R5 bricks every FUNDED path *including* reclaim,
and the worst of it is that `buyerPubKey` comes from the buyer app. `proofHeight` overflow is
rejected, not exploitable. Post-timeout spends need no attestation at all — test 51 already showed
this, and the sharper consequence is that a post-timeout release is indistinguishable from a
reclaim on chain, which is why the tracker's post-timeout discrimination leans on the data input
rather than the height (§10.1).

### 10.7 Still-open owner decisions from the first pass

Unchanged by this pass: P1 (the post-timeout race) and P2 (the seller picks the claim window's far
end) remain the only findings that alter the protocol's guarantees; C3/C6 and §10.2/§10.3 are the
cheap hardening set; C4/C5 need the phase-2 invariants written down. No `.es` file was modified in
either pass.
