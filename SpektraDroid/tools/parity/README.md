# tools/parity — engine ↔ spektrafilm parity harness

| file | what |
|---|---|
| `oracle.py` | runs the **spektrafilm Python runtime** (upstream/spektrafilm/src) on a linear-sRGB `.npy`, dumps the topology taps `log_e_film`, `cmy_film`, `log_e_print`, `cmy_print` and the 8-bit output |
| `host_parity.cpp` | runs the **Android C++ engine** on the host with the same params, dumps the same taps (`SPK_TIMING=1` prints per-stage timing instead) |
| `compare.py` | tap-by-tap max / p99 / mean differences, plus a side-by-side PNG |
| `host_engine_test.cpp` | behaviour tests of the engine API the app relies on (cached negative == cold render, cancellation, every stock renders, degenerate sizes). Build it with `-fsanitize=address,undefined` or `thread` too. |
| `run_parity.sh` | build + run all three (`SPEKTRAFILM_SRC`, `PYTHON`, `CXX` env vars; see its header) |

Profiles and neutral print filters are SpektraLab's (`../spk_ref.py` points the
Python runtime at them), so both sides render the stocks the app ships.

Results on 2026-11-06 (portrait 320×480, every film/paper family incl. B&W,
cine, slides scanned, EV / filter / print-exposure / DIR / format changes):
deterministic taps agree to ~1e-4 density, **output within 1/255** (0 pixels
off by more than 2). With grain on, per-patch grain bias and RMS match the
reference to ~1e-4 density; with glare on, the mean output shift matches.

Re-run after any engine change, and re-bake (`tools/bake_assets.py`) when the
upstream profiles change.
