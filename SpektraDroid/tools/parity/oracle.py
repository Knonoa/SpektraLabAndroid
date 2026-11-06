#!/usr/bin/env python3
"""oracle.py -- run the spektrafilm Python reference on a linear-sRGB .npy and
dump the same taps host_parity writes.

  PYTHONPATH=upstream/spektrafilm/src python tools/parity/oracle.py in.npy out_dir key=value ...

Keys are the spk::RenderParams names (see host_parity.cpp). The input is
treated exactly as the Android app feeds the engine: linear sRGB, no cctf.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import spk_ref  # noqa: E402

spk_ref.patch_spektrafilm()

from spektrafilm.runtime.params_builder import digest_params, init_params  # noqa: E402
from spektrafilm.runtime.pipeline import SimulationPipeline  # noqa: E402

TAPS = ["log_e_film", "cmy_film", "log_e_print", "cmy_print"]


def parse(argv):
    kv = {}
    for a in argv:
        k, v = a.split("=", 1)
        kv[k] = v
    return kv


def b(v):
    return v in ("1", "true", "True")


def build_params(kv):
    p = init_params(kv.get("film_stock", "kodak_portra_400"),
                    kv.get("print_stock", "kodak_portra_endura"))
    p.io.input_color_space = "sRGB"
    p.io.input_cctf_decoding = False
    p.io.scan_film = b(kv.get("scan_film", "0"))
    p.camera.exposure_compensation_ev = float(kv.get("exposure_ev", 0))
    p.camera.auto_exposure = b(kv.get("auto_exposure", "1"))
    p.camera.film_format_mm = float(kv.get("film_format_mm", 35))
    h = p.film_render.halation
    h.active = b(kv.get("halation_active", "1"))
    h.halation_amount = float(kv.get("halation_amount", 1))
    h.scatter_amount = float(kv.get("scatter_amount", 1))
    d = p.film_render.dir_couplers
    d.active = b(kv.get("dir_couplers_active", "1"))
    d.amount = float(kv.get("dir_couplers_amount", 1))
    g = p.film_render.grain
    g.active = b(kv.get("grain_active", "1"))
    g.sublayers_active = b(kv.get("grain_sublayers", "1"))
    p.enlarger.print_exposure = float(kv.get("print_exposure", 1))
    p.enlarger.y_filter_shift = float(kv.get("y_filter_shift", 0))
    p.enlarger.m_filter_shift = float(kv.get("m_filter_shift", 0))
    p.enlarger.print_exposure_compensation = b(kv.get("print_exposure_compensation", "0"))
    p.print_render.glare.active = b(kv.get("glare_active", "1"))
    p.print_render.glare.percent *= float(kv.get("glare_amount", 1))
    if not b(kv.get("unsharp_mask", "1")):
        p.scanner.unsharp_mask = (0.0, 0.0)
    # the B&W product profiles carry no hanatos window fit (see bake_assets.py)
    if np.size(p.film.data.hanatos2025_adaptation_window_params) == 0:
        p.settings.apply_hanatos2025_adaptation_window = False
    p.settings.use_enlarger_lut = False
    p.settings.use_scanner_lut = False
    return digest_params(p)


def main():
    src, out = sys.argv[1], Path(sys.argv[2])
    kv = parse(sys.argv[3:])
    out.mkdir(parents=True, exist_ok=True)
    image = np.load(src).astype(np.float64)
    params = build_params(kv)
    pipe = SimulationPipeline(params)
    x = pipe.process(image, collect="log_e_film")
    np.save(out / "log_e_film.npy", x.astype(np.float32))
    x = pipe.process(x, inject="log_e_film", collect="cmy_film")
    np.save(out / "cmy_film.npy", x.astype(np.float32))
    if not params.io.scan_film:
        x = pipe.process(x, inject="cmy_film", collect="log_e_print")
        np.save(out / "log_e_print.npy", x.astype(np.float32))
        x = pipe.process(x, inject="log_e_print", collect="cmy_print")
        np.save(out / "cmy_print.npy", x.astype(np.float32))
        rgb = pipe.process(x, inject="cmy_print", collect="rgb_out")
    else:
        rgb = pipe.process(x, inject="cmy_film", collect="rgb_out")
    np.save(out / "rgb_out_encoded.npy", rgb.astype(np.float32))
    rgb8 = np.round(np.clip(rgb, 0, 1) * 255).astype(np.uint8)
    np.save(out / "rgb_out.npy", rgb8)


if __name__ == "__main__":
    main()
