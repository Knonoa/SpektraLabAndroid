#include "android_image_decoder.hpp"

#include "image_io.hpp"
#include "io/raw_decoder.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace spk::android_io {
namespace {

void fit(int w, int h, int max_edge, int& dw, int& dh) {
    dw = w; dh = h;
    if (max_edge <= 0 || std::max(w, h) <= max_edge) return;
    const double scale = double(max_edge) / std::max(w, h);
    dw = std::max(1, int(std::lround(w * scale)));
    dh = std::max(1, int(std::lround(h * scale)));
}

// inv(sRGB D50 colorants) * ProPhoto D50 colorants. Calculated once from
// the same matrices used by engine/host/image_io.cpp, with no gamut clamp.
std::array<double, 9> prophoto_to_srgb() {
    const auto s = spkhost::srgb_space().to_xyz_d50;
    const auto p = spkhost::linear_prophoto_space().to_xyz_d50;
    const double a=s[0], b=s[1], c=s[2], d=s[3], e=s[4], f=s[5], g=s[6], h=s[7], i=s[8];
    const double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
    const std::array<double, 9> inv={(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det,
        (f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det,(d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det};
    std::array<double, 9> out{};
    for(int r=0;r<3;++r) for(int col=0;col<3;++col) for(int k=0;k<3;++k) out[r*3+col]+=inv[r*3+k]*p[k*3+col];
    return out;
}

}  // namespace

LinearImage scaled_linear(const LinearImage& source, int max_edge) {
    LinearImage out;
    fit(source.width, source.height, max_edge, out.width, out.height);
    if (out.width == source.width && out.height == source.height) out.rgb = source.rgb;
    else spkhost::resize_area(source.rgb.data(), source.width, source.height, 3, out.width, out.height, out.rgb);
    return out;
}

bool decode_linear(const std::string& path, int max_edge, LinearImage& out, std::string& error) {
    spkhost::FloatImage decoded;
    spkhost::Probe probe;
    // Reuse the desktop decoder: AHD, as-shot WB, no automatic brightening,
    // linear 16-bit ProPhoto, plus its ICC-aware TIFF reader. Never use a RAW
    // file's embedded JPEG as the negative's source.
    if (spkhost::sniff_kind(path) == spkhost::FileKind::Raw) {
        spk::io::DecodedRaw raw;
        if (!spk::io::decode_raw_compatible(path, nullptr, std::max(0, max_edge), raw, error)) return false;
        decoded.width = raw.width;
        decoded.height = raw.height;
        decoded.rgb = std::move(raw.rgb);
    } else if (!spkhost::decode_linear_prophoto(path, {}, decoded, probe, error)) return false;
    LinearImage image{int(decoded.width), int(decoded.height), std::move(decoded.rgb)};
    int dw, dh;
    fit(image.width, image.height, max_edge, dw, dh);
    if (dw != image.width || dh != image.height) image = scaled_linear(image, max_edge);
    static const auto matrix = prophoto_to_srgb();
    for (std::size_t n=0; n<image.rgb.size(); n+=3) {
        const double v[3]={image.rgb[n],image.rgb[n+1],image.rgb[n+2]};
        for (int r=0;r<3;++r) {
            const double x=matrix[r*3]*v[0]+matrix[r*3+1]*v[1]+matrix[r*3+2]*v[2];
            if (!std::isfinite(x)) { error="Image contains non-finite colour samples"; return false; }
            image.rgb[n+r]=float(x);
        }
    }
    out=std::move(image);
    return true;
}

std::vector<std::uint8_t> display_rgba(const LinearImage& source) {
    std::vector<std::uint8_t> out(std::size_t(source.width)*source.height*4);
    for(std::size_t p=0;p<out.size()/4;++p) {
        for(int c=0;c<3;++c) {
            double x=std::clamp(double(source.rgb[p*3+c]),0.0,1.0);
            x=x<=0.0031308 ? 12.92*x : 1.055*std::pow(x,1.0/2.4)-0.055;
            out[p*4+c]=std::uint8_t(std::lround(x*255));
        }
        out[p*4+3]=255;
    }
    return out;
}

}  // namespace spk::android_io
