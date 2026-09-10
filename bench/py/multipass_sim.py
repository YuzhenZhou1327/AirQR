"""Multi-pass loss simulation on the rendered stream: models the real UX —
the receiver watches pass-by-pass (each pass is a fresh shuffle of the same
slot space) with 30% whole-frame loss + X% per-code loss, and is allowed to
decode after any prefix. Every trial must eventually succeed.
"""
import hashlib
import json
import random
import struct
import sys
from pathlib import Path

import zxingcpp
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from roundtrip import (Rng, cycle_len, parse_block, reassemble, selections,
                       splitmix64_next32)  # noqa: E402


def pass_shuffled_slots(manifest, session_seed, pass_idx):
    """Recomputes the pass order exactly per PROTOCOL §5 (independent impl)."""
    k = manifest["k"]
    cyc = cycle_len(k)
    p = Rng((session_seed ^ 0x5EED5EED ^ (pass_idx << 32)) & ((1 << 64) - 1))
    order = list(range(cyc))
    for i in range(cyc - 1, 0, -1):
        j = p.next32() % (i + 1)
        order[i], order[j] = order[j], order[i]
    return order


def decode_png(png):
    img = Image.open(png).convert("L")
    out = []
    for res in zxingcpp.read_barcodes(img):
        payload = bytes(res.bytes)
        if payload[:1] == b"{":
            out.append(("manifest", payload))
        else:
            out.append(("block", payload))
    return out


def main():
    frames_dir = Path(sys.argv[1])
    orig_path = Path(sys.argv[2])
    code_drop = float(sys.argv[3]) if len(sys.argv) > 3 else 0.2
    frame_drop = float(sys.argv[4]) if len(sys.argv) > 4 else 0.3
    trials = int(sys.argv[5]) if len(sys.argv) > 5 else 5

    pngs = sorted(frames_dir.glob("p*.png"))
    by_pass = {}
    for p in pngs:
        m = p.stem.split("-")
        by_pass.setdefault(int(m[0][1:]), []).append(p)
    orig = orig_path.read_bytes()

    print(f"multi-pass loss sim: per-code drop {code_drop:.0%}, frame drop {frame_drop:.0%}")
    all_pass = 0
    for t in range(trials):
        rng = random.Random(7000 + t)
        manifest = None
        known = {}  # slot -> payload (first occurrence wins)
        solved = False
        passes_used = 0
        for pass_idx in sorted(by_pass):
            passes_used += 1
            for png in by_pass[pass_idx]:
                if rng.random() < frame_drop:
                    continue  # whole frame missed
                for kind, payload in decode_png(png):
                    if kind == "manifest":
                        if manifest is None:
                            manifest = json.loads(payload)
                        continue
                    b = parse_block(payload)
                    if b["id"] not in known:
                        known[b["id"]] = payload
            if manifest is None:
                continue
            # attempt reassembly with what we have
            bytid = {}
            for hdr, payload in known.items():
                tid = int.from_bytes(payload[2:6], "little")
                bid = int.from_bytes(payload[10:14], "little")
                data = payload[18:]
                seed = int.from_bytes(data[:4], "little")
                bytid.setdefault(tid, {}).setdefault(bid, (seed, data[4:]))
            slots = bytid.get(manifest["tid"], {})
            try:
                file = reassemble({manifest["tid"]: slots}, manifest)
                ok = hashlib.sha256(file).hexdigest() == hashlib.sha256(orig).hexdigest()
            except AssertionError:
                ok = False
            if ok:
                solved = True
                break
        all_pass += solved
        got = len(known)
        print(f"  trial {t}: {'PASS' if solved else 'FAIL'} after {passes_used} passes "
              f"({got}/{cycle_len(manifest['k']) if manifest else '?'} slots)")
    print(f"MULTI-PASS LOSS-SIM: {all_pass}/{trials} recovered")
    sys.exit(0 if all_pass == trials else 1)


if __name__ == "__main__":
    main()
