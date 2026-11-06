#!/usr/bin/env python3
"""compare.py -- tap-by-tap diff of oracle.py vs host_parity output dirs.

  python tools/parity/compare.py ref_dir eng_dir [--png out.png]
"""
import sys
from pathlib import Path

import numpy as np

TAPS = ["log_e_film", "cmy_film", "log_e_print", "cmy_print"]


def main():
    ref, eng = Path(sys.argv[1]), Path(sys.argv[2])
    for t in TAPS:
        if not (ref / f"{t}.npy").exists() or not (eng / f"{t}.npy").exists():
            continue
        a = np.load(ref / f"{t}.npy").astype(np.float64)
        b = np.load(eng / f"{t}.npy").astype(np.float64)
        d = np.abs(a - b)
        print(f"{t:<14} max {d.max():.5f}  p99 {np.percentile(d, 99):.5f}  mean {d.mean():.6f}  "
              f"per-ch mean {np.round(d.mean(axis=(0, 1)), 6)}")
    a = np.load(ref / "rgb_out.npy").astype(np.int32)
    b = np.load(eng / "rgb_out.npy").astype(np.int32)
    d = np.abs(a - b)
    print(f"{'rgb_out (8bit)':<14} max {d.max()}  p99 {np.percentile(d, 99):.1f}  mean {d.mean():.3f}  "
          f">2: {np.mean(d > 2) * 100:.2f}%  mean signed {np.round((b - a).mean(axis=(0, 1)), 3)}")
    if "--png" in sys.argv:
        from PIL import Image
        out = sys.argv[sys.argv.index("--png") + 1]
        diff = np.clip(d * 8, 0, 255).astype(np.uint8)
        Image.fromarray(np.concatenate([a.astype(np.uint8), b.astype(np.uint8), diff], axis=1)).save(out)


if __name__ == "__main__":
    main()
