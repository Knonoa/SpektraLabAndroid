// host_film_edge.cpp -- renders Film Edge and Date Back samples on the host,
// through the same engine the phone runs, for looking at.
//
//   host_film_edge <engine.bin> <fonts dir> <photo.jpg> <out prefix> [long edge]
//
// Writes <prefix>_<case>.png for each case below and prints the canvas sizes
// and timings. Build: see tools/parity/README.md ("Film Edge samples").
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

using namespace spk;

static std::vector<uint8_t> slurp(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), {});
}

// box-filtered downscale of an RGBA8 image so the long edge is `edge`
static std::vector<uint8_t> downscale(const uint8_t* in, int w, int h, int edge, int& ow, int& oh) {
    const double s = std::min(1.0, double(edge) / std::max(w, h));
    ow = std::max(1, int(std::lround(w * s)));
    oh = std::max(1, int(std::lround(h * s)));
    std::vector<uint8_t> out((size_t)ow * oh * 4);
    for (int y = 0; y < oh; y++)
        for (int x = 0; x < ow; x++) {
            const int x0 = x * w / ow, x1 = std::max(x0 + 1, (x + 1) * w / ow);
            const int y0 = y * h / oh, y1 = std::max(y0 + 1, (y + 1) * h / oh);
            double acc[3] = {0, 0, 0};
            for (int yy = y0; yy < y1; yy++)
                for (int xx = x0; xx < x1; xx++)
                    for (int c = 0; c < 3; c++) acc[c] += in[((size_t)yy * w + xx) * 4 + c];
            const double n = double(x1 - x0) * (y1 - y0);
            for (int c = 0; c < 3; c++) out[((size_t)y * ow + x) * 4 + c] = uint8_t(std::lround(acc[c] / n));
            out[((size_t)y * ow + x) * 4 + 3] = 255;
        }
    return out;
}

struct Case {
    const char* name;
    const char* film;
    const char* paper;   // "" = scan the film
    bool edge;
    const char* format;
    const char* edge_text;
    const char* holes;
    bool date;
    const char* style;
    const char* text;
    const char* data;
    const char* placement;
};

int main(int argc, char** argv) {
    if (argc < 5) {
        std::printf("usage: host_film_edge <engine.bin> <fonts dir> <photo.jpg> <out prefix> [long edge]\n");
        return 2;
    }
    auto bin = slurp(argv[1]);
    EngineContext& ctx = EngineContext::instance();
    if (!ctx.init(bin.data(), bin.size())) { std::printf("init: %s\n", ctx.error.c_str()); return 1; }
    const std::string fonts = argv[2];
    ctx.fonts.regular = slurp(fonts + "/texgyreheros-regular.otf");
    ctx.fonts.bold = slurp(fonts + "/texgyreheros-bold.otf");
    ctx.fonts.condensed = slurp(fonts + "/texgyreheroscn-regular.otf");
    ctx.fonts.mono_bold = slurp(fonts + "/DejaVuSansMono-Bold.ttf");
    std::printf("fonts: %zu %zu %zu %zu bytes\n", ctx.fonts.regular.size(), ctx.fonts.bold.size(),
                ctx.fonts.condensed.size(), ctx.fonts.mono_bold.size());

    int iw, ih, n;
    uint8_t* px = stbi_load(argv[3], &iw, &ih, &n, 4);
    if (!px) { std::printf("cannot read %s\n", argv[3]); return 1; }
    int w, h;
    auto small = downscale(px, iw, ih, argc > 5 ? std::atoi(argv[5]) : 1600, w, h);
    stbi_image_free(px);
    std::vector<float> lin((size_t)w * h * 3);
    srgb8_to_linear(small.data(), w, h, (size_t)w * 4, lin.data());

    const Case cases[] = {
        {"kodak135", "kodak_portra_400", "kodak_portra_endura", true, "135", "KODAK PORTRA 400", "white",
         true, "lcd", "'26 11 6", "1/250 F5.6 A +0.3 28mm", "frame"},
        {"gold135_rebate", "kodak_gold_200", "kodak_portra_endura", true, "135", "KODAK GB 200-7", "white",
         true, "lcd", "'26 11 6", "1/125 F8 P", "rebate"},
        {"provia135_black", "fujifilm_provia_100f", "", true, "135", "FUJI RDPIII", "black",
         true, "dots", "'26 11 6", "", "frame"},
        {"xtra_half", "fujifilm_xtra_400", "kodak_portra_endura", true, "135_half", "S-400", "white",
         true, "lcd", "'26 11 6", "", "frame"},
        {"cinestill135", "cinestill_800t", "kodak_portra_endura", true, "135", "CINESTILL 800T", "white",
         false, "lcd", "", "", "frame"},
        {"pro400h_6x7", "fujifilm_pro_400h", "kodak_portra_endura", true, "120_6x7", "FUJI PRO400H", "white",
         true, "lcd", "'26 11 6", "1/60 F4 A", "frame"},
        {"portra_6x6", "kodak_portra_400", "kodak_portra_endura", true, "120_6x6", "KODAK PORTRA 400", "white",
         false, "lcd", "", "", "frame"},
        {"date_only", "kodak_portra_400", "kodak_portra_endura", false, "135", "", "white",
         true, "lcd", "'26 11 6", "1/250 F5.6 A +0.3 28mm", "frame"},
    };
    for (const Case& c : cases) {
        RenderParams p;
        p.film_stock = c.film;
        p.scan_film = c.paper[0] == 0;
        if (!p.scan_film) p.print_stock = c.paper;
        if (ctx.data.film(p.film_stock) == nullptr) { std::printf("%-16s skipped: no %s\n", c.name, c.film); continue; }
        p.seed = 7919;
        p.overscan.active = c.edge;
        p.overscan.format = c.format;
        p.overscan.edge_text = c.edge_text;
        p.overscan.holes = c.holes;
        p.overscan.camera_seed = 417;
        p.overscan.frame_seed = 23;
        p.overscan.frame_number = 12;
        p.overscan.f_number = 5.6;
        p.date.active = c.date;
        p.date.style = c.style;
        p.date.text = c.text;
        p.date.data_text = c.data;
        p.date.placement = c.placement;
        int ow, oh;
        std::string err;
        if (!render_output_size(w, h, p, ow, oh, err)) { std::printf("%-16s size: %s\n", c.name, err.c_str()); continue; }
        std::vector<uint8_t> out((size_t)ow * oh * 4);
        Engine engine(ctx.data, ctx.cam16, &ctx.fonts);
        const auto t0 = std::chrono::steady_clock::now();
        const bool ok = engine.render(lin.data(), w, h, 1, p, out.data(), err);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (!ok) { std::printf("%-16s FAILED: %s\n", c.name, err.c_str()); continue; }
        const std::string path = std::string(argv[4]) + "_" + c.name + ".png";
        stbi_write_png(path.c_str(), ow, oh, 4, out.data(), ow * 4);
        std::printf("%-16s %4dx%-4d -> %4dx%-4d %7.0f ms  %s\n", c.name, w, h, ow, oh, ms, path.c_str());
    }

    // The cached negative carries the film canvas: a print-side edit on it must
    // give the bytes a cold render gives.
    {
        RenderParams p;
        p.film_stock = "kodak_portra_400";
        p.print_stock = "kodak_portra_endura";
        p.seed = 7919;
        p.overscan.active = true;
        p.overscan.edge_text = "KODAK PORTRA 400";
        p.date.active = true;
        p.date.text = "'26 11 6";
        int ow, oh;
        std::string err;
        render_output_size(w, h, p, ow, oh, err);
        std::vector<uint8_t> a((size_t)ow * oh * 4), b(a.size());
        Engine warm(ctx.data, ctx.cam16, &ctx.fonts), cold(ctx.data, ctx.cam16, &ctx.fonts);
        bool ok = warm.render(lin.data(), w, h, 5, p, a.data(), err);
        p.print_stock = "kodak_2383";
        p.print_exposure = 1.3f;
        const auto t0 = std::chrono::steady_clock::now();
        ok = ok && warm.render(lin.data(), w, h, 5, p, a.data(), err);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        ok = ok && cold.render(lin.data(), w, h, 5, p, b.data(), err);
        std::printf("cache check: %s (print-side edit %.0f ms)\n", ok && a == b ? "identical" : "MISMATCH", ms);
        if (!ok || a != b) return 1;
    }
    return 0;
}
