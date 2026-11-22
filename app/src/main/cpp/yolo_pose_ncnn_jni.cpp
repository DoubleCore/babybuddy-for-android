#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "cpu.h"
#include "gpu.h"
#include "mat.h"
#include "net.h"
#include "pipelinecache.h"

#define LOG_TAG "NcnnYoloPoseJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PoseCandidate {
    float x1;
    float y1;
    float x2;
    float y2;
    float score;
    float kpts[51];
};

// Inference mutex: ONLY acquired on the background camera analysis thread!
// Never acquired on the Android Main UI thread so UI touches/dialogs never stall.
std::mutex g_infer_mutex;

// Tiny non-blocking label mutex + atomic Vulkan state for instant Main-Thread UI reads.
std::mutex g_label_mutex;
std::string g_active_hw_label = "ARM NEON FP16 稳帧";
std::atomic<int> g_vulkan_state{-1}; // -1 = unprobed, 0 = unsupported, 1 = supported

bool g_gpu_instance_created = false;
ncnn::PipelineCache* g_pipeline_cache = nullptr;
int g_pipeline_cache_gpu_idx = -1;
std::string g_pipeline_cache_file;

ncnn::Net* g_net = nullptr;
std::string g_loaded_signature;
int g_input_size = 320;

// Pre-allocated reusable input tensor (zero per-frame malloc/free!)
ncnn::Mat g_prepared_in;
int g_prep_nw = 320;
int g_prep_nh = 320;
int g_prep_dw = 0;
int g_prep_dh = 0;
bool g_prep_ready = false;

// Fast L1-cached byte [0..255] -> float [0.0f..1.0f] lookup table
float g_byte_to_norm[256];
std::once_flag g_lut_once;

void init_lut_once() {
    std::call_once(g_lut_once, []() {
        for (int i = 0; i < 256; ++i) {
            g_byte_to_norm[i] = static_cast<float>(i) * (1.0f / 255.0f);
        }
    });
}

void set_hw_label(const std::string& label) {
    std::lock_guard<std::mutex> lk(g_label_mutex);
    g_active_hw_label = label;
}

bool ensure_gpu_ready_locked() {
    if (!g_gpu_instance_created) {
        ncnn::create_gpu_instance();
        g_gpu_instance_created = true;
    }
    int cnt = ncnn::get_gpu_count();
    g_vulkan_state.store(cnt > 0 ? 1 : 0, std::memory_order_relaxed);
    return cnt > 0;
}

void release_net_locked() {
    if (g_net != nullptr) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
    g_loaded_signature.clear();
    g_prep_ready = false;
}

void release_pipeline_cache_locked() {
    if (g_pipeline_cache != nullptr) {
        delete g_pipeline_cache;
        g_pipeline_cache = nullptr;
    }
    g_pipeline_cache_gpu_idx = -1;
}

inline float clamp01(float v) {
    return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
}

float compute_iou(const PoseCandidate& a, const PoseCandidate& b) {
    float ix1 = std::max(a.x1, b.x1);
    float iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2);
    float iy2 = std::min(a.y2, b.y2);
    float iw = std::max(0.f, ix2 - ix1);
    float ih = std::max(0.f, iy2 - iy1);
    float inter = iw * ih;
    if (inter <= 0.f) return 0.f;
    float area_a = (a.x2 - a.x1) * (a.y2 - a.y1);
    float area_b = (b.x2 - b.x1) * (b.y2 - b.y1);
    return inter / (area_a + area_b - inter + 1e-5f);
}

void nms_candidates(std::vector<PoseCandidate>& list, std::vector<PoseCandidate>& kept, float iou_thresh) {
    std::sort(list.begin(), list.end(), [](const PoseCandidate& a, const PoseCandidate& b) {
        return a.score > b.score;
    });
    kept.clear();
    kept.reserve(8);
    for (size_t i = 0; i < list.size(); ++i) {
        bool suppressed = false;
        for (size_t k = 0; k < kept.size(); ++k) {
            if (compute_iou(list[i], kept[k]) > iou_thresh) {
                suppressed = true;
                break;
            }
        }
        if (!suppressed) {
            kept.push_back(list[i]);
            if (kept.size() >= 8) break;
        }
    }
}

bool decode_out0_to_56(
    const ncnn::Mat& out0,
    int sz,
    int nw,
    int nh,
    int dw,
    int dh,
    float boxConfThresh,
    float iouThresh,
    float out_arr[56]
) {
    std::vector<PoseCandidate> raw_candidates;
    raw_candidates.reserve(32);

    // PNNX NCNN YOLO26-pose & YOLO11-pose output layout: dims=2, h=56, w=num_anchors (2100 / 4725 / 8400)
    if (out0.h == 56 && out0.w > 56) {
        const int num_anchors = out0.w;
        const float* cx_row = out0.row(0);
        const float* cy_row = out0.row(1);
        const float* bw_row = out0.row(2);
        const float* bh_row = out0.row(3);
        const float* sc_row = out0.row(4);

        for (int j = 0; j < num_anchors; ++j) {
            float sc = sc_row[j];
            if (sc < boxConfThresh) continue;
            float bw = bw_row[j];
            float bh = bh_row[j];
            if (bw <= 2.f || bh <= 2.f) continue;
            float cx = cx_row[j];
            float cy = cy_row[j];

            PoseCandidate cand;
            cand.x1 = cx - bw * 0.5f;
            cand.y1 = cy - bh * 0.5f;
            cand.x2 = cx + bw * 0.5f;
            cand.y2 = cy + bh * 0.5f;
            cand.score = sc;
            for (int k = 0; k < 51; ++k) {
                cand.kpts[k] = out0.row(5 + k)[j];
            }
            raw_candidates.push_back(cand);
        }
    } else if (out0.w == 57 && out0.h > 0) {
        for (int i = 0; i < out0.h; ++i) {
            const float* r = out0.row(i);
            float sc = r[4];
            int cls = (int)std::round(r[5]);
            if (cls != 0 || sc < boxConfThresh) continue;
            if (r[2] <= r[0] + 2.f || r[3] <= r[1] + 2.f) continue;
            PoseCandidate cand;
            cand.x1 = r[0];
            cand.y1 = r[1];
            cand.x2 = r[2];
            cand.y2 = r[3];
            cand.score = sc;
            for (int k = 0; k < 51; ++k) cand.kpts[k] = r[6 + k];
            raw_candidates.push_back(cand);
        }
    }

    if (raw_candidates.empty()) return false;

    std::vector<PoseCandidate> kept;
    nms_candidates(raw_candidates, kept, iouThresh);
    if (kept.empty()) return false;

    const float half_sz = sz * 0.5f;
    const PoseCandidate* best = &kept[0];
    float best_rank = -1.f;
    for (size_t i = 0; i < kept.size(); ++i) {
        const PoseCandidate& c = kept[i];
        float cx = (c.x1 + c.x2) * 0.5f;
        float cy = (c.y1 + c.y2) * 0.5f;
        float dist = std::hypot((cx - half_sz) / half_sz, (cy - half_sz) / half_sz);
        if (dist > 1.4f) dist = 1.4f;
        float center_w = 1.f - 0.22f * dist;
        float area_frac = ((c.x2 - c.x1) * (c.y2 - c.y1)) / (float)(sz * sz);
        area_frac = std::max(0.01f, std::min(1.f, area_frac));
        float rank = c.score * center_w * (0.75f + 0.25f * std::sqrt(area_frac));
        if (rank > best_rank) {
            best_rank = rank;
            best = &c;
        }
    }

    float inv_nw = 1.f / (float)std::max(1, nw);
    float inv_nh = 1.f / (float)std::max(1, nh);
    out_arr[0] = clamp01((best->x1 - dw) * inv_nw);
    out_arr[1] = clamp01((best->y1 - dh) * inv_nh);
    out_arr[2] = clamp01((best->x2 - dw) * inv_nw);
    out_arr[3] = clamp01((best->y2 - dh) * inv_nh);
    out_arr[4] = clamp01(best->score);

    for (int k = 0; k < 17; ++k) {
        float kx = best->kpts[k * 3 + 0];
        float ky = best->kpts[k * 3 + 1];
        float kc = best->kpts[k * 3 + 2];
        out_arr[5 + k * 3 + 0] = clamp01((kx - dw) * inv_nw);
        out_arr[5 + k * 3 + 1] = clamp01((ky - dh) * inv_nh);
        out_arr[5 + k * 3 + 2] = clamp01(kc);
    }
    return true;
}

void compute_inverse_uv_stepper(
    int rawW,
    int rawH,
    int rot,
    bool isFrontMirror,
    float u0,
    float v0,
    float roiFrac,
    int outW,
    int outH,
    int dy,
    int32_t& fx_init,
    int32_t& fy_init,
    int32_t& fx_step,
    int32_t& fy_step
) {
    float inv_w = 1.0f / (float)std::max(1, outW);
    float inv_h = 1.0f / (float)std::max(1, outH);
    float mv = v0 + ((float)dy + 0.5f) * inv_h * roiFrac;

    float u_first = u0 + 0.5f * inv_w * roiFrac;
    float u_delta = inv_w * roiFrac;
    float mu0 = isFrontMirror ? (1.0f - u_first) : u_first;
    float dmu = isFrontMirror ? (-u_delta) : u_delta;

    float rx0 = 0.f, ry0 = 0.f, drx = 0.f, dry = 0.f;
    switch (rot) {
        case 90:
            rx0 = mv;
            drx = 0.f;
            ry0 = 1.0f - mu0;
            dry = -dmu;
            break;
        case 180:
            rx0 = 1.0f - mu0;
            drx = -dmu;
            ry0 = 1.0f - mv;
            dry = 0.f;
            break;
        case 270:
            rx0 = 1.0f - mv;
            drx = 0.f;
            ry0 = mu0;
            dry = dmu;
            break;
        default:
            rx0 = mu0;
            drx = dmu;
            ry0 = mv;
            dry = 0.f;
            break;
    }

    const float scale_x = (float)rawW * 65536.0f;
    const float scale_y = (float)rawH * 65536.0f;
    fx_init = static_cast<int32_t>(rx0 * scale_x);
    fy_init = static_cast<int32_t>(ry0 * scale_y);
    fx_step = static_cast<int32_t>(drx * scale_x);
    fy_step = static_cast<int32_t>(dry * scale_y);
}

} // namespace

// Fast non-blocking JNI_OnLoad: DO NOT load Vulkan drivers on the UI thread!
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    init_lut_once();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    release_net_locked();
    g_prepared_in.release();
    release_pipeline_cache_locked();
    if (g_gpu_instance_created) {
        ncnn::destroy_gpu_instance();
        g_gpu_instance_created = false;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeLoadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring jParamPath,
    jstring jBinPath,
    jstring jCacheDirPath,
    jint targetSize,
    jboolean preferVulkan,
    jint numThreads
) {
    if (jParamPath == nullptr || jBinPath == nullptr) return JNI_FALSE;
    init_lut_once();

    const char* param_cstr = env->GetStringUTFChars(jParamPath, nullptr);
    const char* bin_cstr = env->GetStringUTFChars(jBinPath, nullptr);
    const char* cache_cstr = jCacheDirPath ? env->GetStringUTFChars(jCacheDirPath, nullptr) : nullptr;
    std::string param_path(param_cstr ? param_cstr : "");
    std::string bin_path(bin_cstr ? bin_cstr : "");
    std::string cache_dir(cache_cstr ? cache_cstr : "");
    if (param_cstr) env->ReleaseStringUTFChars(jParamPath, param_cstr);
    if (bin_cstr) env->ReleaseStringUTFChars(jBinPath, bin_cstr);
    if (cache_cstr) env->ReleaseStringUTFChars(jCacheDirPath, cache_cstr);

    if (param_path.empty() || bin_path.empty()) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_infer_mutex);

    // Critical anti-spin fix: force OpenMP worker threads to sleep immediately (0ms spin)
    // between camera frames instead of burning 100% CPU for 20-200ms!
    ncnn::set_omp_dynamic(0);
    ncnn::set_kmp_blocktime(0);

    int clean_size = (targetSize >= 192 && targetSize <= 1024) ? (int)targetSize : 320;
    bool use_vulkan = false;
    if (preferVulkan == JNI_TRUE) {
        use_vulkan = ensure_gpu_ready_locked();
    }

    int big_cores = ncnn::get_big_cpu_count();
    // Default to 2 FP16 threads so Android Main UI Thread & RenderThread always have dedicated Prime/Big cores!
    int threads = (numThreads > 0) ? (int)numThreads : std::max(2, std::min(2, big_cores > 0 ? big_cores : 2));

    std::string sig = param_path + "|" + bin_path + "|" + std::to_string(clean_size) +
                      "|" + (use_vulkan ? "vk" : "cpu") + "|" + std::to_string(threads);
    if (g_net != nullptr && g_loaded_signature == sig) {
        return JNI_TRUE;
    }

    release_net_locked();

    // Balanced OS core scheduling (do NOT monopolize Prime UI core with powersave=2!)
    ncnn::set_cpu_powersave(0);

    ncnn::Net* net = new ncnn::Net();
    net->opt.lightmode = true;
    net->opt.num_threads = threads;
    net->opt.openmp_blocktime = 0;
    net->opt.use_mapped_model_loading = true;
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = true;
    net->opt.use_packing_layout = true;

    size_t prev_cache_entries = 0;
    if (use_vulkan) {
        int gpu_idx = ncnn::get_default_gpu_index();
        net->opt.use_vulkan_compute = true;
        net->set_vulkan_device(gpu_idx);

        if (g_pipeline_cache == nullptr || g_pipeline_cache_gpu_idx != gpu_idx) {
            release_pipeline_cache_locked();
            const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(gpu_idx);
            if (vkdev != nullptr) {
                g_pipeline_cache = new ncnn::PipelineCache(vkdev);
                g_pipeline_cache_gpu_idx = gpu_idx;
                if (!cache_dir.empty()) {
                    g_pipeline_cache_file = cache_dir + "/vk_pipeline_cache.bin";
                    g_pipeline_cache->load_cache(g_pipeline_cache_file.c_str());
                }
            }
        }
        if (g_pipeline_cache != nullptr) {
            prev_cache_entries = g_pipeline_cache->size();
            net->opt.pipeline_cache = g_pipeline_cache;
        }

        const char* dev_name = ncnn::get_gpu_info(gpu_idx).device_name();
        set_hw_label(std::string("Vulkan [") + (dev_name ? dev_name : "GPU") + "]");
    } else {
        net->opt.use_vulkan_compute = false;
        set_hw_label("NEON FP16 ×" + std::to_string(threads));
    }

    int rp = net->load_param(param_path.c_str());
    int rb = net->load_model(bin_path.c_str());
    if (rp != 0 || rb != 0) {
        LOGE("ncnn load failed: param=%d (%s), bin=%d (%s)", rp, param_path.c_str(), rb, bin_path.c_str());
        net->clear();
        delete net;
        if (use_vulkan) {
            LOGW("Retrying model load on ARM NEON CPU fallback...");
            ncnn::Net* cpu_net = new ncnn::Net();
            cpu_net->opt.lightmode = true;
            cpu_net->opt.num_threads = threads;
            cpu_net->opt.openmp_blocktime = 0;
            cpu_net->opt.use_mapped_model_loading = true;
            cpu_net->opt.use_vulkan_compute = false;
            cpu_net->opt.use_fp16_packed = true;
            cpu_net->opt.use_fp16_storage = true;
            cpu_net->opt.use_fp16_arithmetic = true;
            cpu_net->opt.use_packing_layout = true;
            if (cpu_net->load_param(param_path.c_str()) == 0 && cpu_net->load_model(bin_path.c_str()) == 0) {
                g_net = cpu_net;
                g_input_size = clean_size;
                set_hw_label("NEON FP16 ×" + std::to_string(threads));
                g_loaded_signature = sig;
                return JNI_TRUE;
            }
            cpu_net->clear();
            delete cpu_net;
        }
        return JNI_FALSE;
    }

    if (use_vulkan && g_pipeline_cache != nullptr && !g_pipeline_cache_file.empty()) {
        if (g_pipeline_cache->size() > prev_cache_entries) {
            g_pipeline_cache->save_cache(g_pipeline_cache_file.c_str());
        }
    }

    g_net = net;
    g_input_size = clean_size;
    g_loaded_signature = sig;
    LOGI("Loaded ncnn pose model (%dx%d)", g_input_size, g_input_size);
    return JNI_TRUE;
}

// Lock-free / non-blocking UI getters (safe on Main Thread)
extern "C" JNIEXPORT jstring JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeGetActiveHardwareLabel(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string copy;
    {
        std::lock_guard<std::mutex> lk(g_label_mutex);
        copy = g_active_hw_label;
    }
    return env->NewStringUTF(copy.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeHasVulkanGpu(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    int st = g_vulkan_state.load(std::memory_order_relaxed);
    // If unprobed (-1), optimistically return true on Android 10+ so UI never blocks on Vulkan ICD init;
    // nativeProbeVulkanAsync() updates the exact value in the background.
    return (st != 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeProbeVulkanAsync(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    int st = g_vulkan_state.load(std::memory_order_relaxed);
    if (st >= 0) return st == 1 ? JNI_TRUE : JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    return ensure_gpu_ready_locked() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Ultra-fast (~0.25ms) Zero-Bitmap YUV_420_888 ROI + Rotation + Mirror + Letterbox gather
 * directly from CameraX DirectByteBuffers into reusable [g_prepared_in].
 * Caller can immediately `imageProxy.close()` right after this returns!
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativePrepareYuv420(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject yBuf,
    jint yRowStride,
    jobject uBuf,
    jobject vBuf,
    jint uvRowStride,
    jint uvPixelStride,
    jint srcW,
    jint srcH,
    jint rotationDeg,
    jboolean isFrontMirror,
    jfloat zoomRatio,
    jfloat panX,
    jfloat panY
) {
    if (!yBuf || !uBuf || !vBuf || srcW < 16 || srcH < 16) return JNI_FALSE;
    init_lut_once();

    const uint8_t* y_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    const uint8_t* u_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    const uint8_t* v_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    jlong y_cap = env->GetDirectBufferCapacity(yBuf);
    jlong u_cap = env->GetDirectBufferCapacity(uBuf);
    jlong v_cap = env->GetDirectBufferCapacity(vBuf);

    jlong min_y = static_cast<jlong>(srcH - 1) * yRowStride + (srcW - 1) + 1;
    jlong min_uv = static_cast<jlong>((srcH >> 1) - 1) * uvRowStride + ((srcW >> 1) - 1) * uvPixelStride + 1;
    if (!y_ptr || !u_ptr || !v_ptr || y_cap < min_y || u_cap < min_uv || v_cap < min_uv) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_infer_mutex);
    const int sz = g_input_size;
    if (g_prepared_in.empty() || g_prepared_in.w != sz || g_prepared_in.h != sz || g_prepared_in.c != 3) {
        g_prepared_in.create(sz, sz, 3, static_cast<size_t>(4u), nullptr);
    }

    const int rot = ((rotationDeg % 360) + 360) % 360;
    const int upW = (rot == 90 || rot == 270) ? srcH : srcW;
    const int upH = (rot == 90 || rot == 270) ? srcW : srcH;

    const float z = std::max(1.0f, std::min(8.0f, (float)zoomRatio));
    const float px = std::max(-1.0f, std::min(1.0f, (float)panX));
    const float py = std::max(-1.0f, std::min(1.0f, (float)panY));
    const float roiFrac = 1.0f / z;
    const float u0 = 0.5f * (1.0f - roiFrac) * (1.0f + px);
    const float v0 = 0.5f * (1.0f - roiFrac) * (1.0f + py);

    const float roiUpW = std::max(8.0f, (float)upW * roiFrac);
    const float roiUpH = std::max(8.0f, (float)upH * roiFrac);
    const float scale = std::min((float)sz / roiUpW, (float)sz / roiUpH);
    const int nw = std::max(1, std::min(sz, (int)std::round(roiUpW * scale)));
    const int nh = std::max(1, std::min(sz, (int)std::round(roiUpH * scale)));
    const int dw = (sz - nw) / 2;
    const int dh = (sz - nh) / 2;

    ncnn::Mat chR = g_prepared_in.channel(0);
    ncnn::Mat chG = g_prepared_in.channel(1);
    ncnn::Mat chB = g_prepared_in.channel(2);

    const float pad_val = 114.0f / 255.0f;
    // Fill top & bottom letterbox bars
    for (int y = 0; y < dh; ++y) {
        std::fill_n(chR.row(y), sz, pad_val);
        std::fill_n(chG.row(y), sz, pad_val);
        std::fill_n(chB.row(y), sz, pad_val);
    }
    for (int y = dh + nh; y < sz; ++y) {
        std::fill_n(chR.row(y), sz, pad_val);
        std::fill_n(chG.row(y), sz, pad_val);
        std::fill_n(chB.row(y), sz, pad_val);
    }

    const bool mirror = (isFrontMirror == JNI_TRUE);
    for (int dy = 0; dy < nh; ++dy) {
        float* r_row = chR.row(dh + dy);
        float* g_row = chG.row(dh + dy);
        float* b_row = chB.row(dh + dy);

        for (int x = 0; x < dw; ++x) {
            r_row[x] = pad_val;
            g_row[x] = pad_val;
            b_row[x] = pad_val;
        }
        for (int x = dw + nw; x < sz; ++x) {
            r_row[x] = pad_val;
            g_row[x] = pad_val;
            b_row[x] = pad_val;
        }

        int32_t fx = 0, fy = 0, fx_step = 0, fy_step = 0;
        compute_inverse_uv_stepper(srcW, srcH, rot, mirror, u0, v0, roiFrac, nw, nh, dy, fx, fy, fx_step, fy_step);

        float* r_dst = r_row + dw;
        float* g_dst = g_row + dw;
        float* b_dst = b_row + dw;

        for (int dx = 0; dx < nw; ++dx) {
            int sx = fx >> 16;
            int sy = fy >> 16;
            if ((unsigned)sx >= (unsigned)srcW) sx = sx < 0 ? 0 : (srcW - 1);
            if ((unsigned)sy >= (unsigned)srcH) sy = sy < 0 ? 0 : (srcH - 1);
            fx += fx_step;
            fy += fy_step;

            int y_val = y_ptr[sy * yRowStride + sx];
            int c_off = (sy >> 1) * uvRowStride + (sx >> 1) * uvPixelStride;
            int u_val = static_cast<int>(u_ptr[c_off]) - 128;
            int v_val = static_cast<int>(v_ptr[c_off]) - 128;

            int r_i = y_val + ((359 * v_val + 128) >> 8);
            int g_i = y_val - ((88 * u_val + 183 * v_val + 128) >> 8);
            int b_i = y_val + ((454 * u_val + 128) >> 8);

            r_i = r_i < 0 ? 0 : (r_i > 255 ? 255 : r_i);
            g_i = g_i < 0 ? 0 : (g_i > 255 ? 255 : g_i);
            b_i = b_i < 0 ? 0 : (b_i > 255 ? 255 : b_i);

            r_dst[dx] = g_byte_to_norm[r_i];
            g_dst[dx] = g_byte_to_norm[g_i];
            b_dst[dx] = g_byte_to_norm[b_i];
        }
    }

    g_prep_nw = nw;
    g_prep_nh = nh;
    g_prep_dw = dw;
    g_prep_dh = dh;
    g_prep_ready = true;
    return JNI_TRUE;
}

/**
 * Renders the upright zoomed/rotated YUV_420_888 ROI directly into a pre-allocated reusable RGBA_8888 [dstBitmap]
 * (ONLY invoked when smartwatch is actively polling `/api/snapshot.jpg` or when running ML Kit comparison mode).
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeRenderYuvRoiToBitmap(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject yBuf,
    jint yRowStride,
    jobject uBuf,
    jobject vBuf,
    jint uvRowStride,
    jint uvPixelStride,
    jint srcW,
    jint srcH,
    jint rotationDeg,
    jboolean isFrontMirror,
    jfloat zoomRatio,
    jfloat panX,
    jfloat panY,
    jobject dstBitmap
) {
    if (!yBuf || !uBuf || !vBuf || !dstBitmap || srcW < 16 || srcH < 16) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, dstBitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    const uint8_t* y_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    const uint8_t* u_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    const uint8_t* v_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    jlong y_cap = env->GetDirectBufferCapacity(yBuf);
    jlong u_cap = env->GetDirectBufferCapacity(uBuf);
    jlong v_cap = env->GetDirectBufferCapacity(vBuf);
    jlong min_y = static_cast<jlong>(srcH - 1) * yRowStride + (srcW - 1) + 1;
    jlong min_uv = static_cast<jlong>((srcH >> 1) - 1) * uvRowStride + ((srcW >> 1) - 1) * uvPixelStride + 1;
    if (!y_ptr || !u_ptr || !v_ptr || y_cap < min_y || u_cap < min_uv || v_cap < min_uv) {
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &pixels) < 0 || !pixels) return JNI_FALSE;

    const int outW = static_cast<int>(info.width);
    const int outH = static_cast<int>(info.height);
    const int rot = ((rotationDeg % 360) + 360) % 360;
    const float z = std::max(1.0f, std::min(8.0f, (float)zoomRatio));
    const float px = std::max(-1.0f, std::min(1.0f, (float)panX));
    const float py = std::max(-1.0f, std::min(1.0f, (float)panY));
    const float roiFrac = 1.0f / z;
    const float u0 = 0.5f * (1.0f - roiFrac) * (1.0f + px);
    const float v0 = 0.5f * (1.0f - roiFrac) * (1.0f + py);
    const bool mirror = (isFrontMirror == JNI_TRUE);

    uint8_t* base = static_cast<uint8_t*>(pixels);
    for (int dy = 0; dy < outH; ++dy) {
        uint8_t* row = base + dy * info.stride;
        int32_t fx = 0, fy = 0, fx_step = 0, fy_step = 0;
        compute_inverse_uv_stepper(srcW, srcH, rot, mirror, u0, v0, roiFrac, outW, outH, dy, fx, fy, fx_step, fy_step);
        for (int dx = 0; dx < outW; ++dx) {
            int sx = fx >> 16;
            int sy = fy >> 16;
            if ((unsigned)sx >= (unsigned)srcW) sx = sx < 0 ? 0 : (srcW - 1);
            if ((unsigned)sy >= (unsigned)srcH) sy = sy < 0 ? 0 : (srcH - 1);
            fx += fx_step;
            fy += fy_step;

            int y_val = y_ptr[sy * yRowStride + sx];
            int c_off = (sy >> 1) * uvRowStride + (sx >> 1) * uvPixelStride;
            int u_val = static_cast<int>(u_ptr[c_off]) - 128;
            int v_val = static_cast<int>(v_ptr[c_off]) - 128;

            int r_i = y_val + ((359 * v_val + 128) >> 8);
            int g_i = y_val - ((88 * u_val + 183 * v_val + 128) >> 8);
            int b_i = y_val + ((454 * u_val + 128) >> 8);

            uint8_t* p = row + (dx << 2);
            p[0] = static_cast<uint8_t>(r_i < 0 ? 0 : (r_i > 255 ? 255 : r_i));
            p[1] = static_cast<uint8_t>(g_i < 0 ? 0 : (g_i > 255 ? 255 : g_i));
            p[2] = static_cast<uint8_t>(b_i < 0 ? 0 : (b_i > 255 ? 255 : b_i));
            p[3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, dstBitmap);
    return JNI_TRUE;
}

/**
 * Runs inference on [g_prepared_in] and writes 56 floats directly into caller's reusable [jOut56].
 * Zero Java allocations!
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeRunPrepared(
    JNIEnv* env,
    jobject /*thiz*/,
    jfloatArray jOut56,
    jfloat boxConfThresh,
    jfloat iouThresh
) {
    if (jOut56 == nullptr || env->GetArrayLength(jOut56) < 56) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_infer_mutex);
    if (g_net == nullptr || !g_prep_ready || g_prepared_in.empty()) return JNI_FALSE;

    ncnn::Extractor ex = g_net->create_extractor();
    if (ex.input("in0", g_prepared_in) != 0) {
        ex.input("images", g_prepared_in);
    }

    ncnn::Mat out0;
    if (ex.extract("out0", out0) != 0) {
        if (ex.extract("output0", out0) != 0) {
            return JNI_FALSE;
        }
    }

    float out_arr[56];
    if (!decode_out0_to_56(out0, g_input_size, g_prep_nw, g_prep_nh, g_prep_dw, g_prep_dh, boxConfThresh, iouThresh, out_arr)) {
        return JNI_FALSE;
    }

    env->SetFloatArrayRegion(jOut56, 0, 56, out_arr);
    return JNI_TRUE;
}

/**
 * Bitmap path retained for LAN IP MJPEG camera streams & bitmap-based unit/integration callers.
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeDetectBitmapRoi(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject bitmap,
    jint roiLeft,
    jint roiTop,
    jint roiWidth,
    jint roiHeight,
    jfloat boxConfThresh,
    jfloat iouThresh
) {
    if (bitmap == nullptr) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 8 || info.height < 8) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_infer_mutex);
    if (g_net == nullptr) return nullptr;

    int rx = (roiWidth > 4 && roiHeight > 4) ? std::max(0, std::min((int)info.width - 4, (int)roiLeft)) : 0;
    int ry = (roiWidth > 4 && roiHeight > 4) ? std::max(0, std::min((int)info.height - 4, (int)roiTop)) : 0;
    int rw = (roiWidth > 4 && roiHeight > 4) ? std::min((int)roiWidth, (int)info.width - rx) : (int)info.width;
    int rh = (roiWidth > 4 && roiHeight > 4) ? std::min((int)roiHeight, (int)info.height - ry) : (int)info.height;

    const int sz = g_input_size;
    float scale = std::min((float)sz / (float)rw, (float)sz / (float)rh);
    int nw = std::max(1, std::min(sz, (int)std::round(rw * scale)));
    int nh = std::max(1, std::min(sz, (int)std::round(rh * scale)));
    int dw = (sz - nw) / 2;
    int dh = (sz - nh) / 2;

    ncnn::Mat in = (rx == 0 && ry == 0 && rw == (int)info.width && rh == (int)info.height)
        ? ncnn::Mat::from_android_bitmap_resize(env, bitmap, ncnn::Mat::PIXEL_RGB, nw, nh)
        : ncnn::Mat::from_android_bitmap_roi_resize(env, bitmap, ncnn::Mat::PIXEL_RGB, rx, ry, rw, rh, nw, nh);

    if (in.empty()) return nullptr;

    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, dh, sz - nh - dh, dw, sz - nw - dw, ncnn::BORDER_CONSTANT, 114.f);

    const float norm_vals[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in_pad.substract_mean_normalize(nullptr, norm_vals);

    ncnn::Extractor ex = g_net->create_extractor();
    if (ex.input("in0", in_pad) != 0) {
        ex.input("images", in_pad);
    }

    ncnn::Mat out0;
    if (ex.extract("out0", out0) != 0) {
        if (ex.extract("output0", out0) != 0) {
            return nullptr;
        }
    }

    float out_arr[56];
    if (!decode_out0_to_56(out0, sz, nw, nh, dw, dh, boxConfThresh, iouThresh, out_arr)) {
        return nullptr;
    }

    jfloatArray jres = env->NewFloatArray(56);
    if (jres != nullptr) {
        env->SetFloatArrayRegion(jres, 0, 56, out_arr);
    }
    return jres;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeRelease(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    release_net_locked();
    g_prepared_in.release();
}
