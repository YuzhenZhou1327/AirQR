"""QR binary-mode capacity calibration: segno encoder + zxing-cpp decoder.

Empirically bisects max byte capacity per (version, ECC), then verifies
decode round-trip at capacity. Output table feeds spec/qr-capacity.md.
"""
import segno
import zxingcpp
from PIL import Image
import io

def make_qr(data: bytes, version: int, ecc: str) -> Image.Image:
    q = segno.make_qr(data, version=version, error=ecc, mode="byte")
    buf = io.BytesIO()
    q.save(buf, kind="png", scale=4, border=2)
    buf.seek(0)
    return Image.open(buf).convert("L")

def max_capacity(version: int, ecc: str) -> int:
    lo, hi = 1, 4000  # hi above any QR capacity
    while lo < hi:
        mid = (lo + hi + 1) // 2
        try:
            make_qr(b"\x41" * mid, version, ecc)
            lo = mid
        except Exception:
            hi = mid - 1
    return lo

def decode_ok(img: Image.Image) -> bytes | None:
    try:
        res = zxingcpp.read_barcodes(img)
    except Exception:
        return None
    if not res:
        return None
    return bytes(res[0].bytes)

if __name__ == "__main__":
    print(f"{'ver':>4} {'ecc':>3} {'cap(B)':>7}  decode@cap  decode@cap-1  decode@2914")
    rows = []
    for ecc in ("L", "M"):
        for ver in (10, 25, 40):
            cap = max_capacity(ver, ecc)
            dec_cap = decode_ok(make_qr(b"\x41" * cap, ver, ecc))
            dec_cap1 = decode_ok(make_qr(b"\x41" * (cap - 1), ver, ecc))
            dec_2914 = "n/a"
            if ver == 40 and cap >= 2914:
                r = decode_ok(make_qr(b"\x41" * 2914, ver, ecc))
                dec_2914 = "OK" if r == b"\x41" * 2914 else f"MISMATCH len={len(r) if r else 0}"
            row = dict(ver=ver, ecc=ecc, cap=cap,
                       dec_cap=(dec_cap == b"\x41" * cap), dec_cap1=(dec_cap1 == b"\x41" * (cap - 1)),
                       dec_2914=dec_2914)
            rows.append(row)
            print(f"{ver:>4} {ecc:>3} {cap:>7}  {str(row['dec_cap']):>10}  {str(row['dec_cap1']):>12}  {dec_2914}")
    print("\n--- verdict ---")
    for r in rows:
        ok = r["dec_cap"] and r["dec_cap1"]
        if r["ver"] == 40 and r["ecc"] == "L":
            ok = ok and r["dec_2914"] == "OK"
        print(f"v{r['ver']}-{r['ecc']}: {'PASS' if ok else 'FAIL'}")
