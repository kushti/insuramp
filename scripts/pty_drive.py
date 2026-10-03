#!/usr/bin/env python3
"""
Drive a full-screen terminal program under a synthesized PTY and capture frames.

Mosaic refuses to start without a TTY (its FAQ says so; `System.console()` is
null in a pipe). `script(1)` and `pty.spawn` both provide one, so the consoles
*can* be smoke-tested here -- which is what this harness is for.

It sends keys on a schedule, waits for the program to settle, and prints the
final screen after stripping ANSI escapes. `--keys` takes a Python expression
evaluated per step, so each step can wait for specific output before sending the
next key rather than firing blind.

Usage:
  pty_drive.py --cols 160 --rows 45 --settle 3 --out frames.txt \
      --keys '[("wait", "OFFERED"), ("send", "a")]' -- ./tui/seller/build/install/seller/bin/seller
"""
import argparse
import os
import pty
import re
import select
import signal
import struct
import sys
import termios
import time
import fcntl

ANSI = re.compile(rb"\x1b\[[0-9;?]*[A-Za-z]|\x1b[()][A-Za-z0-9]|\x1b[=>]|\r")

KEYS = {
    "enter": b"\r", "esc": b"\x1b", "tab": b"\t", "space": b" ",
    "up": b"\x1b[A", "down": b"\x1b[B", "right": b"\x1b[C", "left": b"\x1b[D",
    "ctrl-c": b"\x03", "backspace": b"\x7f",
}


def strip(buf: bytes) -> str:
    return ANSI.sub(b"", buf).decode("utf-8", "replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cols", type=int, default=160)
    ap.add_argument("--rows", type=int, default=45)
    ap.add_argument("--settle", type=float, default=3.0, help="idle seconds before sending")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--keys", default="[]")
    ap.add_argument("--out", default="-")
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    args = ap.parse_args()

    cmd = args.cmd[1:] if args.cmd and args.cmd[0] == "--" else args.cmd
    if not cmd:
        print("no command given", file=sys.stderr)
        return 2

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = os.environ.get("TERM", "xterm-256color")
        os.environ["COLUMNS"] = str(args.cols)
        os.environ["LINES"] = str(args.rows)
        os.execvp(cmd[0], cmd)
        os._exit(127)

    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", args.rows, args.cols, 0, 0))

    transcript = bytearray()
    deadline = time.time() + args.timeout

    def pump(seconds: float) -> None:
        """Read whatever arrives for `seconds`, appending to the transcript."""
        end = time.time() + seconds
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], min(0.2, max(0.0, end - time.time())))
            if not r:
                continue
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                return
            if not chunk:
                return
            transcript.extend(chunk)

    try:
        steps = eval(args.keys, {"keys": KEYS, "re": re})  # noqa: S307 - operator-supplied
        pump(1.5)  # let the program paint its first frame
        for step in steps:
            kind = step[0]
            if kind == "send":
                key = step[1]
                os.write(fd, KEYS.get(key, key.encode()))
            elif kind == "wait":
                # Block until `step[1]` appears in the transcript (regex), or
                # `step[2]` seconds elapse. Returns False on timeout.
                pattern = step[1]
                limit = step[2] if len(step) > 2 else args.settle
                end = time.time() + limit
                found = False
                while time.time() < end:
                    if re.search(pattern, strip(bytes(transcript))):
                        found = True
                        break
                    pump(0.25)
                if not found:
                    print(f"[harness] timed out waiting for {pattern!r}", file=sys.stderr)
            elif kind == "sleep":
                time.sleep(step[1])
            pump(args.settle)
    finally:
        try:
            os.write(fd, KEYS["ctrl-c"])
            pump(0.5)
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass
        try:
            os.waitpid(pid, 0)
        except ChildProcessError:
            pass
        os.close(fd)

    text = strip(bytes(transcript))
    # The last repaint is the frame we want; Mosaic repaints in place, so the
    # tail of the transcript is the live screen plus a little scrollback.
    if args.out == "-":
        print(text)
    else:
        with open(args.out, "w") as fh:
            fh.write(text)
        print(f"[harness] wrote {len(text)} chars to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())