// spektra_engine.hpp -- the spectral film-and-print simulation pipeline.
//
// A stage-by-stage C++ port of the spektrafilm runtime
// (upstream/spektrafilm/src/spektrafilm/runtime):
//
//   preprocess   auto exposure (center-weighted)          pipeline._preprocess
//   filming      expose: rgb -> tc -> hanatos tc_lut -> raw, exposure comp,
//                scatter + halation, log10               stages/filming.py
//                develop: curves, DIR couplers, grain     model/develop.py ...
//   printing     expose: enlarger spectral integral (3D LUT), print exposure
//                develop: fitted print curves             stages/printing.py
//   scanning     viewing spectral integral (3D LUT) -> XYZ, glare, -> sRGB,
//                CAM16-UCS gamut compression, unsharp mask, sRGB cctf
//                                                         stages/scanning.py
//
// Parity with the reference is checked by tools/parity (oracle.py runs the
// Python runtime on the same input; host_parity.cpp dumps the engine taps).
//
// Defaults follow spektrafilm's params_schema, except
// `print_exposure_compensation`, which follows SpektraLab's product default
// (false): Camera exposure then moves the finished print, as it does in the
// desktop app, instead of being cancelled by the print normalisation.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cam16.hpp"
#include "engine_data.hpp"

namespace spk {

struct RenderParams {
    std::string film_stock = "kodak_portra_400";
    std::string print_stock = "kodak_portra_endura";
    bool scan_film = false;               // io.scan_film: no print, scan the film itself

    // camera
    float exposure_ev = 0.0f;             // camera.exposure_compensation_ev
    bool auto_exposure = true;            // camera.auto_exposure (center_weighted)
    float film_format_mm = 35.0f;         // camera.film_format_mm (long edge)

    // film rendering
    bool halation_active = true;          // halation.active (scatter + halation)
    float halation_amount = 1.0f;         // halation.halation_amount
    float scatter_amount = 1.0f;          // halation.scatter_amount (0..1 mix)
    bool dir_couplers_active = true;      // dir_couplers.active
    float dir_couplers_amount = 1.0f;     // dir_couplers.amount
    bool grain_active = true;             // grain.active
    bool grain_sublayers = true;          // grain.sublayers_active
    float grain_amount = 1.0f;            // SpektraLab: in + amount * (grained - in)

    // enlarger
    float print_exposure = 1.0f;          // enlarger.print_exposure (linear)
    float y_filter_shift = 0.0f;          // enlarger.y_filter_shift (Kodak CC)
    float m_filter_shift = 0.0f;          // enlarger.m_filter_shift (Kodak CC)
    bool print_exposure_compensation = false;

    // print / scan
    bool glare_active = true;             // print_render.glare.active
    float glare_amount = 1.0f;            // multiplies glare.percent
    bool unsharp_mask = true;             // scanner.unsharp_mask (0.7, 0.7)

    uint64_t seed = 0;                    // grain / glare realisation
};

// Named pipeline taps, mirroring spektrafilm.runtime.topology.Tap.
// Planes are planar float (r, g, b), w*h each.
using TapFn = std::function<void(const char* tap, const float* r, const float* g,
                                 const float* b, int w, int h)>;

class Engine {
public:
    Engine(const EngineData& data, const Cam16Setup& cam16);
    ~Engine();

    // Renders linear-sRGB float RGB (interleaved, w*h*3) into RGBA8 sRGB.
    // `source_id` identifies the pixels: with the same id, size and
    // film-side params the developed negative is reused, so print-side edits
    // (paper, enlarger filters, print exposure, glare) skip filming.
    // `cancel` (optional) aborts between stages when it becomes non-zero.
    bool render(const float* rgb_linear, int w, int h, int64_t source_id,
                const RenderParams& params, uint8_t* rgba_out,
                std::string& error, const std::atomic<int>* cancel = nullptr,
                const TapFn& tap = nullptr);

    // Frees the cached negative (call on memory pressure / new photo).
    void drop_caches();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// sRGB 8-bit -> linear float, shared by the JNI layer and the host tools.
void srgb8_to_linear(const uint8_t* rgba, int w, int h, size_t stride_bytes,
                     float* rgb_out);

// Shared, lazily-initialised process resources.
struct EngineContext {
    EngineData data;
    Cam16Setup cam16;
    bool ready = false;
    std::string error;
    std::mutex mutex;

    bool init(const uint8_t* bin_data, size_t bin_size);
    static EngineContext& instance();
};

}  // namespace spk
