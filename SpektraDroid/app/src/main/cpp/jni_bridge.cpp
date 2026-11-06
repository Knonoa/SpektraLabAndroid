// jni_bridge.cpp -- the JNI surface for Kotlin.
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include <cstring>
#include <string>

#include "spektra_engine.hpp"

using namespace spk;

namespace {

// Read the whole engine.bin asset into memory once.
std::vector<uint8_t>& asset_buffer() {
    static std::vector<uint8_t> buf;
    return buf;
}

bool ensure_context(std::string& err) {
    EngineContext& ctx = EngineContext::instance();
    if (ctx.error.empty() && ctx.data.films.empty()) {
        ctx.error = "engine data not loaded";
    }
    if (!ctx.data.films.empty()) return true;
    err = ctx.error.empty() ? "engine data not loaded" : ctx.error;
    return false;
}

RenderParams params_from(JNIEnv* env, jobject jparams, const char*& err) {
    RenderParams p;
    jclass cls = env->GetObjectClass(jparams);

    auto get_str = [&](const char* name) -> std::string {
        jfieldID fid = env->GetFieldID(cls, name, "Ljava/lang/String;");
        if (!fid) return {};
        auto s = (jstring)env->GetObjectField(jparams, fid);
        if (!s) return {};
        const char* chars = env->GetStringUTFChars(s, nullptr);
        std::string out(chars);
        env->ReleaseStringUTFChars(s, chars);
        return out;
    };
    auto get_f = [&](const char* name) -> float {
        jfieldID fid = env->GetFieldID(cls, name, "F");
        return fid ? env->GetFloatField(jparams, fid) : 0.0f;
    };
    auto get_z = [&](const char* name) -> bool {
        jfieldID fid = env->GetFieldID(cls, name, "Z");
        return fid ? env->GetBooleanField(jparams, fid) != JNI_FALSE : false;
    };

    p.film_stock = get_str("filmStock");
    p.print_stock = get_str("printStock");
    p.exposure_ev = get_f("exposureEv");
    p.y_filter_shift = get_f("yFilterShift");
    p.m_filter_shift = get_f("mFilterShift");
    p.c_filter_shift = get_f("cFilterShift");
    p.print_exposure = get_f("printExposure");
    p.film_format_mm = get_f("filmFormatMm");
    p.grain_amount = get_f("grainAmount");
    p.halation_amount = get_f("halationAmount");
    p.scatter_amount = get_f("scatterAmount");
    p.auto_exposure = get_z("autoExposure");

    if (p.print_exposure <= 0.0f) p.print_exposure = 1.0f;
    if (p.film_format_mm <= 0.0f) p.film_format_mm = 35.0f;
    err = nullptr;
    return p;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeLoadEngineData(
        JNIEnv* env, jobject, jobject assetManager) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "engine/engine.bin", AASSET_MODE_BUFFER);
    if (!asset) return JNI_FALSE;
    size_t size = (size_t)AAsset_getLength(asset);
    auto& buf = asset_buffer();
    buf.resize(size);
    AAsset_read(asset, buf.data(), size);
    AAsset_close(asset);

    EngineContext& ctx = EngineContext::instance();
    bool ok = ctx.init(buf.data(), buf.size());
    if (ok && !ctx.cam16_ready) {
        cam16_setup_srgb(ctx.cam16);
        ctx.cam16_ready = true;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeListFilms(JNIEnv* env, jobject) {
    EngineContext& ctx = EngineContext::instance();
    std::string err;
    if (!ensure_context(err)) return nullptr;
    const auto& films = ctx.data.films;
    jobjectArray arr = env->NewObjectArray((jsize)films.size() * 2,
                                           env->FindClass("java/lang/String"), nullptr);
    for (jsize i = 0; i < (jsize)films.size(); i++) {
        env->SetObjectArrayElement(arr, i * 2, env->NewStringUTF(films[i].stock.c_str()));
        env->SetObjectArrayElement(arr, i * 2 + 1, env->NewStringUTF(films[i].name.c_str()));
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeListPapers(JNIEnv* env, jobject) {
    EngineContext& ctx = EngineContext::instance();
    std::string err;
    if (!ensure_context(err)) return nullptr;
    const auto& papers = ctx.data.papers;
    jobjectArray arr = env->NewObjectArray((jsize)papers.size() * 2,
                                           env->FindClass("java/lang/String"), nullptr);
    for (jsize i = 0; i < (jsize)papers.size(); i++) {
        env->SetObjectArrayElement(arr, i * 2, env->NewStringUTF(papers[i].stock.c_str()));
        env->SetObjectArrayElement(arr, i * 2 + 1, env->NewStringUTF(papers[i].name.c_str()));
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeRender(
        JNIEnv* env, jobject, jobject bitmapIn, jobject bitmapOut, jobject jparams) {
    std::string err;
    EngineContext& ctx = EngineContext::instance();
    if (!ensure_context(err)) return JNI_FALSE;

    AndroidBitmapInfo infoIn, infoOut;
    if (AndroidBitmap_getInfo(env, bitmapIn, &infoIn) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, bitmapOut, &infoOut) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    if (infoIn.width != infoOut.width || infoIn.height != infoOut.height) return JNI_FALSE;
    if (infoIn.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        infoOut.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    void* pxIn = nullptr;
    void* pxOut = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmapIn, &pxIn) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmapOut, &pxOut) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, bitmapIn);
        return JNI_FALSE;
    }

    const char* perr = nullptr;
    RenderParams p = params_from(env, jparams, perr);
    Engine engine(ctx.data, ctx.cam16, p);

    // The bitmaps are ARGB_8888 which on Android is RGBA in memory (little
    // endian); row stride may exceed width*4.
    bool ok = false;
    if (infoIn.stride == (uint32_t)(infoIn.width * 4) &&
        infoOut.stride == (uint32_t)(infoOut.width * 4)) {
        ok = engine.render((const uint8_t*)pxIn, infoIn.width, infoIn.height,
                           (uint8_t*)pxOut, err);
    } else {
        // copy into tight buffers
        size_t np = (size_t)infoIn.width * infoIn.height;
        std::vector<uint8_t> tin(np * 4), tout(np * 4);
        for (uint32_t y = 0; y < infoIn.height; y++) {
            std::memcpy(tin.data() + (size_t)y * infoIn.width * 4,
                        (uint8_t*)pxIn + (size_t)y * infoIn.stride, (size_t)infoIn.width * 4);
        }
        ok = engine.render(tin.data(), infoIn.width, infoIn.height, tout.data(), err);
        for (uint32_t y = 0; y < infoOut.height; y++) {
            std::memcpy((uint8_t*)pxOut + (size_t)y * infoOut.stride,
                        tout.data() + (size_t)y * infoOut.width * 4, (size_t)infoOut.width * 4);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmapIn);
    AndroidBitmap_unlockPixels(env, bitmapOut);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
