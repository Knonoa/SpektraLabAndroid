#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace spk::android_io {

// The import boundary owns full precision pixels, never an Android Bitmap.
// All engine inputs are oriented linear sRGB float, including negative or
// above-white values introduced by the ProPhoto -> sRGB matrix.
struct LinearImage {
    int width = 0, height = 0;
    std::vector<float> rgb;
};

bool decode_linear(const std::string& path, int max_edge, LinearImage& out, std::string& error);
LinearImage scaled_linear(const LinearImage& source, int max_edge);
std::vector<std::uint8_t> display_rgba(const LinearImage& source);

}  // namespace spk::android_io
