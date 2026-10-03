# Running a console against a live backend

Mosaic needs a real TTY: `System.console()` must be non-null, or it refuses to
start. That is why `./gradlew :tui:seller:run` and IDE run-configs do not work —
and it is *not* a reason a console cannot be smoke-tested from a pipe. A
synthesized PTY satisfies the same check:

```bash
script -qec "CMD" /dev/null          # util-linux
python3 -c 'import pty; pty.spawn(["CMD"])'   # stdlib
```

`scripts/pty_drive.py` wraps that into something scriptable: it allocates a PTY,
sends a schedule of keys, waits for specific output before each key, and prints the
final screen with ANSI escapes stripped.

## The whole smoke run

```bash
# 1. a demo backend, with seeded quotes
P2P_OPERATOR_KEY=demo P2P_DEMO_QUOTES=true ./gradlew :backend:run &

# 2. build the launchers
./gradlew :tui:seller:installDist :tui:buyer:installDist

# 3. the seller console: wait for the lane, walk the cursor
scripts/pty_drive.py --cols 170 --rows 44 --settle 3 \
  --keys '[("wait","OFFERED",25),("send","l"),("send","j")]' -- \
  tui/seller/build/install/seller/bin/seller

# 4. the buyer console: create a key, take a quote, create a deal
#    P2P_PAYOUT_ADDRESS must be a valid TRON address -- it is the USDT leg,
#    not the Ergo key.
TRON=T...
P2P_BASE_URL=http://127.0.0.1:8080 \
P2P_KEY_FILE=/tmp/buyer.p2pkey P2P_PAYOUT_ADDRESS=$TRON \
P2P_KEY_PASSPHRASE=hunter2 \
scripts/pty_drive.py --cols 130 --rows 44 --settle 4 \
  --keys '[("wait","Create one now",40),("send","y"),("send","enter"),
           ("wait","enter . take",50),("send","3"),
           ("wait","> 3.",20),("send","enter"),
           ("wait","seller has your offer",60)]' -- \
  tui/buyer/build/install/buyer/bin/buyer
```

## Why this exists

The headless suite (`mosaic-testing`) renders the screens and covers the pure logic,
and it passed while eight real bugs shipped -- including a status line that was dead
on the happy path and a key-address/chain mix-up that made every deal creation fail.
See `specs/tui-apps.md` §8.3.

## A valid TRON address for tests

The backend validates `receiveAddress` as a 34-char base58check `T...` address. Any
real one works; do not invent one by hand, the checksum will not match.
