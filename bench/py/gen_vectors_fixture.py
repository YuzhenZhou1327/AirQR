"""Converts spec/vectors/cases.json to a flat TSV fixture the JVM-side
Java tests read directly (android/test/vectors.tsv). Regenerate on any
protocol change: python bench/py/gen_vectors_fixture.py
"""
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
cases = json.loads((root / "spec" / "vectors" / "cases.json").read_text())["cases"]
out = root / "android" / "test" / "vectors.tsv"
cols = ["case", "file_hex", "blen", "k", "seed", "tid", "block_0_hex",
        "block_c0_hex", "manifest_json", "all_blocks_hex", "sel_vectors"]
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("\t".join(cols) + "\n")
    for c in cases:
        vals = [str(c[k]) for k in cols]
        for v in vals:
            assert "\t" not in v and "\n" not in v, "TSV-unsafe value"
        f.write("\t".join(vals) + "\n")
print(f"wrote {out} ({len(cases)} cases)")
