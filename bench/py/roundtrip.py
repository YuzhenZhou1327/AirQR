"""Golden round-trip: Go renders PNG frames -> zxing-cpp (3rd party) decodes
every cell -> an INDEPENDENT Python LT implementation reassembles the file
-> compare sha256. No Go code involved in decode; proves the wire format and
LT math are correct as specified, not merely self-consistent.

Protocol constants are re-derived from spec/PROTOCOL.md v1.1, deliberately
NOT imported from the Go implementation.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

import zxingcpp
from PIL import Image

M64 = (1 << 64) - 1
PHI = 0x9E3779B97F4A7C15


def splitmix64_next32(st: int) -> tuple[int, int]:
    st = (st + PHI) & M64
    z = st
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    z = (z ^ (z >> 31)) & M64
    return st, z & 0xFFFFFFFF


class Rng:
    def __init__(self, seed: int):
        self.st = seed & M64

    def next32(self) -> int:
        self.st, out = splitmix64_next32(self.st)
        return out


def selections(seed: int, k: int) -> tuple[int, list[bool]]:
    p = Rng(((seed * PHI) & M64) ^ (k & 0xFFFFFFFF))
    u = p.next32() % 10000
    if u < 200:
        deg = 1
    elif u < 1500:
        deg = 2
    elif u < 2900:
        deg = 3
    elif u < 4000:
        deg = 4
    elif u < 6500:
        deg = 5 + p.next32() % 6
    else:
        deg = 11 + p.next32() % 54
    sel = [False] * k
    for _ in range(deg):
        idx = p.next32() % k
        sel[idx] = not sel[idx]
    return deg, sel


def cycle_len(k: int) -> int:
    return max(32, (k * 5 + 3) // 4)


def parse_block(payload: bytes) -> dict:
    assert payload[0] == 0x51, "magic"
    assert payload[1] == 1, "version"
    tid = int.from_bytes(payload[2:6], "little")
    blen = int.from_bytes(payload[6:10], "little")
    bid = int.from_bytes(payload[10:14], "little")
    return {"tid": tid, "blen": blen, "id": bid, "data": payload[14:]}


def decode_frames(frames_dir: Path) -> dict:
    """Decode all PNGs; returns {tid: {slot_id: (seed, payload)}}."""
    bytid: dict[int, dict] = {}
    manifest = None
    pngs = sorted(frames_dir.glob("p*.png"))
    assert pngs, f"no frames in {frames_dir}"
    for png in pngs:
        img = Image.open(png).convert("L")
        results = zxingcpp.read_barcodes(img)
        assert results, f"{png.name}: no barcodes found"
        for res in results:
            payload = bytes(res.bytes)
            if payload[:1] == b"{":
                man = json.loads(payload)
                assert man["fmt"] == "airqr1"
                manifest = man
                continue
            b = parse_block(payload)
            slot = bytid.setdefault(b["tid"], {})
            if b["id"] not in slot:
                seed = int.from_bytes(b["data"][:4], "little")
                slot[b["id"]] = (seed, b["data"][4:])
    return {"bytid": bytid, "manifest": manifest}


def reassemble(bytid: dict, manifest: dict) -> bytes:
    tid = manifest["tid"]
    k, blen, size = manifest["k"], manifest["blen"], manifest["size"]
    slots = bytid[tid]
    known: list[bytes | None] = [None] * k
    eqs: list[list] = []  # [sel(list[bool]), data(bytes)]
    solved = 0

    def peel_from(eq):
        nonlocal solved
        changed = True
        while changed:
            changed = False
            for i, on in enumerate(eq[0]):
                if on and known[i] is not None:
                    eq[1] = bytes(a ^ b for a, b in zip(eq[1], known[i]))
                    eq[0][i] = False
                    changed = True
            unk = [i for i, on in enumerate(eq[0]) if on]
            if len(unk) == 1:
                i = unk[0]
                known[i] = eq[1][:]
                solved += 1
                eq[0][i] = False
                changed = True

    def peel_slot(i):
        for eq in eqs:
            if eq[0][i] and known[i] is not None:
                eq[1] = bytes(a ^ b for a, b in zip(eq[1], known[i]))
                eq[0][i] = False
                unk = [j for j, on in enumerate(eq[0]) if on]
                if len(unk) == 1:
                    peel_from(eq)

    for sid in sorted(slots):
        seed, payload = slots[sid]
        if sid < k:
            pad = bytearray(blen)
            pad[: len(payload)] = payload
            known[sid] = bytes(pad)
            solved += 1
            peel_slot(sid)
        else:
            _, sel = selections(seed, k)
            data = bytearray(blen)
            for i, on in enumerate(sel):
                if on and known[i] is not None:
                    data = bytearray(a ^ b for a, b in zip(data, known[i]))
                    sel[i] = False
            unk = [i for i, on in enumerate(sel) if on]
            if len(unk) == 0:
                continue
            eq = [sel, bytes(data)]
            if len(unk) == 1:
                peel_from(eq)
            else:
                eqs.append(eq)

    assert solved == k, f"solved {solved}/{k}"
    out = bytearray()
    for i in range(k):
        out += known[i]
    return bytes(out[:size])


def main():
    frames_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "temp/frames")
    dec = decode_frames(frames_dir)
    manifest = dec["manifest"]
    assert manifest, "no manifest decoded"
    print(f"manifest: {manifest}")
    bytid = dec["bytid"]
    assert len(bytid) == 1, f"expected one session, got {list(bytid)}"
    tid, slots = next(iter(bytid.items()))
    assert tid == manifest["tid"], "tid mismatch between manifest and blocks"
    k = manifest["k"]
    lt_slots = sorted(i for i in slots if i >= k)
    src_slots = sorted(i for i in slots if i < k)
    print(f"blocks: {len(src_slots)} source + {len(lt_slots)} LT = {len(slots)} unique "
          f"(cycle={cycle_len(k)})")
    file = reassemble(bytid, manifest)
    digest = hashlib.sha256(file).hexdigest()
    # compare against original file
    session = {}
    for line in (frames_dir / "session.txt").read_text().splitlines():
        kk, _, vv = line.partition("=")
        session[kk] = vv
    orig = Path(sys.argv[2] if len(sys.argv) > 2 else "temp/sample.bin").read_bytes()
    assert len(orig) == manifest["size"], "size mismatch"
    ok = hashlib.sha256(orig).hexdigest() == digest
    print(f"sha256(reassembled) = {digest}")
    print(f"sha256(original)    = {hashlib.sha256(orig).hexdigest()}")
    print("ROUND-TRIP:", "PASS ✓" if ok else "FAIL ✗")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
