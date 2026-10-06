# Terminal Consoles Implementation Spec — Buyer & Seller TUIs

*Implementation spec for the two full-screen terminal consoles of the vault-insured
cash→USDT on-ramp. Depends on: `specs/operator-backend.md` (the `/v1` dashboard API and
`/v1/events` WebSocket both consoles talk to over HTTP — neither console links the
backend in-process); `specs/deal-protocol.md` (canonical state names §1, handoff record
§3.2, `p2pgate://` QR payloads §3.3); `specs/android-app.md` (the buyer side's screens and
chain interaction — this spec covers the terminal rendering of the same protocol, and
inherits every protocol decision made there); `specs/seller-dashboard.md` (the web
dashboard, whose feature set the operator console mirrors); `specs/vault-contract.md`
(timings: `RECLAIM_TIMEOUT` 24h, `CLAIM_MATURATION` 12h).
Product context: `onramp-ux.md` §2 (buyer), §3 (seller meeting), §4 (operator).
Direction: cash→USDT only; the reverse direction is out of scope and not specified.*

**Implementation status (2026-10-03):** all three modules are landed and tested (95
tests), and **both consoles have been run against a live demo backend**. §8 records what
that session verified and, more usefully, the eight bugs it found that the headless suite
had passed.

## 1. Why a third and fourth UI

There are already two user interfaces, and this adds two more. That is deliberate, not
redundant: each one exists because the *other* ones cannot run in the place the work
happens.

| | Buyer | Seller / operator |
|---|---|---|
| Primary UI | native Android app (`apps/app`, `android-app.md`) | web dashboard (`backend/.../dashboard/`, `seller-dashboard.md`) |
| **Terminal console** | `:tui:buyer` | `:tui:seller` |
| Why it exists | a desktop machine, not a phone, is where key generation and tx broadcast belong; the Android app's claim path is unbuilt (`specs/README.md` open backlog) | SSH + `tmux` is how an operator actually watches a lane — the web dashboard needs a browser and a working session |
| Holds private keys? | **yes**, one file | **no** — never |

The consoles are **not** a third implementation of the protocol. Every wire format is
shared: `tui/common` mirrors `apps/app`'s hand-written `net/Dtos.kt` precedent, and both
consumes `:apps:core:dealprotocol` for the state machine, the handoff record and the QR
payload codec. The seller's signing side and the buyer's chain side reuse
`:apps:core:ergo` unchanged.

## 2. Module layout

```
tui/common/   BackendClient (interface) + KtorBackendClient, wire DTOs, TuiConfig, Format
tui/seller/   the operator console: SellerState, SellerController, SellerScreen, TerminalQr
tui/buyer/    the buyer console: BuyerFlow, KeyVault, HandoffStore, BuyerState/Controller/Screen
```

`:tui:common` holds everything both binaries need, so neither console can drift from the
other on wire shapes or configuration resolution. `:tui:seller` and `:tui:buyer` each
have exactly one `Main.kt` that starts the Mosaic terminal loop.

**Why a separate top-level folder.** `apps/` is the Android app's tree and is
otherwise reserved; a terminal console has no business in it, and putting `tui/` at the
root keeps the Android dependency closure (AGP, Compose-for-Android, Room, KSP) out of a
module that is plain JVM. Every `tui/` module is `kotlin("jvm")`.

## 3. The console framework: Mosaic

Both consoles are [Mosaic](https://github.com/JakeWharton/mosaic) 0.18.0
(`com.jakewharton.mosaic:mosaic-runtime`), Compose-for-the-terminal. The owner chose a
library over hand-rolled raw-mode rendering; Mosaic gives terminal lifecycle, resize,
keyboard input (`Modifier.onKeyEvent`) and headless rendering tests for free.

Consequences worth knowing before editing:

- **Mosaic needs a real TTY.** `./gradlew :tui:seller:run` and IDE run-configs do not
  work — run the installed binary from a terminal. This is also why §8 is the only
  untested area.
- **Mosaic 0.18.0 requires Kotlin ≥ 2.2.10.** The repository was bumped 2.0.21 → 2.2.21
  to unblock it; the bump's fallout (the `compilerOptions` DSL replacing
  `kotlinOptions.jvmTarget`, Room 2.6.1 → 2.7.2 for KSP2, and a BouncyCastle/JSpecify
  `META-INF/versions/9/OSGI-INF/MANIFEST.MF` packaging collision) is repo-wide, not
  `tui/`-specific.
- **Screens are pure functions of state.** Every screen takes a state object and draws
  it; no screen touches the network. `SellerScreenSpec` renders the real composable
  through `mosaic-testing` and asserts the snapshot, so layout regressions are caught
  without a terminal.

## 4. The seller console (`:tui:seller`) — landed

A nine-column kanban of live deals, one column per canonical `DealState`
(`deal-protocol.md` §1), with a cursor. `SellerController.actionsFor(DealState)` is the
single mapping from state to available actions — pure, so the key hints the screen
renders and the legality check the controller enforces cannot drift apart.

Columns run left to right in the order an operator works them
(`SellerState.Lanes.ORDER`), with the operator-facing labels `Lanes.TITLES` — the full
canonical names do not fit a narrow header, and these labels are the only place the two
may differ:

```
QUOTED     FUNDED     PAYMENT_PENDING  PAYMENT_CONFIRMED  CLAIM_OPENED  CLAIMABLE  RELEASED  RECLAIMED  CLAIMED
OFFERED    FUNDED     CASH IN          PAID?               CLAIM         CLAIMABLE  RELEASED  RECLAIMED  CLAIMED
```

| Column | Keys |
|---|---|
| `QUOTED` (`OFFERED`) | `a` accept (funds the vault) · `d` decline |
| `FUNDED` | `s` sign the handoff record (the meeting) · `r` reclaim after the timeout |
| `PAYMENT_PENDING`, `PAYMENT_CONFIRMED` | — |
| `CLAIM_OPENED`, `CLAIMABLE` | `c` contest with the attestation · `i` mark under investigation · `v` accept the claim (record the loss) |
| `RELEASED`, `RECLAIMED`, `CLAIMED` | — (read only) |
| anywhere | `q` the quote-ad panel (publish / withdraw — §4.3) |

`PAYMENT_PENDING` and `PAYMENT_CONFIRMED` deliberately offer **no** actions — waiting is
not an operator action, and a key that does nothing teaches the operator the board is
broken. Every action is additionally guarded: an action outside `actionsFor` is refused
with a message rather than dispatched, so a stale keypress cannot drive a deal sideways.

### 4.1 The console holds no keys

**Invariant: `:tui:seller` never touches a private key.** Every seller-signed
transaction is built inside the backend's `VaultSigner` seam
(`backend/src/main/kotlin/p2pgate/backend/vault/VaultManager.kt`); the
console only asks for actions. A stolen operator terminal session is therefore worth
exactly what the operator bearer token is worth — no more — and there is no key material
on the machine to exfiltrate. The `s` key does not sign anything: it POSTs
`/v1/dashboard/deals/{id}/handoff/sign` and the backend Schnorr-signs with the vault's
R5 key.

### 4.2 The meeting QR is rendered in the terminal

`specs/seller-dashboard.md` §4 serves the signed handoff record as a PNG at
`/handoff/qr.png`. At a meeting the seller is holding a phone in one hand and needs the
code on the *laptop screen* — so `TerminalQr` renders the same payload as terminal text:
ZXing's `QRCodeWriter` (the identical encoder the backend uses, `util/QrCodes.kt`), two
matrix rows per character line via half-block glyphs (`█ ▀ ▄`) so the code stays square
instead of stretching two-to-one. Any key dismisses the panel.

`TerminalQrSpec` asserts the rendering is scannable without a phone: every module maps
to the glyph its encoder bit implies (reading the half of the glyph the module actually
occupies — the top half when its padded row is even, the bottom half when odd), and the
text decodes back to the original payload through ZXing's reader.

The buyer's app must hold a **verified** seller-signed record before they leave with the
cash (`onramp-ux.md` §2.3), which is why the panel stays up until dismissed.

### 4.3 Quote ads from the console (2026-10-05)

The operator's ads are managed from the console too — `q` toggles the quote panel (a
board section, like the meeting QR): the live quotes with cursor (`j`/`k`), `x` withdraws
the selected one, and `n` opens the new-ad form. The form fields are currency, rate
(fiat per 1 USDT), min/max USDT, ETA minutes, spread bps, and lat/lon (a pair or
neither). Mosaic has no text-field widget, so the controller captures typed characters
per focused field (`tab`/`↑`/`↓` to move, `enter` to publish, `esc` to cancel); the typed
whole-unit decimals become wire units on submit — rate → micros of fiat per USDT,
amounts → 6-decimal base units, exactly (BigDecimal, never Double). Local validation
saves the round trip (3-letter currency, positive numbers, min ≤ max, lat/lon as a
pair); the backend re-checks everything, capacity included, and its refusal reason is
shown verbatim in the status line — the capacity rule ("max deal size … exceeds free
collateral") is a feature of the demo, not an error. `spreadBps` stays metadata: the cash
leg is re-derived from the rate, per the owner decision recorded in `AGENTS.md`.

**Modifier combos are not actions.** Both consoles ignore `ctrl`/`alt` key events:
Mosaic delivers ctrl+c as key `"c"` with ctrl set, and without the guard an operator's
quit attempt would CONTEST the selected claim — or ctrl+a accept an offer and fund a
vault (found via the PTY harness, §8.3 item 9).

## 5. The buyer console (`:tui:buyer`) — landed

Quotes, the deal, the meeting gate, and the two claim paths. The state/screen/controller
split matches the seller console's, and the decision logic lives in `BuyerFlow` as pure
functions so the rules below are asserted without a chain.

### 5.1 Key custody: one passphrase-encrypted file

The buyer's key lives in **one file, encrypted under a passphrase**, written only on
explicit user action and read only when a transaction actually needs signing. Design:

- The file holds the buyer's secp256k1 secret (the **R6** deal key, per
  `deal-protocol.md` §4 key management — R5 is the seller's key) and the deal
  terms the key is bound to.
- Encryption is AES-GCM with a random salt and a random IV per write; the key is derived
  with PBKDF2-HMAC-SHA512 (BouncyCastle is already a dependency). The plaintext key is
  never written, never logged, and never held in a field of any state object that gets
  rendered — the screen shows whether a key is *present*, never its bytes.
- The passphrase is read with echo disabled and is not retained after derivation.
- The file location and passphrase source follow the backend's own configuration
  precedence (flag → env → default), which `TuiConfig` already implements.

**Path D payout is the only path that needs this key.** Path B (claim-open) is
`sigmaProp(...)` with no `proveDlog`, so the entire dispute flow — open the claim, watch
it mature, take the payout — needs **no private key to *open***, and a buyer who never
takes a payout never needs a key file at all. That split is the reason the key handling
can stay this simple: the key gates exactly one operation, and the console reads it only
after the path is chosen (`BuyerController.signerFor`).

**The file format** (`KeyVault`): `"PGKEY" | version | iterations | salt(32) | iv(12) |
AES-256-GCM ciphertext`. The header through the IV is authenticated as additional data, so
the iteration count cannot be lowered or the salt/IV swapped without the tag check
failing; the iteration count (PBKDF2-HMAC-SHA512, 210k) is stored rather than hardcoded so
it can be raised without invalidating anyone's file. Written via a temp file with
`rw-------` applied *before the first byte is written* — creating a file leaves it at the
umask default, and that window is the one that matters. A key directory is created
owner-only; an existing one is left alone.

Every failure mode — absent file, wrong magic, future version, absurd iteration count,
wrong passphrase, flipped ciphertext bit — is a `KeyVault.UnlockFailed` whose message
says which, so the console can tell a buyer "that passphrase is wrong" without leaking
anything about the file. `KeyVaultSpec` asserts each one, that the plaintext secret
appears nowhere in the file, and that one file's ciphertext cannot be pasted into
another's envelope (the salt/IV are per file).

### 5.2 The console builds and broadcasts claim transactions

This is the gap that the Android app leaves open (`specs/README.md` open backlog: "the
core promise is a text list" — `POST /v1/deals/{id}/claim` returns instruction strings).
The buyer console is the surface where that gap is closed:

- `:apps:core:ergo` supplies `ChainSource` (explorer, or a node's extra-indexer API when
  `P2P_NODE_URL` is set), `ClaimTxBuilder` and `SchnorrVerifier` — all already
  implemented and tested.
- `BuyerController.buildAndBroadcastClaim(payout)` picks the path, builds the
  transaction offline (which is what proves the build satisfies the compiled vault
  scripts), signs it, and submits it through a `TxSubmitter` seam.
- The chain reader follows `TuiConfig.network` (mainnet by default; `P2P_NETWORK=testnet`
  to switch). The compiled trees are the single canonical parameter set — there is no
  `compileFast` anymore (its freshness override died with the in-script freshness
  window on 2026-10-04; the maturation had already become a hardcoded literal). The
  deployed oracle NFT id comes from
  `P2P_ORACLE_NFT_ID`; since 2026-10-03 it is no longer compiled into the trees (both
  boxes pin it per-box: FUNDED R7, PROVEN R9), so an unset variable no longer makes the
  console refuse a real vault box — it only degrades spend classification (a release can
  be misread as a reclaim), and that is called out on stderr at startup.

### 5.3 The seller key comes from the chain, not the backend

`specs/README.md`'s open backlog flags that the Android app verifies the handoff
signature against `DealDto.sellerPubKey` — a key the *backend* supplied — so a
compromised backend can substitute the key **and** a matching signature and the app will
print "safe to leave". The console closes that, because it reads the chain:

- the vault box is fetched from `ChainSource` by id, not taken from the backend's copy;
- the record's `dealId` must equal the box's **R4**, so a record captured from another
  deal cannot be replayed here;
- the signature must verify under the box's **R5**, the key the contract itself pins.

The **fiat amount is deliberately not checked**: it is not on-chain, so verifying it
would only compare two backend-supplied values and prove nothing. The deal terms are
hashed into the deal id, which R4 pins, so the amount is bound indirectly rather than
checked directly — stated here rather than glossed over.

The captured record is stored locally (`HandoffStore`, owner-only, no private key
material in it — it carries the *seller's* signature) and is the buyer's evidence. It is
never re-fetched from the backend: the record is only as trustworthy as the channel it
arrived over, and that channel is a person standing in front of the buyer.

The deal token is held **in memory only**. A restart loses it and the deal must be
re-attached (`attachTo`); persisting a bearer token to disk is left undone deliberately,
since `specs/README.md` already flags the Android app's plaintext token storage.

## 6. Talking to the backend

Both consoles are **separate processes over HTTP/WS** — they do not link `:backend` and
share no memory with it. `BackendClient` is an interface (so the seller console's tests
run against a fake, not a live server); `KtorBackendClient` is the HTTP implementation,
carrying the operator token as a bearer header.

The operator key is read from the environment or a flag and never written to disk
(`TuiConfig`). The **buyer** console holds no operator key at all — it uses the
per-deal token the backend mints at creation, for the buyer endpoints only. Note the dashboard's known auth weaknesses (key in URL query strings, no
security headers, and `P2P_OPERATOR_KEY` unset = demo-open) are recorded in the
`seller-dashboard.md` status note and apply unchanged to the console.

**DTO drift is the standing hazard.** `tui/common/wire/Dtos.kt` is *hand-mirrored* from
`backend/src/main/kotlin/p2pgate/backend/api/Dtos.kt`, following the precedent in
`apps/app/.../net/Dtos.kt`. Nothing generates it. `:tui:seller:check` therefore runs
`checkDashboardCoverage`, which greps the web dashboard's `api()` calls and fails the
build if any dashboard path has no `BackendClient` counterpart. That catches coverage,
**not** field drift — a renamed field still compiles and still fails at runtime.

## 7. Testing

| Module | Tests | What is covered |
|---|---|---|
| `:tui:common` | 30 | `KtorBackendClient` against Ktor's `MockEngine` (16: URL shapes, auth headers, error wording, DTO decoding), `Format`, `TuiConfig.resolve` precedence |
| `:tui:seller` | 34 | `SellerState`/`actionsFor` per state, the screen rendered headlessly through `mosaic-testing`, the terminal QR, the status line (§8.3), the quote-ad panel (`SellerQuotePanelSpec` 10: cursor, withdraw, form typing, validation, wire-unit conversion, rejection surfacing) |
| `:tui:buyer` | 41 | `KeyVault` (17: round-trip, wrong passphrase, tampering, cross-file splice, permissions, every failure mode), `BuyerFlow` (15: which key each path needs, when a claim may be opened, the maturation countdown), the screen rendered headlessly |

Gate: `./gradlew :tui:common:test :tui:seller:test :tui:buyer:test` (also in the full
gate in `AGENTS.md`). The buyer screen tests drive the **real** controller against a fake
client and a fake chain, so the state assembly is covered too; two package-internal
`debug*` seams place it in a state that would otherwise need a signature or a file.

The client tests use `runBlocking`, deliberately, not `runTest`: `MockEngine` dispatches
on a real dispatcher and outlives the virtual clock, so two runs disagreed about which
tests failed. Stable under `runBlocking` over repeated runs.

## 8. Live verification, and what it found

### 8.1 How they were run

Mosaic needs a TTY, which a pipe does not provide — but a **synthesized** one does.
`script -qec CMD /dev/null` and Python's `pty.spawn` both make `System.console()`
non-null, which is all Mosaic checks. That makes a scripted smoke test possible, and the
harness has since been promoted into the repo: `scripts/pty_drive.py` (PTY driver: key
schedule, wait-for-output, ANSI-stripped final screen) with `scripts/pty-smoke.md` as its
reference, and the canonical runbook is `specs/tui-demo-run.md`:

```bash
export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1   # the launchers need it at RUN time too
P2P_OPERATOR_KEY=demo P2P_DEMO_QUOTES=true ./gradlew :backend:run &
./gradlew :tui:seller:installDist :tui:buyer:installDist
script -qec "P2P_OPERATOR_KEY=demo tui/seller/build/install/seller/bin/seller" /dev/null
```

So "no TTY here" was a harness limitation, not a property of the consoles. **Both
consoles run against a live backend.**

### 8.2 What was verified

- the seller console paints the live nine-column lane, and the per-column key hints
  change correctly as the cursor moves (`a/d` on QUOTED → `s/r` on FUNDED);
- the buyer console creates a key file through the passphrase prompt, lists the live
  quote feed, and **creates a real deal** — `deal 1124ae69… created`, cash leg derived
  from the rate (`you pay 92 INR`), event ticker updating;
- auth rejection works: a bad `P2P_OPERATOR_KEY` returns `{"error":"invalid operator key"}`;
- the key file and its sidecar are written `-rw-------`;
- the keyless fallback (no key file, no prompt answered) still starts and can open a claim.

### 8.3 Bugs that only running could find

Every one of these passed the headless suite. That is the argument for the PTY harness
existing at all.

1. **The seller's status line was dead on the happy path.** `refresh()` wrote to
   `status` only on failure, so the console's one feedback channel read "connecting…"
   forever. A successful refresh now reports the live count, and the board's *age*
   (`synced 4s ago`) is shown in the status strip — amber past 30s, because staleness
   usually means the chain watcher behind the backend has fallen behind.
   `SellerStatusSpec`, 5 tests.
2. **A refresh clobbered action messages.** "signed the handoff record — scan below" was
   wiped by the next 5s tick before an operator could read it. Status written by an
   action is now pinned for 8s. Found while fixing (1); `say()` must stamp the
   **injected** clock, not `System.currentTimeMillis()`, or the pin never expires —
   which is what the new test caught on its first run. The buyer console had the same bug
   independently and got the same fix.
3. **`P2P_DEMO_QUOTES=true` seeded nothing.** `maxAmount = mixReadyCollateral × share`,
   and demo mode has an empty vault, so every `maxAmount` was 0 and every seed was
   refused. The existing test *asserted* this ("an empty pool rejects the seeds"), which
   is why it survived. Demo seeds now fall back to a fixed 5,000-USDT ceiling and bypass
   the capacity check — **only** in `seedDemoQuotes`, with a separate test that an
   ordinary publish against an unfunded pool is still refused. "Max deal size = vault
   capacity is a hard constraint" is what stops the feed promising collateral the
   operator does not have, so that second test is the one that matters.
4. **The buyer console could not unlock its own key.** Mosaic owns stdin, so a passphrase
   prompt issued from inside the event loop rendered `Passphrase:` and then never received
   the keystrokes. Unlocking now happens once in `Main`, **before** the terminal loop, and
   the secret is held for the session and overwritten on quit. The file at rest stays
   encrypted either way; this is the ordinary unlocked-wallet exposure model.
   `P2P_KEY_PASSPHRASE` now takes precedence over the interactive prompt — someone who
   exports it has opted into automation, and prompting anyway hangs a scripted run.
5. **The console advertised an affordance it did not have.** The quote list said
   `enter · take`, and neither digit-selection nor Enter was bound to anything.
6. **The Ergo key address was offered as the USDT payout address.** `receiveAddress` is a
   **TRON** address; the console passed its Ergo P2PK address and the backend refused the
   deal with "receiveAddress is not a valid TRON address". Two keys on two chains: the
   Ergo key authorises the claim *spend*, the TRON address receives the *USDT*. Now a
   separate `P2P_PAYOUT_ADDRESS`, validated with `TronAddress` and shown next to the
   quote list so the two cannot be confused again.
7. **A restarted console did not know its own address.** The address lives inside the
   encrypted payload, so a console restarted after a crash had a key it could not name
   and could not deal. `KeyVault` now writes a `.pub` sidecar (address + fingerprint,
   both public by construction) so the console can publish `receiveAddress` without a
   passphrase prompt.
8. **Misleading error text.** "write a key file first — press k" was shown when the key
   file *existed* and only the passphrase had not been supplied. Errors now name the
   actual missing thing.
9. **Ctrl combos dispatched actions.** Found while rehearsing the quote panel (§4.3):
   the PTY harness's closing ctrl+c reached the action dispatch as key `"c"` — a phantom
   CONTEST ("nothing selected" in the status). On a selected CLAIM card that would have
   contested it, and ctrl+a would have *accepted an offer and funded a vault*. Mosaic
   delivers modifiers on `KeyEvent` (`ctrl`/`alt`/`shift`); both consoles now ignore
   modified key events entirely.

### 8.4 Still not verified

1. **Field drift** — a backend field the hand-mirrored DTO names differently. The demo
   backend's shapes all decoded correctly, but that is one server's current output.
2. **The claim paths live** — **no claim transaction has been built
   or broadcast from either console**: demo mode's NoOp tx submitter never puts a vault
   on-chain, so the claim paths are still proven only by the contract suite
   and `:core:ergo`'s own builder tests. (The `P2P_ORACLE_NFT_ID` mismatch sub-point is
   stale since 2026-10-03: the trees no longer embed the NFT — a mismatch now only
   degrades spend classification, it cannot break a claim build.)
3. **The meeting QR on a real terminal** — the rendering is verified by decoding it back
   through ZXing, not by a phone scanning a laptop screen in a bright room.
4. **WebSocket reconnect** — the seller board's live updates come from `/v1/events`;
   reconnect behaviour, and what happens to the cursor when a deal moves column
   underneath it, is still unwatched.

## 9. Cross-references

- Design: `onramp-ux.md` §2/§3/§4, `onramp-insurance.md` (trust model)
- Backend API: `specs/operator-backend.md` §9 (dashboard API, env vars, run recipe)
- Protocol: `specs/deal-protocol.md` §1 (states), §3.2 (handoff record), §3.3 (QR), §4 (keys)
- Sibling UIs: `specs/android-app.md`, `specs/seller-dashboard.md`
- Contracts: `specs/vault-contract.md` §3 (paths), §2 (timings)