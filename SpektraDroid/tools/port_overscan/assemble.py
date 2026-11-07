"""Assembles SpektraDroid/app/src/main/cpp/overscan.cpp from the desktop engine's
engine/src/pipeline/overscan.cpp (taken by line range, unchanged) and the Android
parts in this directory (*.part: the CPU kernels, the text rasteriser, the API).

    python3 SpektraDroid/tools/port_overscan/assemble.py [--check]

The line ranges below are those of overscan.cpp at the commit this was ported
from (6c9199d). When the desktop engine changes, update the ranges, re-run, diff.
"""
import pathlib, sys
HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]   # the SpektraLabAndroid checkout
src = (ROOT / "engine/src/pipeline/overscan.cpp").read_text().split("\n")
def lines(a, b):  # 1-based inclusive
    return "\n".join(src[a - 1:b]) + "\n"
def part(n): return (HERE / n).read_text()
def sub(text, old, new, count=1):
    assert text.count(old) == count, (old[:80], text.count(old))
    return text.replace(old, new)

# H: the layout, minus the desktop pipeline's members
H = lines(507, 702)
H = sub(H, "bool Pipeline::overscan_layout(uint32_t frame_w, uint32_t frame_h, std::string& error) {\n    const OverscanParams& o = params_.film_render.overscan;\n",
        "bool overscan_layout(const OverscanParams& o, double px_mm, uint32_t frame_w, uint32_t frame_h,\n                     OverscanLayout& L, std::string& error) {\n")
H = sub(H, """    if (params_.settings.striped) {
        error = "overscan: not supported by the striped executor yet (RFC-032 §27); render with striped = false";
        return false;
    }
""", "")
H = sub(H, "        const bool pair = o.pair;\n", "        const bool pair = false;   // Android: no half-frame pairs\n")
H = sub(H, "    OverscanLayout& L = overscan_;\n    L = OverscanLayout{};\n", "    L = OverscanLayout{};\n")
H = sub(H, "    L.px = pixel_size_um_ / 1000.0;\n", "    L.px = px_mm;\n")
H = sub(H, "    if (o.pair) {\n", "    if (false) {   // Android: no half-frame pairs\n")

# G: the imprints; the date in the frame takes the data line too (Android)
G = lines(1388, 2150)
old_frame = G[G.index("    } else if (!date_text.empty()) {\n        // In the frame"):G.index("    if (g.ops.empty()) return;\n    out.push_back(g);\n}\n", G.index("// In the frame"))]
new_frame = '''    } else if (!date_text.empty() || (d.data_in_frame && !data_drawn && !data_text.empty())) {
        // In the frame, at the chosen corner of the *film's* frame (the corner
        // as the camera's back sees it, held level), inset from the gate.
        // Android: with no rebate to print it in, the data line goes in the
        // frame too, beside the date (Nikon's MF-28 back imprints either way).
        const bool right = d.corner == "br" || d.corner == "tr", bottom = d.corner == "br" || d.corner == "bl";
        // With overscan the layout is already in film mm. The date alone draws
        // on the bare frame, so the film's direction comes from its shape: a
        // full frame taller than wide, or a half frame wider than tall, was
        // shot with the camera turned, and the film runs down the picture
        // (the same quarter turn the overscan layout uses).
        bool turned = false;
        double fs = gs, ft = gt, fw = gw, fh = gh;
        if (!L.valid) {
            turned = fmt == "135_half" ? frame_w_mm > frame_h_mm : frame_h_mm > frame_w_mm;
            fs = 0.0; ft = 0.0;
            fw = turned ? frame_h_mm : frame_w_mm;
            fh = turned ? frame_w_mm : frame_h_mm;
        }
        auto in_frame = [&](const std::string& text, const std::string& style, double h, double lift) {
            const double w = text_width(text, style, h);
            // Kept inside the frame: a large face or an inset past the frame's
            // middle would otherwise carry the text off its edge.
            const double over = (style == "lcd" ? h * std::tan(8.0 * M_PI / 180.0) : 0.0) + 0.1;   // the slant, and the blur
            const double s0 = std::clamp(right ? fs + fw - d.inset_x - w : fs + d.inset_x, fs + 0.1, std::max(fs + 0.1, fs + fw - w - over));
            const double want = bottom ? ft + fh - d.inset_y - lift : ft + d.inset_y + h + lift;
            const double base = std::clamp(want, std::min(ft + h + 0.1, ft + fh - 0.1), ft + fh - 0.1);
            draw(text, style, h, [&](double x, double y, double& ss, double& tt) {
                const double sf = s0 + x, tf = base - y;
                if (!turned) { ss = sf; tt = tf; }
                else { ss = fh - tf; tt = sf; }       // picture x = across - t, y = s
            });
        };
        const double h_date = (d.style == "dots" ? 0.95 : 1.3) * k;
        if (!date_text.empty()) in_frame(date_text, d.style, h_date, 0.0);
        if (d.data_in_frame && !data_drawn && !data_text.empty())
            in_frame(data_text, "data", 0.62 * k, date_text.empty() ? 0.0 : h_date + 0.45 * k);
    }
'''
G = G.replace(old_frame, new_frame)
assert "data_in_frame" in G

out = (part("head.part") + lines(62, 70) + "\n" + lines(72, 129) + "\n" + lines(131, 238) + "\n" + lines(240, 316)
       + part("lights.part") + lines(354, 475) + part("text.part") + lines(991, 1372) + "\n" + G
       + part("raster.part") + "}  // namespace\n\n" + H + part("layout_tail.part") + part("public.part"))
# the desktop engine's CoreText-era note on text rasterisation
out = out.replace("// Text is rasterised with CoreText/CoreGraphics.\n", "")
import re
out = re.sub(r"\bRng\b", "OsRng", out)   # engine_data.hpp has its own spk::Rng
dst = ROOT / "SpektraDroid/app/src/main/cpp/overscan.cpp"
if "--check" in sys.argv:
    same = dst.read_text() == out
    print("overscan.cpp", "matches" if same else "DIFFERS from", "the assembly")
    sys.exit(0 if same else 1)
dst.write_text(out)
print("wrote", dst, len(out.splitlines()), "lines")
