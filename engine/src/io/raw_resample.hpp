#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace spk::io::detail {

// Area-average the oriented, linear uint16 output before allocating float RGB.
// This preserves every sensor-derived code and avoids a full-size float copy
// when a mobile caller only needs a preview or a bounded export.
inline void raw_output_to_float(const unsigned char* source, std::uint32_t sw,
                                std::uint32_t sh, std::uint32_t max_edge,
                                std::vector<float>& out, std::uint32_t& dw,
                                std::uint32_t& dh) {
    dw = sw; dh = sh;
    if (max_edge && std::max(sw, sh) > max_edge) {
        const double scale = double(max_edge) / std::max(sw, sh);
        dw = std::max(1u, std::uint32_t(std::lround(sw * scale)));
        dh = std::max(1u, std::uint32_t(std::lround(sh * scale)));
    }
    const auto sample = [source](std::size_t i) {
        std::uint16_t code;
        std::memcpy(&code, source + i * sizeof(code), sizeof(code));
        return float(double(code) / 65535.0);
    };
    if (dw == sw && dh == sh) {
        out.resize(std::size_t(sw) * sh * 3);
        for (std::size_t i = 0; i < out.size(); ++i) out[i] = sample(i);
        return;
    }
    struct Tap { std::uint32_t i; float weight; };
    const auto weights = [](std::uint32_t s, std::uint32_t d) {
        std::vector<std::vector<Tap>> taps(d);
        const double scale = double(s) / d;
        for (std::uint32_t x = 0; x < d; ++x) {
            const double a = x * scale, b = (x + 1) * scale;
            for (std::uint32_t i = std::uint32_t(a); i < std::min<double>(s, std::ceil(b)); ++i) {
                const double coverage = std::min<double>(b, i + 1) - std::max<double>(a, i);
                if (coverage > 0) taps[x].push_back({i, float(coverage / scale)});
            }
        }
        return taps;
    };
    const auto tx = weights(sw, dw), ty = weights(sh, dh);
    std::vector<float> mid(std::size_t(dw) * sh * 3);
    for (std::uint32_t y = 0; y < sh; ++y)
        for (std::uint32_t x = 0; x < dw; ++x)
            for (std::uint32_t c = 0; c < 3; ++c) {
                float sum = 0;
                for (const auto& t : tx[x]) sum += t.weight * sample((std::size_t(y) * sw + t.i) * 3 + c);
                mid[(std::size_t(y) * dw + x) * 3 + c] = sum;
            }
    out.assign(std::size_t(dw) * dh * 3, 0.0f);
    for (std::uint32_t y = 0; y < dh; ++y)
        for (const auto& t : ty[y])
            for (std::size_t i = 0; i < std::size_t(dw) * 3; ++i)
                out[std::size_t(y) * dw * 3 + i] += t.weight * mid[std::size_t(t.i) * dw * 3 + i];
}

}  // namespace spk::io::detail
