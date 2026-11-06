// host_parity.cpp -- runs the Android engine on the host and dumps every tap.
//
//   host_parity <engine.bin> <input.npy float32 HxWx3 linear sRGB> <out_dir> [key=value ...]
//
// Writes <out_dir>/<tap>.npy (float32 HxWx3) for log_e_film, cmy_film,
// log_e_print, cmy_print, rgb_out_linear, plus rgb_out.npy (uint8 HxWx3) and
// timing to stdout. Keys mirror spk::RenderParams field names.
//
// Build: see tools/parity/run_parity.sh
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

using namespace spk;

static std::vector<uint8_t> read_file(const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { std::fprintf(stderr, "cannot open %s\n", path); std::exit(1); }
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), {});
}

static bool read_npy_f32(const char* path, std::vector<float>& out, int& h, int& w) {
    auto buf = read_file(path);
    if (buf.size() < 10 || std::memcmp(buf.data(), "\x93NUMPY", 6) != 0) return false;
    const int major = buf[6];
    size_t hlen, off;
    if (major == 1) { hlen = buf[8] | (buf[9] << 8); off = 10; }
    else { hlen = buf[8] | (buf[9] << 8) | (buf[10] << 16) | ((size_t)buf[11] << 24); off = 12; }
    std::string header((const char*)buf.data() + off, hlen);
    if (header.find("'<f4'") == std::string::npos) { std::fprintf(stderr, "need <f4\n"); return false; }
    if (header.find("'fortran_order': False") == std::string::npos) return false;
    auto p = header.find("'shape': (");
    int c = 0;
    std::sscanf(header.c_str() + p + 10, "%d, %d, %d", &h, &w, &c);
    if (c != 3) return false;
    out.resize((size_t)h * w * 3);
    std::memcpy(out.data(), buf.data() + off + hlen, out.size() * 4);
    return true;
}

static void write_npy(const std::string& path, const void* data, const char* descr, int h, int w,
                      size_t elem) {
    std::ostringstream hd;
    hd << "{'descr': '" << descr << "', 'fortran_order': False, 'shape': (" << h << ", " << w
       << ", 3), }";
    std::string header = hd.str();
    size_t total = 10 + header.size() + 1;
    header.append((16 - total % 16) % 16, ' ');
    header += '\n';
    std::ofstream f(path, std::ios::binary);
    f.write("\x93NUMPY\x01\x00", 8);
    uint16_t hl = (uint16_t)header.size();
    f.write((const char*)&hl, 2);
    f.write(header.data(), header.size());
    f.write((const char*)data, (std::streamsize)((size_t)h * w * 3 * elem));
}

static bool parse_bool(const std::string& v) { return v == "1" || v == "true" || v == "True"; }

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s engine.bin input.npy out_dir [key=value ...]\n", argv[0]);
        return 2;
    }
    auto bin = read_file(argv[1]);
    EngineContext& ctx = EngineContext::instance();
    if (!ctx.init(bin.data(), bin.size())) {
        std::fprintf(stderr, "init failed: %s\n", ctx.error.c_str());
        return 1;
    }
    std::vector<float> rgb;
    int h = 0, w = 0;
    if (!read_npy_f32(argv[2], rgb, h, w)) { std::fprintf(stderr, "bad npy\n"); return 1; }
    std::string out_dir = argv[3];

    RenderParams p;
    int repeat = 1;
    for (int i = 4; i < argc; i++) {
        std::string kv = argv[i];
        auto eq = kv.find('=');
        std::string k = kv.substr(0, eq), v = kv.substr(eq + 1);
        if (k == "film_stock") p.film_stock = v;
        else if (k == "print_stock") p.print_stock = v;
        else if (k == "scan_film") p.scan_film = parse_bool(v);
        else if (k == "exposure_ev") p.exposure_ev = std::stof(v);
        else if (k == "auto_exposure") p.auto_exposure = parse_bool(v);
        else if (k == "film_format_mm") p.film_format_mm = std::stof(v);
        else if (k == "halation_active") p.halation_active = parse_bool(v);
        else if (k == "halation_amount") p.halation_amount = std::stof(v);
        else if (k == "scatter_amount") p.scatter_amount = std::stof(v);
        else if (k == "dir_couplers_active") p.dir_couplers_active = parse_bool(v);
        else if (k == "dir_couplers_amount") p.dir_couplers_amount = std::stof(v);
        else if (k == "grain_active") p.grain_active = parse_bool(v);
        else if (k == "grain_sublayers") p.grain_sublayers = parse_bool(v);
        else if (k == "grain_amount") p.grain_amount = std::stof(v);
        else if (k == "print_exposure") p.print_exposure = std::stof(v);
        else if (k == "y_filter_shift") p.y_filter_shift = std::stof(v);
        else if (k == "m_filter_shift") p.m_filter_shift = std::stof(v);
        else if (k == "print_exposure_compensation") p.print_exposure_compensation = parse_bool(v);
        else if (k == "glare_active") p.glare_active = parse_bool(v);
        else if (k == "glare_amount") p.glare_amount = std::stof(v);
        else if (k == "unsharp_mask") p.unsharp_mask = parse_bool(v);
        else if (k == "seed") p.seed = std::stoull(v);
        else if (k == "repeat") repeat = std::stoi(v);
        else { std::fprintf(stderr, "unknown key %s\n", k.c_str()); return 2; }
    }

    Engine engine(ctx.data, ctx.cam16);
    std::vector<uint8_t> out((size_t)w * h * 4);
    std::string err;
    const bool timing_only = std::getenv("SPK_TIMING") != nullptr;
    auto tlast = std::chrono::steady_clock::now();
    TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int tw, int th) {
        if (timing_only) {
            auto now = std::chrono::steady_clock::now();
            std::printf("  %-16s %.1f ms\n", name, std::chrono::duration<double, std::milli>(now - tlast).count());
            tlast = now;
            return;
        }
        std::vector<float> inter((size_t)tw * th * 3);
        for (size_t i = 0; i < (size_t)tw * th; i++) {
            inter[i * 3] = r[i];
            inter[i * 3 + 1] = g[i];
            inter[i * 3 + 2] = b[i];
        }
        write_npy(out_dir + "/" + name + ".npy", inter.data(), "<f4", th, tw, 4);
    };
    auto t0 = std::chrono::steady_clock::now();
    tlast = t0;
    if (!engine.render(rgb.data(), w, h, -1, p, out.data(), err, nullptr, tap)) {
        std::fprintf(stderr, "render failed: %s\n", err.c_str());
        return 1;
    }
    auto t1 = std::chrono::steady_clock::now();
    std::printf("first render (incl. setup) %.1f ms\n",
                std::chrono::duration<double, std::milli>(t1 - t0).count());
    for (int r = 1; r < repeat; r++) {
        auto a = std::chrono::steady_clock::now();
        engine.render(rgb.data(), w, h, 1, p, out.data(), err);
        auto b = std::chrono::steady_clock::now();
        std::printf("render %d %.1f ms\n", r, std::chrono::duration<double, std::milli>(b - a).count());
    }
    std::vector<uint8_t> rgb8((size_t)w * h * 3);
    for (size_t i = 0; i < (size_t)w * h; i++)
        for (int c = 0; c < 3; c++) rgb8[i * 3 + c] = out[i * 4 + c];
    write_npy(out_dir + "/rgb_out.npy", rgb8.data(), "|u1", h, w, 1);
    return 0;
}
