# Running the TUI demo — runbook

*Runbook for trying out the two terminal consoles (`tui/`) against a local demo backend
on one machine. Depends on: `specs/tui-apps.md` (what the consoles are and do — this file
only covers getting them running), `specs/operator-backend.md` (the backend's env vars and
demo mode), `scripts/pty-smoke.md` (the scripted PTY harness reference). Product context:
`onramp-ux.md` §2–§4.*

## 0. What the demo is — and is not

```
scripts/run-seller.sh                              → the operator backend (demo mode) on :8080
scripts/tui-seller.sh                              → the operator console (nine-column kanban)
scripts/tui-buyer.sh                               → the buyer console (quotes → deal → meeting → claim)
```

The two `tui-*.sh` scripts build the console launcher if it is missing
(`:tui:*:installDist` → `tui/{seller,buyer}/build/install/{seller,buyer}/bin/…`),
export a JDK 17 `JAVA_HOME` (needed at *run* time — the launchers resolve `java`
lazily), and wrap the run in a synthesized PTY when stdin is not a terminal, so the
same command works interactively and from a pipe/CI.

Demo mode (`P2P_DEMO_QUOTES=true`) means: in-memory deal store, seeded located example
quotes, a dev oracle, and a **NoOp tx submitter** — nothing ever reaches a blockchain.
The consoles exercise the full UI and the real wire formats, but no vault exists on-chain,
so the claim paths are exercised only up to (offline, prover-verified) transaction
construction. That limitation is recorded in `specs/tui-apps.md` §8.4.

## 1. Prerequisites

- JDK 17 — this machine keeps it at `~/.local/opt/jdk-17.0.20.1+1`; the run scripts
  default `JAVA_HOME` to it. Nothing Android-related is needed (that is
  `scripts/run-buyer.sh` — the *app*, not the console). **`JAVA_HOME` must also be
  exported when running the console binaries**, not only when building: the installed
  launchers are shell scripts that resolve `java` via `JAVA_HOME`/`PATH`, and a system
  Java 8 fails with `UnsupportedClassVersionError` (the classes are Java 17).
- Port 8080 free.
- A real terminal, **or** a PTY shim — Mosaic refuses to start without a TTY
  (`System.console()` must be non-null). `./gradlew :tui:seller:run` does **not** work;
  the installed binaries do. From a pipe or CI, synthesize the TTY:
  `script -qec "CMD" /dev/null` (util-linux) or `python3 -c 'import pty; pty.spawn(["CMD"])'`.

## 2. Start the backend

```bash
scripts/run-seller.sh
```

Despite the name it starts the **operator backend** (the seller side of the demo).
Defaults it sets, all overridable:

| Env var | Demo default | Meaning |
|---|---|---|
| `P2P_OPERATOR_KEY` | `op-demo` | dashboard/console bearer token (unset = demo-open; set here so auth is exercised) |
| `P2P_DEMO_QUOTES` | `true` | seed three located example quotes into `/v1/quotes` |
| `P2P_MIX_READY` | `100000000000` | pretend mix-ready collateral, so quote capacity is non-zero |

The web dashboard rides along at `http://localhost:8080/dashboard/` (same backend, same
key) — useful next to the console to see what each renders differently.

## 3. Build the console launchers

```bash
export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1
./gradlew :tui:seller:installDist :tui:buyer:installDist
```

(`scripts/tui-seller.sh` / `scripts/tui-buyer.sh` run this automatically when the
launcher is missing — §4/§5.)

This produces `tui/seller/build/install/seller/bin/seller` and
`tui/buyer/build/install/buyer/bin/buyer` — the only supported way to run the consoles
(there is no `seller/bin/seller` shortcut; the Gradle `run` task does not work under
Mosaic, see §1).

## 4. The seller console (operator)

```bash
scripts/tui-seller.sh
```

That is the whole command — the script builds the launcher if missing, exports JDK 17,
and PTY-wraps when needed. The manual equivalent (from a real terminal, drop the
`script` wrapper):

```bash
export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1   # the launchers need it at RUN time too
script -qec "P2P_OPERATOR_KEY=op-demo tui/seller/build/install/seller/bin/seller" /dev/null
```

(From a real terminal, just run the binary.) The console holds **no keys** — every
seller-signed action is performed by the backend (`specs/tui-apps.md` §4.1). Keys:

| Where | Key | Action |
|---|---|---|
| `OFFERED` column | `a` / `d` | accept (funds the vault) / decline an offer |
| `FUNDED` column | `s` | sign the handoff record — the meeting QR renders in the terminal |
| | `r` | reclaim after the timeout |
| `CLAIM*` columns | `c` / `i` / `v` | contest with the attestation / investigate / accept the loss |
| anywhere | `q` | the quote-ad panel: live quotes, `n` new ad (typed form, `enter` publishes), `x` withdraw selected |
| anywhere | cursor keys, `q` | move, quit |

## 5. The buyer console

```bash
P2P_KEY_PASSPHRASE=hunter2 P2P_PAYOUT_ADDRESS=T... scripts/tui-buyer.sh
```

(`T...` = any real base58check TRON address — do NOT invent one, the checksum must
match. `P2P_KEY_PASSPHRASE` set = no interactive prompt; omit it to be prompted.)
The manual equivalent:

```bash
TRON=T...   # a real TRON address, as above
script -qec "env \
  JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1 \
  P2P_BASE_URL=http://127.0.0.1:8080 \
  P2P_KEY_FILE=/tmp/buyer.p2pkey \
  P2P_KEY_PASSPHRASE=hunter2 \
  P2P_PAYOUT_ADDRESS=$TRON \
  tui/buyer/build/install/buyer/bin/buyer" /dev/null
```

Env vars the buyer console reads (`tui/buyer/.../Main.kt`):

| Env var | Required? | Meaning |
|---|---|---|
| `P2P_BASE_URL` | no (default `http://127.0.0.1:8080`) | the backend |
| `P2P_KEY_FILE` / `P2P_HANDOFF_FILE` | no | where the encrypted key file / captured handoff record live |
| `P2P_KEY_PASSPHRASE` | no | unlocks the key file without prompting — the automation path; omit it for the interactive prompt |
| `P2P_PAYOUT_ADDRESS` | **yes, to deal** | the buyer's **TRON** USDT address — *not* the Ergo key (the Ergo key authorizes the claim spend; the TRON address receives the USDT). Validated with `TronAddress`; a bad checksum fails the deal |
| `P2P_ORACLE_NFT_ID` | no | the deployed oracle NFT (64 hex). Unset only degrades spend classification (a release can be misread as a reclaim) — the trees no longer embed the NFT, so claims build either way (2026-10-03) |
| `P2P_NETWORK`, `P2P_EXPLORER_URL`, `P2P_NODE_URL` | no | chain-source selection (`TuiConfig`; mainnet default) |

First run: the console offers to create the key file (passphrase prompt, or the env var
above), writes it `rw-------` plus a public `.pub` sidecar, lists the seeded quotes, and
`enter` takes one. The deal then appears in the seller console's `OFFERED` column —
accept it there, and walk the deal through the meeting (`s` on FUNDED → scan the terminal
QR… or in demo, just watch the states move).

## 6. Scripted smoke (no human)

`scripts/pty_drive.py` drives a console through a PTY: it waits for expected output before
sending each key and prints the final screen with ANSI escapes stripped. Full reference
and the known-good key schedules: `scripts/pty-smoke.md`. Example (seller console, wait
for the lane then walk the cursor):

```bash
scripts/pty_drive.py --cols 170 --rows 44 --settle 3 \
  --keys '[("wait","OFFERED",25),("send","l"),("send","j")]' -- \
  tui/seller/build/install/seller/bin/seller
```

This harness exists because the headless suite passed while eight real bugs shipped
(`specs/tui-apps.md` §8.3) — run it after touching screen code.

## 7. Troubleshooting

| Symptom | Cause |
|---|---|
| `seller/bin/seller: no such file` | shorthand from the §0 diagram — the real paths are `tui/{seller,buyer}/build/install/{seller,buyer}/bin/{seller,buyer}`, and they exist only after the `installDist` step (§3) |
| `scripts/pty_drive.py: Permission denied` | fixed in-repo (`chmod +x`); the script is executable now |
| `UnsupportedClassVersionError` from a launcher | `JAVA_HOME` not exported at run time — the launcher picked up a system Java 8; export the JDK 17 path (§1) |
| console exits immediately / refuses to start | no TTY — run via `script -qec … /dev/null` or `pty_drive.py` |
| `{"error":"invalid operator key"}` | `P2P_OPERATOR_KEY` mismatch with the backend's |
| "receiveAddress is not a valid TRON address" | `P2P_PAYOUT_ADDRESS` is missing, malformed, or an *Ergo* address (wrong chain) |
| buyer console: "no backend answers" / empty quotes | backend not up, or `P2P_BASE_URL` wrong |
| board stuck at "connecting…" | backend down or `/v1/events` unreachable — the status strip shows the board's age |

## 8. Cross-references

- The consoles themselves: `specs/tui-apps.md` (§8: what a live run verifies and what it
  still cannot)
- The backend's demo mode and full env-var list: `specs/operator-backend.md` ("Demo run
  recipe")
- The PTY harness reference: `scripts/pty-smoke.md`, `scripts/pty_drive.py`
- The Android-app demo (emulator): `scripts/run-buyer.sh`
