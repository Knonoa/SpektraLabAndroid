# SpektraDroid tools

Offline tooling for the Android engine. Nothing here runs on the phone.

| file | what |
|---|---|
| `bake_assets.py` | writes `app/src/main/assets/engine/engine.bin` (format v2): illuminants, CMFs, dichroics, per-film hanatos2025 tc_lut, spectra and curves — every number taken from the **spektrafilm Python reference itself** |
| `spk_ref.py` | shared glue: finds SpektraLab's `engine/` data, resolves `derived_from` profiles, points spektrafilm at SpektraLab's stocks and neutral-filter database |
| `parity/` | engine ↔ reference parity harness and engine behaviour tests — see `parity/README.md` |

## Setup

```bash
# spektrafilm (the reference) -- not vendored in this repo
git clone https://github.com/andreavolpato/spektrafilm
# python 3.13 + its runtime deps
pip install "numpy~=2.4" "scipy~=1.17" "colour-science~=0.4.6" scikit-image matplotlib \
            "opt-einsum~=3.4.0" numba pyfftw "OpenImageIO~=3.1.11" exiv2 rawpy pillow
```

SpektraLab's profile data is found automatically: `<repo>/engine/` in the
SpektraLabAndroid repo, or `<workspace>/upstream/SpektraLab/engine/` next to a
workspace checkout. Override with `SPEKTRALAB_ENGINE=...`.

## Re-baking engine.bin

```bash
PYTHONPATH=spektrafilm/src python SpektraDroid/tools/bake_assets.py
# or bake to a scratch file and compare with the shipped one:
SPK_BAKE_OUT=/tmp/engine.bin PYTHONPATH=spektrafilm/src python SpektraDroid/tools/bake_assets.py
cmp /tmp/engine.bin SpektraDroid/app/src/main/assets/engine/engine.bin
```

Re-bake whenever SpektraLab's profiles or neutral print filters change, then
re-run the parity harness.
