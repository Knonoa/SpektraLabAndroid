// jni_bridge.cpp -- the JNI surface for Kotlin (com.spektralab.android.engine.SpektraNative).
//
// One process-wide EngineContext (baked data + CAM16 tables) and any number
// of engine handles: the app keeps one for the interactive preview (whose
// cached negative makes print-side edits cheap) and one for export.
//
// Cancellation: every render carries a token; nativeSetWanted() publishes the
// token of the newest request, and a running render whose token is no longer
// wanted aborts at its next stage boundary.
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

using namespace spk;

#define LOG_TAG "SpektraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    explicit Handle(EngineContext& ctx) : engine(ctx.data, ctx.cam16) {}
    Engine engine;
    std::mutex mutex;
    std::atomic<int64_t> wanted{0};
    // linear-light copy of the last source bitmap
    int64_t lin_id = -1;
    int lin_w = 0, lin_h = 0;
    std::vector<float> lin;
};

std::string json_escape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if ((unsigned char)c < 0x20) o += ' ';
        else o += c;
    }
    return o;
}

jstring jstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

RenderParams params_from(JNIEnv* env, jobject jp) {
    RenderParams p;
    jclass cls = env->GetObjectClass(jp);
    auto S = [&](const char* n) -> std::string {
        jfieldID f = env->GetFieldID(cls, n, "Ljava/lang/String;");
        if (!f) { env->ExceptionClear(); return {}; }
        auto s = (jstring)env->GetObjectField(jp, f);
        if (!s) return {};
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string out(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
        return out;
    };
    auto F = [&](const char* n, float def) -> float {
        jfieldID f = env->GetFieldID(cls, n, "F");
        if (!f) { env->ExceptionClear(); return def; }
        return env->GetFloatField(jp, f);
    };
    auto Z = [&](const char* n, bool def) -> bool {
        jfieldID f = env->GetFieldID(cls, n, "Z");
        if (!f) { env->ExceptionClear(); return def; }
        return env->GetBooleanField(jp, f) != JNI_FALSE;
    };
    auto J = [&](const char* n, int64_t def) -> int64_t {
        jfieldID f = env->GetFieldID(cls, n, "J");
        if (!f) { env->ExceptionClear(); return def; }
        return env->GetLongField(jp, f);
    };
    p.film_stock = S("filmStock");
    p.print_stock = S("printStock");
    p.scan_film = Z("scanFilm", false);
    p.exposure_ev = F("exposureEv", 0.0f);
    p.auto_exposure = Z("autoExposure", true);
    p.film_format_mm = F("filmFormatMm", 35.0f);
    p.halation_active = Z("halationActive", true);
    p.halation_amount = F("halationAmount", 1.0f);
    p.scatter_amount = F("scatterAmount", 1.0f);
    p.dir_couplers_active = Z("couplersActive", true);
    p.dir_couplers_amount = F("couplersAmount", 1.0f);
    p.grain_active = Z("grainActive", true);
    p.grain_sublayers = Z("grainSublayers", true);
    p.grain_amount = F("grainAmount", 1.0f);
    p.print_exposure = F("printExposure", 1.0f);
    p.y_filter_shift = F("yFilterShift", 0.0f);
    p.m_filter_shift = F("mFilterShift", 0.0f);
    p.print_exposure_compensation = Z("printExposureCompensation", false);
    p.glare_active = Z("glareActive", true);
    p.glare_amount = F("glareAmount", 1.0f);
    p.unsharp_mask = Z("unsharpMask", true);
    p.seed = (uint64_t)J("seed", 0);
    env->DeleteLocalRef(cls);
    if (!(p.print_exposure > 0.0f)) p.print_exposure = 1.0f;
    if (!(p.film_format_mm > 0.0f)) p.film_format_mm = 35.0f;
    return p;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeInit(JNIEnv* env, jobject, jobject assetManager) {
    EngineContext& ctx = EngineContext::instance();
    if (ctx.ready) return nullptr;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "engine/engine.bin", AASSET_MODE_BUFFER);
    if (!asset) return jstr(env, "engine.bin missing from assets");
    const size_t size = (size_t)AAsset_getLength(asset);
    const void* buf = AAsset_getBuffer(asset);
    bool ok;
    if (buf) {
        ok = ctx.init((const uint8_t*)buf, size);
    } else {
        std::vector<uint8_t> tmp(size);
        AAsset_read(asset, tmp.data(), size);
        ok = ctx.init(tmp.data(), size);
    }
    AAsset_close(asset);
    if (!ok) return jstr(env, ctx.error.empty() ? "engine init failed" : ctx.error);
    LOGI("engine ready: %zu films, %zu papers", ctx.data.films.size(), ctx.data.papers.size());
    return nullptr;
}

// {"films":[{"stock","name","positive","bw","cine","target"}...],"papers":[...]}
JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeCatalog(JNIEnv* env, jobject) {
    EngineContext& ctx = EngineContext::instance();
    if (!ctx.ready) return jstr(env, "{}");
    std::ostringstream o;
    o << "{\"films\":[";
    for (size_t i = 0; i < ctx.data.films.size(); i++) {
        const auto& f = ctx.data.films[i];
        o << (i ? "," : "") << "{\"stock\":\"" << json_escape(f.stock) << "\",\"name\":\""
          << json_escape(f.name) << "\",\"positive\":" << (f.positive ? "true" : "false")
          << ",\"bw\":" << (f.bw ? "true" : "false") << ",\"cine\":" << (f.cine ? "true" : "false")
          << ",\"target\":\"" << json_escape(f.target_print) << "\"}";
    }
    o << "],\"papers\":[";
    for (size_t i = 0; i < ctx.data.papers.size(); i++) {
        const auto& p = ctx.data.papers[i];
        o << (i ? "," : "") << "{\"stock\":\"" << json_escape(p.stock) << "\",\"name\":\""
          << json_escape(p.name) << "\",\"bw\":" << (p.bw ? "true" : "false")
          << ",\"cine\":" << (p.cine ? "true" : "false") << "}";
    }
    o << "]}";
    return jstr(env, o.str());
}

JNIEXPORT jlong JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeCreate(JNIEnv*, jobject) {
    EngineContext& ctx = EngineContext::instance();
    if (!ctx.ready) return 0;
    return (jlong) new Handle(ctx);
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeDestroy(JNIEnv*, jobject, jlong h) {
    auto* handle = (Handle*)h;
    if (!handle) return;
    handle->wanted.store(-1);
    { std::lock_guard<std::mutex> lk(handle->mutex); }
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeSetWanted(JNIEnv*, jobject, jlong h, jlong token) {
    auto* handle = (Handle*)h;
    if (handle) handle->wanted.store(token);
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeDropCaches(JNIEnv*, jobject, jlong h) {
    auto* handle = (Handle*)h;
    if (!handle) return;
    std::lock_guard<std::mutex> lk(handle->mutex);
    handle->engine.drop_caches();
    handle->lin_id = -1;
    std::vector<float>().swap(handle->lin);
}

// Returns null on success, "cancelled", or an error message.
JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeRender(
        JNIEnv* env, jobject, jlong h, jobject bitmapIn, jlong sourceId, jobject bitmapOut,
        jobject jparams, jlong token) {
    auto* handle = (Handle*)h;
    if (!handle) return jstr(env, "engine not created");
    const RenderParams p = params_from(env, jparams);
    std::lock_guard<std::mutex> lk(handle->mutex);
    if (handle->wanted.load() != token) return jstr(env, "cancelled");

    AndroidBitmapInfo in{}, out{};
    if (AndroidBitmap_getInfo(env, bitmapIn, &in) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, bitmapOut, &out) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "bitmap info failed");
    if (in.width != out.width || in.height != out.height) return jstr(env, "bitmap size mismatch");
    if (in.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || out.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return jstr(env, "bitmaps must be ARGB_8888");
    const int w = (int)in.width, hgt = (int)in.height;

    try {
        if (handle->lin_id != sourceId || handle->lin_w != w || handle->lin_h != hgt || sourceId < 0) {
            void* px = nullptr;
            if (AndroidBitmap_lockPixels(env, bitmapIn, &px) != ANDROID_BITMAP_RESULT_SUCCESS)
                return jstr(env, "lock input failed");
            handle->lin.resize((size_t)w * hgt * 3);
            srgb8_to_linear((const uint8_t*)px, w, hgt, in.stride, handle->lin.data());
            AndroidBitmap_unlockPixels(env, bitmapIn);
            handle->lin_id = sourceId;
            handle->lin_w = w;
            handle->lin_h = hgt;
        }
    } catch (const std::bad_alloc&) {
        handle->lin_id = -1;
        return jstr(env, "out of memory");
    }

    std::vector<uint8_t> tight;
    void* po = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmapOut, &po) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "lock output failed");
    uint8_t* dst = (uint8_t*)po;
    const bool strided = out.stride != (uint32_t)w * 4;
    if (strided) {
        tight.resize((size_t)w * hgt * 4);
        dst = tight.data();
    }
    Handle* hd = handle;
    const int64_t tok = token;
    CancelFn cancel = [hd, tok] { return hd->wanted.load(std::memory_order_relaxed) != tok; };
    std::string err;
    const bool ok = handle->engine.render(handle->lin.data(), w, hgt, sourceId, p, dst, err, &cancel);
    if (ok && strided) {
        for (int y = 0; y < hgt; y++)
            std::memcpy((uint8_t*)po + (size_t)y * out.stride, tight.data() + (size_t)y * w * 4, (size_t)w * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmapOut);
    if (!ok) return jstr(env, err);
    return nullptr;
}

}  // extern "C"
