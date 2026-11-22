#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#include "cpu.h"
#include "gpu.h"
#include "mat.h"
#include "net.h"

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

std::mutex g_mutex;
bool g_gpu_instance_created = false;
ncnn::Net* g_net = nullptr;
std::string g_loaded_signature;
int g_input_size = 320;
std::string g_active_hw_label = "ncnn";

void ensure_gpu_ready_locked() {
    if (!g_gpu_instance_created) {
        ncnn::create_gpu_instance();
        g_gpu_instance_created = true;
    }
}

void release_net_locked() {
    if (g_net != nullptr) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
    g_loaded_signature.clear();
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

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_gpu_ready_locked();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_net_locked();
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
    jint targetSize,
    jboolean preferVulkan,
    jint numThreads
) {
    if (jParamPath == nullptr || jBinPath == nullptr) return JNI_FALSE;
    const char* param_cstr = env->GetStringUTFChars(jParamPath, nullptr);
    const char* bin_cstr = env->GetStringUTFChars(jBinPath, nullptr);
    std::string param_path(param_cstr ? param_cstr : "");
    std::string bin_path(bin_cstr ? bin_cstr : "");
    if (param_cstr) env->ReleaseStringUTFChars(jParamPath, param_cstr);
    if (bin_cstr) env->ReleaseStringUTFChars(jBinPath, bin_cstr);

    if (param_path.empty() || bin_path.empty()) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_gpu_ready_locked();

    int clean_size = (targetSize >= 192 && targetSize <= 1024) ? (int)targetSize : 320;
    int gpu_count = ncnn::get_gpu_count();
    bool use_vulkan = (preferVulkan == JNI_TRUE) && (gpu_count > 0);
    int big_cores = ncnn::get_big_cpu_count();
    int threads = (numThreads > 0) ? (int)numThreads : std::max(2, std::min(4, big_cores > 0 ? big_cores : 4));

    std::string sig = param_path + "|" + bin_path + "|" + std::to_string(clean_size) +
                      "|" + (use_vulkan ? "vk" : "cpu") + "|" + std::to_string(threads);
    if (g_net != nullptr && g_loaded_signature == sig) {
        return JNI_TRUE;
    }

    release_net_locked();

    // Pin worker threads onto big/prime cores when running on CPU
    ncnn::set_cpu_powersave(use_vulkan ? 0 : 2);

    ncnn::Net* net = new ncnn::Net();
    net->opt.lightmode = true;
    net->opt.num_threads = threads;
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = true;
    net->opt.use_packing_layout = true;

    if (use_vulkan) {
        int gpu_idx = ncnn::get_default_gpu_index();
        net->opt.use_vulkan_compute = true;
        net->set_vulkan_device(gpu_idx);
        const char* dev_name = ncnn::get_gpu_info(gpu_idx).device_name();
        g_active_hw_label = std::string("Vulkan GPU [") + (dev_name ? dev_name : "GPU") + "]";
    } else {
        net->opt.use_vulkan_compute = false;
        g_active_hw_label = "ARM NEON FP16 大核×" + std::to_string(threads);
    }

    int rp = net->load_param(param_path.c_str());
    int rb = net->load_model(bin_path.c_str());
    if (rp != 0 || rb != 0) {
        LOGE("ncnn load failed: param=%d (%s), bin=%d (%s)", rp, param_path.c_str(), rb, bin_path.c_str());
        net->clear();
        delete net;
        // If Vulkan shader compilation failed on quirky OEM driver, retry cleanly on ARM NEON CPU!
        if (use_vulkan) {
            LOGW("Retrying model load on ARM NEON CPU fallback...");
            ncnn::set_cpu_powersave(2);
            ncnn::Net* cpu_net = new ncnn::Net();
            cpu_net->opt.lightmode = true;
            cpu_net->opt.num_threads = threads;
            cpu_net->opt.use_vulkan_compute = false;
            cpu_net->opt.use_fp16_packed = true;
            cpu_net->opt.use_fp16_storage = true;
            cpu_net->opt.use_fp16_arithmetic = true;
            cpu_net->opt.use_packing_layout = true;
            if (cpu_net->load_param(param_path.c_str()) == 0 && cpu_net->load_model(bin_path.c_str()) == 0) {
                g_net = cpu_net;
                g_input_size = clean_size;
                g_active_hw_label = "ARM NEON FP16 大核×" + std::to_string(threads);
                g_loaded_signature = sig;
                return JNI_TRUE;
            }
            cpu_net->clear();
            delete cpu_net;
        }
        return JNI_FALSE;
    }

    g_net = net;
    g_input_size = clean_size;
    g_loaded_signature = sig;
    LOGI("Loaded ncnn pose model (%dx%d) on %s", g_input_size, g_input_size, g_active_hw_label.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeGetActiveHardwareLabel(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_active_hw_label.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_pkgsoftware_babybuddywidgets_posemonitor_NcnnYoloPoseNative_nativeHasVulkanGpu(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_gpu_ready_locked();
    return ncnn::get_gpu_count() > 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Zero-copy ROI crop + resize + letterbox + Vulkan/NEON FP16 inference + NMS decoding.
 * Returns a 56-float array:
 *   [0..3] : normalized box (left, top, right, bottom) within the ROI
 *   [4]    : box confidence score
 *   [5..55]: 17 COCO keypoints * (x, y, conf) normalized within the ROI
 * Or nullptr if no person passes [boxConfThresh].
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

    std::lock_guard<std::mutex> lock(g_mutex);
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

    // Zero-copy ARM NEON ROI crop + bilinear resize directly from Android Bitmap memory!
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
        // E2E [N, 57] fallback layout
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

    if (raw_candidates.empty()) return nullptr;

    std::vector<PoseCandidate> kept;
    nms_candidates(raw_candidates, kept, iouThresh);
    if (kept.empty()) return nullptr;

    // Pick best crib subject (confidence * center proximity * scale)
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

    float inv_nw = 1.f / (float)nw;
    float inv_nh = 1.f / (float)nh;
    float out_arr[56];
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
    std::lock_guard<std::mutex> lock(g_mutex);
    release_net_locked();
}
