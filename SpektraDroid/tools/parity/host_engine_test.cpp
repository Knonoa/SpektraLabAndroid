// host_engine_test.cpp -- behaviour tests for the engine API the app relies on:
//  * the cached negative gives byte-identical prints to a cold render
//  * print-side edits reuse the negative (and are much faster)
//  * every stock pair renders without error
//  * cancellation aborts and leaves the engine usable
//  * a 1-pixel and a very wide image do not crash
//
//   host_engine_test <engine.bin>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

using namespace spk;
using clk = std::chrono::steady_clock;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static std::vector<float> scene(int w, int h) {
    std::vector<float> img((size_t)w * h * 3);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float* p = &img[((size_t)y * w + x) * 3];
            float u = (float)x / std::max(1, w - 1), v = (float)y / std::max(1, h - 1);
            p[0] = std::pow(u, 2.2f);
            p[1] = std::pow(v, 2.2f);
            p[2] = std::pow(0.5f * (1 - u) + 0.3f * v, 2.2f);
            if ((x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < (w / 12) * (w / 12)) p[0] = p[1] = p[2] = 1.0f;
        }
    return img;
}

static double ms(clk::time_point a, clk::time_point b) { return std::chrono::duration<double, std::milli>(b - a).count(); }

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    std::ifstream f(argv[1], std::ios::binary);
    std::vector<uint8_t> bin((std::istreambuf_iterator<char>(f)), {});
    EngineContext& ctx = EngineContext::instance();
    if (!ctx.init(bin.data(), bin.size())) { std::printf("init: %s\n", ctx.error.c_str()); return 1; }

    const int w = 480, h = 320;
    auto img = scene(w, h);
    std::vector<uint8_t> a((size_t)w * h * 4), b((size_t)w * h * 4);
    std::string err;

    // 1. cached negative == cold render
    {
        Engine e1(ctx.data, ctx.cam16), e2(ctx.data, ctx.cam16);
        RenderParams p;
        p.seed = 42;
        CHECK(e1.render(img.data(), w, h, 7, p, a.data(), err), "render: %s", err.c_str());
        p.y_filter_shift = -12;
        p.print_exposure = 0.7f;
        p.print_stock = "kodak_supra_endura";
        auto t0 = clk::now();
        CHECK(e1.render(img.data(), w, h, 7, p, a.data(), err), "cached render: %s", err.c_str());
        auto t1 = clk::now();
        CHECK(e2.render(img.data(), w, h, -1, p, b.data(), err), "cold render: %s", err.c_str());
        auto t2 = clk::now();
        CHECK(a == b, "cached negative output differs from a cold render");
        std::printf("print-side edit: cached %.0f ms vs cold %.0f ms\n", ms(t0, t1), ms(t1, t2));
        CHECK(ms(t0, t1) < ms(t1, t2), "cache gave no speed-up");
        // film-side change must invalidate
        p.exposure_ev = 1.0f;
        CHECK(e1.render(img.data(), w, h, 7, p, a.data(), err), "%s", err.c_str());
        CHECK(e2.render(img.data(), w, h, -1, p, b.data(), err), "%s", err.c_str());
        CHECK(a == b, "film-side change did not invalidate the cached negative");
    }

    // 2. every film with its own paper, plus scan
    {
        Engine e(ctx.data, ctx.cam16);
        const int sw = 96, sh = 64;
        auto small = scene(sw, sh);
        std::vector<uint8_t> o((size_t)sw * sh * 4);
        for (const auto& film : ctx.data.films) {
            RenderParams p;
            p.film_stock = film.stock;
            p.print_stock = film.target_print.empty() ? "kodak_portra_endura" : film.target_print;
            p.scan_film = film.positive;
            bool ok = e.render(small.data(), sw, sh, -1, p, o.data(), err);
            CHECK(ok, "%s: %s", film.stock.c_str(), err.c_str());
            double mean = 0;
            for (size_t i = 0; i < o.size(); i += 4) mean += (o[i] + o[i + 1] + o[i + 2]) / 3.0;
            mean /= sw * sh;
            CHECK(mean > 20 && mean < 235, "%s: mean %.1f looks wrong", film.stock.c_str(), mean);
        }
        for (const auto& paper : ctx.data.papers) {
            RenderParams p;
            p.print_stock = paper.stock;
            CHECK(e.render(small.data(), sw, sh, -1, p, o.data(), err), "%s: %s", paper.stock.c_str(), err.c_str());
        }
        RenderParams bad;
        bad.film_stock = "nope";
        CHECK(!e.render(small.data(), sw, sh, -1, bad, o.data(), err) && err.find("unknown") != std::string::npos,
              "unknown stock not reported");
    }

    // 3. cancellation
    {
        Engine e(ctx.data, ctx.cam16);
        RenderParams p;
        int polls = 0;
        CancelFn cancel = [&] { return ++polls > 3; };
        bool ok = e.render(img.data(), w, h, 1, p, a.data(), err, &cancel);
        CHECK(!ok && err == "cancelled", "expected cancelled, got ok=%d err=%s", ok, err.c_str());
        CancelFn never = [] { return false; };
        CHECK(e.render(img.data(), w, h, 1, p, a.data(), err, &never), "engine unusable after cancel: %s", err.c_str());
        Engine e2(ctx.data, ctx.cam16);
        CHECK(e2.render(img.data(), w, h, -1, p, b.data(), err), "%s", err.c_str());
        CHECK(a == b, "render after a cancelled one differs");
    }

    // 4. degenerate sizes
    {
        Engine e(ctx.data, ctx.cam16);
        RenderParams p;
        std::vector<float> one = {0.2f, 0.3f, 0.4f};
        std::vector<uint8_t> o(4);
        CHECK(e.render(one.data(), 1, 1, -1, p, o.data(), err), "1x1: %s", err.c_str());
        auto wide = scene(1500, 3);
        std::vector<uint8_t> ow(1500 * 3 * 4);
        CHECK(e.render(wide.data(), 1500, 3, -1, p, ow.data(), err), "1500x3: %s", err.c_str());
        std::vector<float> black((size_t)64 * 64 * 3, 0.0f);
        std::vector<uint8_t> ob(64 * 64 * 4);
        CHECK(e.render(black.data(), 64, 64, -1, p, ob.data(), err), "black: %s", err.c_str());
    }

    std::printf(failures ? "%d FAILURES\n" : "all engine tests passed\n", failures);
    return failures ? 1 : 0;
}
