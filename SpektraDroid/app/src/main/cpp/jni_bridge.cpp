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
#include <memory>
#include <unordered_map>
#include "image_io.hpp"
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "android_image_decoder.hpp"
#include "spektra_engine.hpp"

using namespace spk;

#define LOG_TAG "SpektraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    explicit Handle(EngineContext& ctx) : engine(ctx.data, ctx.cam16, &ctx.fonts) {}
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

// A registry, not raw pointers on the Java side: an image can be closed while
// an old render finishes. Its shared reference keeps those float pixels alive.
std::mutex images_mutex;
std::unordered_map<int64_t, std::shared_ptr<android_io::LinearImage>> images;
std::atomic<int64_t> next_image{1};

void image_exception(JNIEnv* env, const std::string& error) {
    env->ThrowNew(env->FindClass("java/io/IOException"), error.c_str());
}

std::shared_ptr<android_io::LinearImage> source_image(JNIEnv* env, int64_t id) {
    std::lock_guard<std::mutex> lock(images_mutex);
    const auto it = images.find(id);
    if (it != images.end()) return it->second;
    image_exception(env, "图片已释放，请重新导入");
    return {};
}

int64_t register_image(std::shared_ptr<android_io::LinearImage> image) {
    const int64_t id = next_image.fetch_add(1);
    std::lock_guard<std::mutex> lock(images_mutex);
    images.emplace(id, std::move(image));
    return id;
}

jobject image_bitmap(JNIEnv* env, const android_io::LinearImage& source) {
    const auto rgba = android_io::display_rgba(source);
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(config_cls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(config_cls, argb);
    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jmethodID create = env->GetStaticMethodID(bitmap_cls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmap_cls, create, source.width, source.height, config);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    AndroidBitmap_getInfo(env, bitmap, &info);
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        image_exception(env, "无法写入图片像素");
        return nullptr;
    }
    for (int y=0;y<source.height;++y)
        std::memcpy(static_cast<uint8_t*>(pixels)+std::size_t(y)*info.stride,
                    rgba.data()+std::size_t(y)*source.width*4, std::size_t(source.width)*4);
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

// Both bitmap and RAW inputs share the same render and expanded-canvas copy.
std::string render_to_bitmap(JNIEnv* env, Handle& handle, const float* rgb, int w, int h,
                            int64_t source_id, jobject bitmap, const RenderParams& params, int64_t token) {
    AndroidBitmapInfo out{};
    if (AndroidBitmap_getInfo(env, bitmap, &out) != ANDROID_BITMAP_RESULT_SUCCESS ||
        out.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return "output must be ARGB_8888";
    int ow=w, oh=h;
    std::string error;
    if (!render_output_size(w, h, params, ow, oh, error)) return error;
    if (int(out.width)!=ow || int(out.height)!=oh) return "bitmap size mismatch";
    // Allocate before locking so exceptions cannot leave a Bitmap locked.
    std::vector<uint8_t> tight(std::size_t(ow)*oh*4);
    CancelFn cancel = [&handle, token] { return handle.wanted.load(std::memory_order_relaxed)!=token; };
    if (!handle.engine.render(rgb,w,h,source_id,params,tight.data(),error,&cancel)) return error;
    void* pixels=nullptr;
    if (AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) return "lock output failed";
    for(int y=0;y<oh;++y)
        std::memcpy(static_cast<uint8_t*>(pixels)+std::size_t(y)*out.stride,
                    tight.data()+std::size_t(y)*ow*4,std::size_t(ow)*4);
    AndroidBitmap_unlockPixels(env,bitmap);
    return {};
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
    auto I = [&](const char* n, int def) -> int {
        jfieldID f = env->GetFieldID(cls, n, "I");
        if (!f) { env->ExceptionClear(); return def; }
        return env->GetIntField(jp, f);
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
    // Film Edge (RFC-032)
    p.overscan.active = Z("overscanActive", false);
    p.overscan.format = S("overscanFormat");
    p.overscan.mode = S("overscanMode");
    p.overscan.gate = S("overscanGate");
    p.overscan.holes = S("overscanHoles");
    p.overscan.camera_seed = I("overscanCameraSeed", 1);
    p.overscan.frame_seed = I("overscanFrameSeed", 1);
    p.overscan.frame_number = I("overscanFrameNumber", 0);
    p.overscan.fog = F("overscanFog", 1.0f);
    p.overscan.leaks = F("overscanLeaks", 0.0f);
    p.overscan.edge_text = S("overscanEdgeText");
    p.overscan.f_number = F("overscanFNumber", 0.0f);
    if (p.overscan.format.empty()) p.overscan.format = "135";
    if (p.overscan.mode.empty()) p.overscan.mode = "strip";
    if (p.overscan.gate.empty()) p.overscan.gate = "auto";
    if (p.overscan.holes.empty()) p.overscan.holes = "white";
    // Date Back (RFC-031)
    p.date.active = Z("dateActive", false);
    p.date.style = S("dateStyle");
    p.date.text = S("dateText");
    p.date.data_text = S("dateDataText");
    p.date.placement = S("datePlacement");
    p.date.corner = S("dateCorner");
    p.date.inset_x = F("dateInsetX", 3.0f);
    p.date.inset_y = F("dateInsetY", 2.4f);
    p.date.size = F("dateSize", 1.0f);
    p.date.exposure_ev = F("dateEv", 3.5f);
    p.date.data_in_frame = Z("dateDataInFrame", true);
    if (p.date.style != "lcd" && p.date.style != "dots" && p.date.style != "data") p.date.style = "lcd";
    if (p.date.placement != "frame" && p.date.placement != "rebate") p.date.placement = "frame";
    if (p.date.corner != "br" && p.date.corner != "bl" && p.date.corner != "tr" && p.date.corner != "tl") p.date.corner = "br";
    if (p.date.text.empty() && p.date.data_text.empty()) p.date.active = false;
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
    // The edge print's faces (Film Edge). Missing ones leave the lettering out.
    auto read = [&](const char* name, std::vector<uint8_t>& out) {
        AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
        if (!a) return;
        out.resize((size_t)AAsset_getLength(a));
        AAsset_read(a, out.data(), out.size());
        AAsset_close(a);
    };
    read("fonts/texgyreheros-regular.otf", ctx.fonts.regular);
    read("fonts/texgyreheros-bold.otf", ctx.fonts.bold);
    read("fonts/texgyreheroscn-regular.otf", ctx.fonts.condensed);
    read("fonts/DejaVuSansMono-Bold.ttf", ctx.fonts.mono_bold);
    LOGI("engine ready: %zu films, %zu papers, fonts %zu/%zu/%zu/%zu bytes", ctx.data.films.size(),
         ctx.data.papers.size(), ctx.fonts.regular.size(), ctx.fonts.bold.size(), ctx.fonts.condensed.size(),
         ctx.fonts.mono_bold.size());
    return nullptr;
}

// The size a render of a w x h source writes: {w, h}, or with Film Edge the
// film canvas. Null when the layout is refused.
JNIEXPORT jintArray JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeOutputSize(JNIEnv* env, jobject, jobject jparams,
                                                                   jint w, jint h) {
    const RenderParams p = params_from(env, jparams);
    int ow = w, oh = h;
    std::string err;
    if (!render_output_size(w, h, p, ow, oh, err)) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "output size: %s", err.c_str());
        return nullptr;
    }
    jintArray out = env->NewIntArray(2);
    const jint v[2] = {ow, oh};
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeImageInfo(JNIEnv* env, jobject, jstring jpath) {
    if (!jpath) return nullptr;
    const char* chars=env->GetStringUTFChars(jpath,nullptr);
    if (!chars) return nullptr;
    const std::string path(chars);
    env->ReleaseStringUTFChars(jpath,chars);
    try {
        spkhost::Probe probe; std::string error;
        if (!spkhost::probe_file(path,probe,error)) { image_exception(env,error); return nullptr; }
        jintArray out=env->NewIntArray(2);
        const jint values[2]={jint(probe.width),jint(probe.height)};
        if (out) env->SetIntArrayRegion(out,0,2,values);
        return out;
    } catch(const std::exception& e) { image_exception(env,e.what()); return nullptr; }
}

JNIEXPORT jlong JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeLoadLinear(JNIEnv* env,jobject,jstring jpath,jint maxEdge) {
    if (!jpath || maxEdge<1 || maxEdge>8192) { image_exception(env,"无效的图片尺寸"); return 0; }
    const char* chars=env->GetStringUTFChars(jpath,nullptr);
    if (!chars) return 0;
    const std::string path(chars);
    env->ReleaseStringUTFChars(jpath,chars);
    try {
        auto image=std::make_shared<android_io::LinearImage>(); std::string error;
        if (!android_io::decode_linear(path,maxEdge,*image,error)) { image_exception(env,error); return 0; }
        LOGI("linear import %dx%d: float32, no Bitmap input",image->width,image->height);
        return register_image(std::move(image));
    } catch(const std::exception& e) { image_exception(env,e.what()); return 0; }
}

JNIEXPORT jlong JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeScaleLinear(JNIEnv* env,jobject,jlong id,jint edge) {
    const auto image=source_image(env,id); if (!image) return 0;
    if (edge<1 || edge>8192) { image_exception(env,"无效的图片尺寸"); return 0; }
    try { return register_image(std::make_shared<android_io::LinearImage>(android_io::scaled_linear(*image,edge))); }
    catch(const std::exception& e) { image_exception(env,e.what()); return 0; }
}

JNIEXPORT jintArray JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeLinearSize(JNIEnv* env,jobject,jlong id) {
    const auto image=source_image(env,id); if (!image) return nullptr;
    jintArray out=env->NewIntArray(2); const jint size[2]={image->width,image->height};
    if(out) env->SetIntArrayRegion(out,0,2,size); return out;
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeLinearBitmap(JNIEnv* env,jobject,jlong id) {
    const auto image=source_image(env,id); if (!image) return nullptr;
    try { return image_bitmap(env,*image); }
    catch(const std::exception& e) { image_exception(env,e.what()); return nullptr; }
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeCloseLinear(JNIEnv*,jobject,jlong id) {
    std::lock_guard<std::mutex> lock(images_mutex); images.erase(id);
}

JNIEXPORT jfloatArray JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeLinearPixel(JNIEnv* env,jobject,jlong id,jint x,jint y) {
    const auto image=source_image(env,id); if(!image)return nullptr;
    if(x<0||y<0||x>=image->width||y>=image->height) { image_exception(env,"像素坐标无效"); return nullptr; }
    jfloatArray out=env->NewFloatArray(3);
    if(out)env->SetFloatArrayRegion(out,0,3,image->rgb.data()+(std::size_t(y)*image->width+x)*3);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_SpektraNative_nativeRenderLinear(
        JNIEnv* env,jobject,jlong h,jlong imageId,jobject bitmap,jobject jparams,jlong token) {
    auto* handle=reinterpret_cast<Handle*>(h);
    if(!handle)return jstr(env,"engine not created");
    const auto image=source_image(env,imageId); if(!image)return nullptr;
    const auto params=params_from(env,jparams);
    std::lock_guard<std::mutex> lock(handle->mutex);
    if(handle->wanted.load()!=token)return jstr(env,"cancelled");
    try {
        // Native IDs occupy a separate key domain from Bitmap source IDs.
        const auto error=render_to_bitmap(env,*handle,image->rgb.data(),image->width,image->height,
                                         INT64_MAX-imageId,bitmap,params,token);
        return error.empty()?nullptr:jstr(env,error);
    } catch(const std::exception& e) { return jstr(env,e.what()); }
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
    if (in.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || out.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return jstr(env, "bitmaps must be ARGB_8888");
    const int w = (int)in.width, hgt = (int)in.height;
    int ow = w, oh = hgt;
    {
        std::string err;
        if (!render_output_size(w, hgt, p, ow, oh, err)) return jstr(env, err);
    }
    if ((int)out.width != ow || (int)out.height != oh) return jstr(env, "bitmap size mismatch");

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

    try {
        const auto err=render_to_bitmap(env,*handle,handle->lin.data(),w,hgt,sourceId,bitmapOut,p,token);
        return err.empty()?nullptr:jstr(env,err);
    } catch(const std::exception& e) { return jstr(env,e.what()); }
}

}  // extern "C"
