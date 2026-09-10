"""Loss simulation on top of the decoded frame set: randomly drop codes the
way a handheld camera would (per-frame bursts + per-code noise) and verify
the file still reassembles. Proves fountain behavior end-to-end with a
third-party decoder, not just the full-cycle case.
"""
import hashlib
import random
import sys
from pathlib import Path

import zxingcpp
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from roundtrip import decode_frames, reassemble, cycle_len  # noqa: E402


def bursty_loss(pngs, drop_prob, rng):
    """A handheld camera misses whole frames (blur/motion) plus random single
    codes (edge cutoff). Returns surviving codes per png name."""
    keep = {}
    for png in pngs:
        if rng.random() < 0.30:
            continue  # whole frame missed
        img = Image.open(png).convert("L")
        for res in zxingcpp.read_barcodes(img):
            payload = bytes(res.bytes)
            if payload[:1] == b"{":
                keep.setdefault("manifest", payload)
                continue
            if rng.random() < drop_prob:
                continue
            keep.setdefault(payload[:18], payload)
    return keep


def main():
    frames_dir = Path(sys.argv[1])
    orig_path = Path(sys.argv[2])
    drops = float(sys.argv[3]) if len(sys.argv) > 3 else 0.2
    trials = int(sys.argv[4]) if len(sys.argv) > 4 else 5
    pngs = sorted(frames_dir.glob("p*.png"))
    orig = orig_path.read_bytes()
    print(f"loss sim: {len(pngs)} frames, per-code drop {drops:.0%}, {trials} trials")
    passes = 0
    for t in range(trials):
        rng = random.Random(1000 + t)
        keep = bursty_loss(pngs, drops, rng)
        # rebuild structures expected by reassemble()
        man = json_loads(keep["manifest"])
        bytid = {}
        for hdr, payload in keep.items():
            if hdr == "manifest":
                continue
            tid = int.from_bytes(payload[2:6], "little")
            bid = int.from_bytes(payload[10:14], "little")
            data = payload[18:]
            seed = int.from_bytes(data[:4], "little")
            bytid.setdefault(tid, {}).setdefault(bid, (seed, data[4:]))
        k = man["k"]
        got = len(next(iter(bytid.values())))
        try:
            file = reassemble(bytid, man)
            ok = hashlib.sha256(file).hexdigest() == hashlib.sha256(orig).hexdigest()
        except AssertionError as e:
            ok = False
            file = None
            print(f"  trial {t}: decode failed ({e})")
        passes += ok
        if ok:
            print(f"  trial {t}: {got}/{cycle_len(k)} slots, {drops:.0%} drop -> PASS")
    print(f"LOSS-SIM: {passes}/{trials} trials recovered")
    sys.exit(0 if passes == trials else 1)


def json_loads(b):
    import json
    return json.loads(b)


if __name__ == "__main__":
    main()
