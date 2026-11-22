// Baby Pose 3D/2D Biomechanical Monitor, 1x-8x ROI Zoom, Threshold Tuning & Smartwatch Companion
// Powered by Google MediaPipe BlazePose (Heavy / Full / Lite 33-point 3D PoseLandmarker)
// with 360° rotation-invariant chirality/roll math, One-Euro adaptive filtering, and zero-point calibration.

(() => {
  const CHANNEL_NAME = 'babybuddy_pose_watch_channel_v1';
  const STATE_KEY = 'babybuddy_pose_watch_state_v1';
  const CFG_KEY = 'babybuddy_pose_watch_cfg_v2';
  const bc = (typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined') ? new BroadcastChannel(CHANNEL_NAME) : null;

  const POSE_LABELS = {
    none: '未检测到婴儿姿态',
    supine_calm: '仰卧平放',
    side_left: '左侧卧/翻身',
    side_right: '右侧卧/翻身',
    prone_head_up: '趴卧/俯睡',
    limb_active: '屈臂挥动/蹬腿',
    sitting_awake: '屈髋撑起/坐立',
  };

  const SHORT_POSE_LABELS = {
    none: '未检出骨架',
    supine_calm: '仰卧平放',
    side_left: '左侧卧',
    side_right: '右侧卧',
    prone_head_up: '趴卧/俯睡',
    limb_active: '屈臂/蹬腿',
    sitting_awake: '撑起/坐立',
  };

  const MODEL_SPECS = {
    yolo26n: {
      code: 'yolo26n',
      isYolo: true,
      shortTitle: 'YOLO26n(内置推荐)',
      displayName: 'Ultralytics YOLO26n-pose (ONNX E2E+RLE · 内置)',
      candidateUrls: [
        './models/yolo26n-pose.onnx',
        'https://hf-mirror.com/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11n-pose.onnx',
      ],
    },
    yolo11s: {
      code: 'yolo11s',
      isYolo: true,
      shortTitle: 'YOLO11s(云端高精)',
      displayName: 'Ultralytics YOLO11s-pose (ONNX · 云端38MB)',
      candidateUrls: [
        'https://hf-mirror.com/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11s-pose.onnx',
        'https://huggingface.co/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11s-pose.onnx',
      ],
    },
    heavy: {
      code: 'heavy',
      isYolo: false,
      shortTitle: 'BlazePose Heavy',
      displayName: 'MediaPipe BlazePose Heavy (33点3D对照)',
      modelUrl: 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task',
    },
    full: {
      code: 'full',
      isYolo: false,
      shortTitle: 'BlazePose Full',
      displayName: 'MediaPipe BlazePose Full (33点3D对照)',
      modelUrl: 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task',
    },
  };

  const PRESET_CONFIGS = {
    sensitive: {
      minConfidence: 0.35,
      angleThresholdDeg: 15,
      rollThresholdDeg: 22,
      smoothingStrength: 0.40,
      confirmFrames: 1,
      threshold: 3,
      windowSec: 30,
      cooldownMs: 1200,
    },
    balanced: {
      minConfidence: 0.45,
      angleThresholdDeg: 22,
      rollThresholdDeg: 28,
      smoothingStrength: 0.55,
      confirmFrames: 2,
      threshold: 4,
      windowSec: 30,
      cooldownMs: 1500,
    },
    strict: {
      minConfidence: 0.60,
      angleThresholdDeg: 32,
      rollThresholdDeg: 35,
      smoothingStrength: 0.70,
      confirmFrames: 3,
      threshold: 6,
      windowSec: 30,
      cooldownMs: 2000,
    },
  };

  const PRESET_ANGLES = {
    supine_calm: { lElbow: 136, rElbow: 138, lShoulder: 42, rShoulder: 40, lKnee: 142, rKnee: 145, lHip: 162, rHip: 164, roll: 0, chirality: 0.95 },
    side_left: { lElbow: 105, rElbow: 72, lShoulder: 55, rShoulder: 68, lKnee: 118, rKnee: 96, lHip: 132, rHip: 124, roll: -52, chirality: 0.45 },
    limb_active: { lElbow: 54, rElbow: 162, lShoulder: 142, rShoulder: 98, lKnee: 72, rKnee: 168, lHip: 102, rHip: 165, roll: -10, chirality: 0.88 },
    side_right: { lElbow: 74, rElbow: 108, lShoulder: 66, rShoulder: 52, lKnee: 98, rKnee: 120, lHip: 125, rHip: 134, roll: 52, chirality: 0.45 },
    prone_head_up: { lElbow: 66, rElbow: 68, lShoulder: 86, rShoulder: 84, lKnee: 154, rKnee: 150, lHip: 158, rHip: 156, roll: 8, chirality: -0.85 },
    sitting_awake: { lElbow: 156, rElbow: 158, lShoulder: 36, rShoulder: 38, lKnee: 108, rKnee: 110, lHip: 86, rHip: 88, roll: 0, chirality: 0.92 },
  };

  function loadSavedConfig() {
    try {
      if (typeof localStorage !== 'undefined') {
        const raw = localStorage.getItem(CFG_KEY);
        if (raw) return JSON.parse(raw);
      }
    } catch (_) {}
    return {};
  }

  const savedCfg = loadSavedConfig();

  let lab = {
    monitoring: false,
    zoomRatio: Number(savedCfg.zoomRatio) || 1.0,
    panX: 0,
    panY: 0,
    modelTier: MODEL_SPECS[savedCfg.modelTier] ? savedCfg.modelTier : 'yolo26n',
    minConfidence: typeof savedCfg.minConfidence === 'number' ? savedCfg.minConfidence : 0.45,
    angleThresholdDeg: typeof savedCfg.angleThresholdDeg === 'number' ? savedCfg.angleThresholdDeg : 22,
    rollThresholdDeg: typeof savedCfg.rollThresholdDeg === 'number' ? savedCfg.rollThresholdDeg : 28,
    smoothingStrength: typeof savedCfg.smoothingStrength === 'number' ? savedCfg.smoothingStrength : 0.55,
    confirmFrames: typeof savedCfg.confirmFrames === 'number' ? savedCfg.confirmFrames : 2,
    threshold: typeof savedCfg.threshold === 'number' ? savedCfg.threshold : 4,
    windowSec: typeof savedCfg.windowSec === 'number' ? savedCfg.windowSec : 30,
    cooldownMs: typeof savedCfg.cooldownMs === 'number' ? savedCfg.cooldownMs : 1500,
    enableContourFallback: Boolean(savedCfg.enableContourFallback ?? false),
    baselineRollOffsetDeg: typeof savedCfg.baselineRollOffsetDeg === 'number' ? savedCfg.baselineRollOffsetDeg : 0,
    showHud: savedCfg.showHud !== undefined ? Boolean(savedCfg.showHud) : true,

    poseCode: 'none',
    poseLabel: POSE_LABELS.none,
    confidence: 0,
    landmarkCount: 0,
    inferenceMs: 0,
    activeModelStatus: '等待初始化...',
    angles: null,
    lastRawUnadjustedRoll: null,
    lastAngleDeltaDeg: 0,
    changeTimestamps: [],
    recentHistory: [],
    alarmRinging: false,
    snoozeUntilMs: 0,
    facingMode: 'environment',
  };

  function persistConfig() {
    try {
      if (typeof localStorage === 'undefined') return;
      localStorage.setItem(CFG_KEY, JSON.stringify({
        zoomRatio: lab.zoomRatio,
        modelTier: lab.modelTier,
        minConfidence: lab.minConfidence,
        angleThresholdDeg: lab.angleThresholdDeg,
        rollThresholdDeg: lab.rollThresholdDeg,
        smoothingStrength: lab.smoothingStrength,
        confirmFrames: lab.confirmFrames,
        threshold: lab.threshold,
        windowSec: lab.windowSec,
        cooldownMs: lab.cooldownMs,
        enableContourFallback: lab.enableContourFallback,
        baselineRollOffsetDeg: lab.baselineRollOffsetDeg,
        showHud: lab.showHud,
      }));
    } catch (_) {}
  }

  let mediaStream = null;
  let videoEl = null;
  let loopTimer = null;
  let isProcessingFrame = false;
  let prevSmoothedKp = null;
  let prevFrameAngles = null;
  let anchorPose = 'none';
  let anchorAngles = null;
  let candidatePose = 'none';
  let candidateStreak = 0;
  let missingStreak = 0;
  let lastChangeAt = 0;
  let audioCtx = null;
  let alarmLoopTimer = null;

  // ONNX Runtime Web (YOLO Pose) & MediaPipe runtime state
  let ortWebModule = null;
  let yoloOrtSession = null;
  let loadedYoloTier = null;
  let yoloLoadingPromise = null;
  let yoloUnavailableReason = null;

  let mpVisionModule = null;
  let mpFilesetResolver = null;
  let poseLandmarker = null;
  let loadedModelTier = null;
  let mpLoadingPromise = null;
  let mpUnavailableReason = null;

  function loadScriptOnce(src) {
    return new Promise((resolve, reject) => {
      if (typeof document === 'undefined') return reject(new Error('no-dom'));
      if (window.ort) return resolve(window.ort);
      const s = document.createElement('script');
      s.src = src;
      s.async = true;
      s.onload = () => (window.ort ? resolve(window.ort) : reject(new Error('ort-missing')));
      s.onerror = () => reject(new Error('script-load-failed'));
      document.head.appendChild(s);
    });
  }

  async function ensureYoloOrtSession(tier) {
    const spec = MODEL_SPECS[tier] || MODEL_SPECS.yolo26n;
    if (yoloOrtSession && loadedYoloTier === spec.code) return yoloOrtSession;
    if (yoloLoadingPromise) return yoloLoadingPromise;

    yoloLoadingPromise = (async () => {
      try {
        lab.activeModelStatus = `正在加载 ${spec.shortTitle} ONNX 引擎...`;
        updateConsoleDom();

        if (!ortWebModule) {
          try {
            ortWebModule = await loadScriptOnce('https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/ort.min.js');
            ortWebModule.env.wasm.wasmPaths = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/';
          } catch (_) {
            ortWebModule = await loadScriptOnce('https://unpkg.com/onnxruntime-web@1.20.1/dist/ort.min.js');
            ortWebModule.env.wasm.wasmPaths = 'https://unpkg.com/onnxruntime-web@1.20.1/dist/';
          }
        }

        let session = null;
        let usedSource = '本地内置';
        for (const mUrl of (spec.candidateUrls || [])) {
          try {
            session = await ortWebModule.InferenceSession.create(mUrl, {
              executionProviders: ['wasm'],
              graphOptimizationLevel: 'all',
            });
            usedSource = mUrl.startsWith('./') ? '本地内置' : '云端镜像';
            break;
          } catch (_) {}
        }
        if (!session) throw new Error('ONNX权重未能加载');

        yoloOrtSession = session;
        loadedYoloTier = spec.code;
        yoloUnavailableReason = null;
        lab.activeModelStatus = `${spec.displayName} · WASM(${usedSource})`;
        return session;
      } catch (err) {
        yoloUnavailableReason = err && err.message ? err.message : 'ONNX不可用';
        lab.activeModelStatus = 'YOLO ONNX 加载受限，正在自动回退 BlazePose...';
        return null;
      } finally {
        yoloLoadingPromise = null;
        updateConsoleDom();
      }
    })();
    return yoloLoadingPromise;
  }

  function decodeYoloPoseTensor(data, dims, inputSize, padX, padY, scaledW, scaledH, boxConfThresh = 0.25) {
    if (!dims || dims.length !== 3) return null;
    const d1 = dims[1], d2 = dims[2];
    const candidates = [];

    const iou = (a, b) => {
      const ix1 = Math.max(a.x1, b.x1), iy1 = Math.max(a.y1, b.y1);
      const ix2 = Math.min(a.x2, b.x2), iy2 = Math.min(a.y2, b.y2);
      const inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
      if (inter <= 0) return 0;
      return inter / ((a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter + 1e-5);
    };

    if (d2 === 57) {
      // YOLO26-pose End-to-End NMS-Free [1, 300, 57]
      for (let i = 0; i < d1; i++) {
        const b = i * 57;
        const score = data[b + 4];
        const cls = Math.round(data[b + 5]);
        if (cls !== 0 || score < boxConfThresh) continue;
        const x1 = data[b], y1 = data[b + 1], x2 = data[b + 2], y2 = data[b + 3];
        if (x2 <= x1 + 2 || y2 <= y1 + 2) continue;
        candidates.push({ x1, y1, x2, y2, score, kpts: data.slice(b + 6, b + 57) });
      }
    } else if (d1 === 56 && d2 > 56) {
      // YOLO11-pose / YOLOv8-pose [1, 56, 8400]
      const raw = [];
      const sOff = 4 * d2;
      for (let j = 0; j < d2; j++) {
        const score = data[sOff + j];
        if (score < boxConfThresh) continue;
        const cx = data[j], cy = data[d2 + j], bw = data[2 * d2 + j], bh = data[3 * d2 + j];
        if (bw <= 2 || bh <= 2) continue;
        const kp = new Float32Array(51);
        for (let k = 0; k < 51; k++) kp[k] = data[(5 + k) * d2 + j];
        raw.push({ x1: cx - bw * 0.5, y1: cy - bh * 0.5, x2: cx + bw * 0.5, y2: cy + bh * 0.5, score, kpts: kp });
      }
      raw.sort((a, b) => b.score - a.score);
      for (const c of raw) {
        if (!candidates.some(k => iou(c, k) > 0.45)) {
          candidates.push(c);
          if (candidates.length >= 10) break;
        }
      }
    }

    if (!candidates.length) return null;
    const half = inputSize * 0.5;
    let best = candidates[0], bestRank = -1;
    for (const c of candidates) {
      const dist = Math.hypot(((c.x1 + c.x2) * 0.5 - half) / half, ((c.y1 + c.y2) * 0.5 - half) / half);
      const rank = c.score * (1 - 0.22 * Math.min(1.4, dist));
      if (rank > bestRank) { bestRank = rank; best = c; }
    }

    const sw = Math.max(1, scaledW), sh = Math.max(1, scaledH);
    const ux = px => Math.max(0, Math.min(1, (px - padX) / sw));
    const uy = py => Math.max(0, Math.min(1, (py - padY) / sh));
    const pt = idx => ({
      x: ux(best.kpts[idx * 3]),
      y: uy(best.kpts[idx * 3 + 1]),
      z: 0,
      c: Math.max(0, Math.min(1, best.kpts[idx * 3 + 2])),
    });

    return {
      boxScore: best.score,
      box: { left: ux(best.x1), top: uy(best.y1), right: ux(best.x2), bottom: uy(best.y2) },
      keypoints: {
        nose: pt(0), leftEye: pt(1), rightEye: pt(2), leftEar: pt(3), rightEar: pt(4),
        leftShoulder: pt(5), rightShoulder: pt(6), leftElbow: pt(7), rightElbow: pt(8),
        leftWrist: pt(9), rightWrist: pt(10), leftHip: pt(11), rightHip: pt(12),
        leftKnee: pt(13), rightKnee: pt(14), leftAnkle: pt(15), rightAnkle: pt(16),
      },
    };
  }

  async function ensurePoseLandmarker(tier) {
    const spec = MODEL_SPECS[tier] || MODEL_SPECS.full;
    if (poseLandmarker && loadedModelTier === spec.code) {
      return poseLandmarker;
    }
    if (mpLoadingPromise) {
      return mpLoadingPromise;
    }
    mpLoadingPromise = (async () => {
      try {
        lab.activeModelStatus = `正在加载 ${spec.shortTitle} 模型...`;
        updateConsoleDom();

        if (!mpVisionModule) {
          try {
            mpVisionModule = await import('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/+esm');
          } catch (_) {
            mpVisionModule = await import('https://unpkg.com/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs');
          }
        }
        if (!mpFilesetResolver) {
          mpFilesetResolver = await mpVisionModule.FilesetResolver.forVisionTasks(
            'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm'
          );
        }
        if (poseLandmarker) {
          try { poseLandmarker.close(); } catch (_) {}
          poseLandmarker = null;
        }

        let instance = null;
        let delegateLabel = 'GPU';
        try {
          instance = await mpVisionModule.PoseLandmarker.createFromOptions(mpFilesetResolver, {
            baseOptions: { modelAssetPath: spec.modelUrl, delegate: 'GPU' },
            runningMode: 'IMAGE',
            numPoses: 1,
            minPoseDetectionConfidence: 0.35,
            minPosePresenceConfidence: 0.35,
            minTrackingConfidence: 0.35,
          });
        } catch (_) {
          delegateLabel = 'CPU';
          instance = await mpVisionModule.PoseLandmarker.createFromOptions(mpFilesetResolver, {
            baseOptions: { modelAssetPath: spec.modelUrl, delegate: 'CPU' },
            runningMode: 'IMAGE',
            numPoses: 1,
            minPoseDetectionConfidence: 0.35,
            minPosePresenceConfidence: 0.35,
            minTrackingConfidence: 0.35,
          });
        }

        poseLandmarker = instance;
        loadedModelTier = spec.code;
        mpUnavailableReason = null;
        lab.activeModelStatus = `${spec.displayName} · ${delegateLabel}`;
        return instance;
      } catch (err) {
        mpUnavailableReason = err && err.message ? err.message : '离线/WASM不可用';
        lab.activeModelStatus = lab.enableContourFallback
          ? '几何轮廓离线兜底 (MediaPipe未就绪)'
          : 'MediaPipe 模型加载失败 (请联网或开启离线轮廓兜底)';
        return null;
      } finally {
        mpLoadingPromise = null;
        updateConsoleDom();
      }
    })();
    return mpLoadingPromise;
  }

  // ==================== Pure 3D/2D Biomechanical Math Engine ====================

  function jointAngleDeg(a, vertex, c, minConf = 0.35, depthScale = 0.35) {
    if (!a || !vertex || !c) return null;
    if ((a.c ?? 1) < minConf || (vertex.c ?? 1) < minConf || (c.c ?? 1) < minConf) return null;
    const v1x = a.x - vertex.x;
    const v1y = a.y - vertex.y;
    const v1z = ((a.z || 0) - (vertex.z || 0)) * depthScale;
    const v2x = c.x - vertex.x;
    const v2y = c.y - vertex.y;
    const v2z = ((c.z || 0) - (vertex.z || 0)) * depthScale;
    const len1 = Math.hypot(v1x, v1y, v1z);
    const len2 = Math.hypot(v2x, v2y, v2z);
    if (len1 < 1e-4 || len2 < 1e-4) return null;
    const cosT = Math.max(-1, Math.min(1, (v1x * v2x + v1y * v2y + v1z * v2z) / (len1 * len2)));
    return (Math.acos(cosT) * 180) / Math.PI;
  }

  function smoothKeypointsAdaptive(prev, curr, smoothingStrength = 0.55) {
    if (!prev) return curr;
    const baseAlpha = Math.max(0.18, Math.min(1.0, 1 - smoothingStrength * 0.75));
    const out = {};
    for (const key of Object.keys(curr)) {
      const np = curr[key];
      const op = prev[key];
      if (!np) { out[key] = null; continue; }
      if (!op || (op.c ?? 0) < 0.2) { out[key] = { ...np }; continue; }
      const speed = Math.hypot(np.x - op.x, np.y - op.y);
      const boost = Math.max(0, Math.min(1, (speed - 0.006) / 0.038));
      const rawA = baseAlpha * 0.55 + boost * (0.94 - baseAlpha * 0.55);
      const a = Math.max(0.15, Math.min(0.96, rawA * (0.65 + 0.35 * (np.c ?? 1))));
      out[key] = {
        x: op.x * (1 - a) + np.x * a,
        y: op.y * (1 - a) + np.y * a,
        z: (op.z || 0) * (1 - a) + (np.z || 0) * a,
        c: np.c ?? 1,
      };
    }
    return out;
  }

  function headReferencePoint(kp, minConf) {
    const pts = [kp.nose, kp.leftEye, kp.rightEye, kp.leftEar, kp.rightEar].filter(p => p && (p.c ?? 1) >= minConf);
    if (!pts.length) return null;
    let sw = 0, sx = 0, sy = 0, sz = 0, mc = 0;
    for (const p of pts) {
      const w = p.c ?? 1;
      sw += w; sx += p.x * w; sy += p.y * w; sz += (p.z || 0) * w;
      if (w > mc) mc = w;
    }
    return { x: sx / sw, y: sy / sw, z: sz / sw, c: mc };
  }

  function computeAnglesFromKeypoints(kp, minConf = 0.45, baselineRollOffsetDeg = 0) {
    const lElbow = jointAngleDeg(kp.leftShoulder, kp.leftElbow, kp.leftWrist, minConf);
    const rElbow = jointAngleDeg(kp.rightShoulder, kp.rightElbow, kp.rightWrist, minConf);

    const ls = kp.leftShoulder && kp.leftShoulder.c >= minConf ? kp.leftShoulder : null;
    const rs = kp.rightShoulder && kp.rightShoulder.c >= minConf ? kp.rightShoulder : null;
    const lh = kp.leftHip && kp.leftHip.c >= minConf ? kp.leftHip : null;
    const rh = kp.rightHip && kp.rightHip.c >= minConf ? kp.rightHip : null;
    const headRef = headReferencePoint(kp, minConf);

    let sMidX = 0, sMidY = 0, hasShoulders = false;
    if (ls && rs) {
      sMidX = (ls.x + rs.x) * 0.5;
      sMidY = (ls.y + rs.y) * 0.5;
      hasShoulders = true;
    }

    let tvx = 0, tvy = 0, tLen = 0, hasHips = false;
    if (hasShoulders && (lh || rh)) {
      const hmx = lh && rh ? (lh.x + rh.x) * 0.5 : (lh || rh).x;
      const hmy = lh && rh ? (lh.y + rh.y) * 0.5 : (lh || rh).y;
      tvx = hmx - sMidX;
      tvy = hmy - sMidY;
      tLen = Math.hypot(tvx, tvy);
      hasHips = tLen >= 0.035;
    }
    if (!hasHips && hasShoulders && headRef) {
      const hx = sMidX - headRef.x;
      const hy = sMidY - headRef.y;
      const hLen = Math.hypot(hx, hy);
      if (hLen >= 0.025) {
        tvx = hx * 2.0;
        tvy = hy * 2.0;
        tLen = hLen * 2.0;
      }
    }

    const vLh = lh || (ls && tLen >= 0.05 ? { x: ls.x + tvx, y: ls.y + tvy, z: ls.z || 0, c: ls.c } : null);
    const vRh = rh || (rs && tLen >= 0.05 ? { x: rs.x + tvx, y: rs.y + tvy, z: rs.z || 0, c: rs.c } : null);

    const lArmEnd = (kp.leftElbow && kp.leftElbow.c >= minConf) ? kp.leftElbow : kp.leftWrist;
    const rArmEnd = (kp.rightElbow && kp.rightElbow.c >= minConf) ? kp.rightElbow : kp.rightWrist;
    const lShoulder = jointAngleDeg(vLh, kp.leftShoulder, lArmEnd, minConf);
    const rShoulder = jointAngleDeg(vRh, kp.rightShoulder, rArmEnd, minConf);

    const lKnee = jointAngleDeg(kp.leftHip, kp.leftKnee, kp.leftAnkle, minConf);
    const rKnee = jointAngleDeg(kp.rightHip, kp.rightKnee, kp.rightAnkle, minConf);

    const lLegEnd = (kp.leftKnee && kp.leftKnee.c >= minConf) ? kp.leftKnee : kp.leftAnkle;
    const rLegEnd = (kp.rightKnee && kp.rightKnee.c >= minConf) ? kp.rightKnee : kp.rightAnkle;
    const lHip = jointAngleDeg(kp.leftShoulder, kp.leftHip, lLegEnd, minConf);
    const rHip = jointAngleDeg(kp.rightShoulder, kp.rightHip, rLegEnd, minConf);

    let torsoTilt = null, roll = null, rawUnadjustedRoll = null, chirality = null;

    if (ls && rs && tLen >= 0.04) {
      const tx = tvx / tLen;
      const ty = tvy / tLen;
      torsoTilt = (Math.atan2(Math.abs(tvx), Math.abs(tvy)) * 180) / Math.PI;

      const sx = ls.x - rs.x;
      const sy = ls.y - rs.y;
      const shoulderSpan2D = Math.hypot(sx, sy);

      if (shoulderSpan2D >= 0.015) {
        chirality = Math.max(-1, Math.min(1, (sx * ty - sy * tx) / shoulderSpan2D));
      }

      const expectedSpan = Math.max(0.06, tLen * 0.68);
      const foreshorten = Math.max(0, Math.min(1, 1 - Math.min(1, shoulderSpan2D / expectedSpan)));

      const cSign = (chirality ?? 1) >= 0 ? 1 : -1;
      const uAnatLeftX = ty * cSign;
      const uAnatLeftY = -tx * cSign;

      const nose = kp.nose && kp.nose.c >= minConf ? kp.nose : null;
      const noseLatRatio = nose
        ? Math.max(-1.25, Math.min(1.25, ((nose.x - sMidX) * uAnatLeftX + (nose.y - sMidY) * uAnatLeftY) / Math.max(0.045, shoulderSpan2D)))
        : 0;

      const dz = (ls.z || 0) - (rs.z || 0);
      const has3D = Math.abs(ls.z || 0) > 1e-4 || Math.abs(rs.z || 0) > 1e-4;
      const depthRollDeg = has3D
        ? Math.max(-75, Math.min(75, (Math.atan2(dz, Math.max(0.05, shoulderSpan2D)) * 180) / Math.PI))
        : 0;

      const lEarC = kp.leftEar ? kp.leftEar.c : 0;
      const rEarC = kp.rightEar ? kp.rightEar.c : 0;
      const earLeftBias = Math.max(lEarC, rEarC) >= minConf ? Math.max(-1, Math.min(1, rEarC - lEarC)) : 0;

      const dirComposite = noseLatRatio * 0.55 + (depthRollDeg / 55) * 0.65 + earLeftBias * 0.40;
      const rollSign = Math.abs(dirComposite) >= 0.08
        ? (dirComposite > 0 ? -1 : 1)
        : (noseLatRatio > 0 ? -1 : 1);

      const geomMag = Math.min(85, foreshorten * 62 + Math.abs(noseLatRatio) * 30 + Math.abs(earLeftBias) * 16);
      const fusedMag = has3D ? Math.min(88, geomMag * 0.58 + Math.abs(depthRollDeg) * 0.42) : geomMag;

      rawUnadjustedRoll = (foreshorten < 0.18 && Math.abs(noseLatRatio) < 0.16 && Math.abs(depthRollDeg) < 12)
        ? Math.max(-20, Math.min(20, -noseLatRatio * 24 - depthRollDeg * 0.35))
        : rollSign * fusedMag;

      roll = Math.max(-90, Math.min(90, rawUnadjustedRoll - baselineRollOffsetDeg));
    }

    return { lElbow, rElbow, lShoulder, rShoulder, lKnee, rKnee, lHip, rHip, torsoTilt, roll, rawUnadjustedRoll, chirality };
  }

  function computeAngleDelta(prev, curr) {
    if (!prev || !curr) return { maxDelta: 0, meanDelta: 0, dominant: '', count: 0 };
    const items = [
      ['左肘弯曲角', prev.lElbow, curr.lElbow, 1.25],
      ['右肘弯曲角', prev.rElbow, curr.rElbow, 1.25],
      ['左肩抬臂角', prev.lShoulder, curr.lShoulder, 1.15],
      ['右肩抬臂角', prev.rShoulder, curr.rShoulder, 1.15],
      ['左膝屈伸角', prev.lKnee, curr.lKnee, 1.15],
      ['右膝屈伸角', prev.rKnee, curr.rKnee, 1.15],
      ['左髋折角', prev.lHip, curr.lHip, 1.0],
      ['右髋折角', prev.rHip, curr.rHip, 1.0],
      ['躯干侧翻角', prev.roll, curr.roll, 1.2],
    ];
    let maxDelta = 0, wSum = 0, wTot = 0, dominant = '', count = 0;
    for (const [name, p, c, w] of items) {
      if (p == null || c == null || Number.isNaN(p) || Number.isNaN(c)) continue;
      const d = Math.abs(c - p);
      wSum += d * w;
      wTot += w;
      count++;
      if (d > maxDelta) { maxDelta = d; dominant = name; }
    }
    return { maxDelta, meanDelta: wTot > 0 ? wSum / wTot : 0, dominant, count };
  }

  function classifyPoseFromAngles(angles, kp, delta, cfg) {
    const minConf = cfg.minConfidence ?? 0.45;
    const rollThresh = cfg.rollThresholdDeg ?? 28;
    const limbJumpThresh = Math.max(14, (cfg.angleThresholdDeg ?? 22) * 1.15);

    const { lElbow, rElbow, lShoulder, rShoulder, lKnee, rKnee, lHip, rHip, chirality } = angles;
    const roll = angles.roll ?? 0;

    // 1. Prone / Stomach sleeping (360° rotation-invariant chirality + forearm prop)
    const backFacing = chirality != null && chirality <= -0.24;
    const noseC = kp && kp.nose ? kp.nose.c : 0;
    const eyeC = kp ? Math.max(kp.leftEye?.c || 0, kp.rightEye?.c || 0) : 0;
    const faceOccluded = kp != null && noseC < minConf * 0.75 && eyeC < minConf * 0.75;
    const bothElbowsPropped = lElbow != null && rElbow != null &&
      lElbow >= 42 && lElbow <= 96 && rElbow >= 42 && rElbow <= 96 &&
      (lShoulder ?? 80) >= 62 && (lShoulder ?? 80) <= 112 &&
      (rShoulder ?? 80) >= 62 && (rShoulder ?? 80) <= 112 &&
      Math.abs(lElbow - rElbow) < 18 &&
      (chirality == null || chirality < 0.25 || faceOccluded);

    if (backFacing || bothElbowsPropped) return 'prone_head_up';

    // 2. Sitting / Pushing up
    const hips = [lHip, rHip].filter(v => v != null);
    const avgHip = hips.length ? hips.reduce((a, b) => a + b, 0) / hips.length : null;
    const armsSupporting = (lElbow ?? 150) > 132 && (rElbow ?? 150) > 132 &&
      (lShoulder ?? 40) < 62 && (rShoulder ?? 40) < 62;
    const headAbove = (kp && kp.nose && kp.leftShoulder && kp.rightShoulder)
      ? kp.nose.y < Math.min(kp.leftShoulder.y, kp.rightShoulder.y) - 0.04
      : true;
    if (avgHip != null && avgHip >= 55 && avgHip <= 108 && armsSupporting && headAbove && Math.abs(roll) < rollThresh) {
      return 'sitting_awake';
    }

    // 3. Limb Active
    const armHighAndBent = (lShoulder != null && lShoulder >= 115 && (lElbow ?? 180) <= 95) ||
      (rShoulder != null && rShoulder >= 115 && (rElbow ?? 180) <= 95);
    const strongElbowAsym = lElbow != null && rElbow != null && Math.abs(lElbow - rElbow) >= 52 &&
      ((lShoulder ?? 0) >= 85 || (rShoulder ?? 0) >= 85);
    const legKick = lKnee != null && rKnee != null && Math.abs(lKnee - rKnee) >= 42;
    const largeRotation = delta && delta.maxDelta >= limbJumpThresh && delta.meanDelta >= Math.max(11, limbJumpThresh * 0.48);

    if (armHighAndBent || strongElbowAsym || legKick || largeRotation) return 'limb_active';

    // 4. Side Roll
    if (roll <= -rollThresh) return 'side_left';
    if (roll >= rollThresh) return 'side_right';

    return 'supine_calm';
  }

  function mapMediaPipeLandmarks(lm) {
    if (!lm || lm.length < 29) return null;
    const pt = (idx) => {
      const p = lm[idx];
      if (!p) return null;
      return {
        x: Math.max(0, Math.min(1, p.x)),
        y: Math.max(0, Math.min(1, p.y)),
        z: p.z || 0,
        c: typeof p.visibility === 'number' ? p.visibility : 0.9,
      };
    };
    return {
      nose: pt(0),
      leftEye: pt(2),
      rightEye: pt(5),
      leftEar: pt(7),
      rightEar: pt(8),
      leftShoulder: pt(11),
      rightShoulder: pt(12),
      leftElbow: pt(13),
      rightElbow: pt(14),
      leftWrist: pt(15),
      rightWrist: pt(16),
      leftHip: pt(23),
      rightHip: pt(24),
      leftKnee: pt(25),
      rightKnee: pt(26),
      leftAnkle: pt(27),
      rightAnkle: pt(28),
    };
  }

  function formatAnglesSummary(a) {
    if (!a) return '肘弯 --/-- · 抬臂 --/-- · 膝弯 --/-- · 侧翻 --';
    const f = v => (v == null || Number.isNaN(v) ? '--' : `${Math.round(v)}°`);
    return `肘弯 L${f(a.lElbow)}/R${f(a.rElbow)} · 抬臂 L${f(a.lShoulder)}/R${f(a.rShoulder)} · 膝弯 L${f(a.lKnee)}/R${f(a.rKnee)} · 侧翻 ${f(a.roll)}`;
  }

  // Optional fallback contour analyzer (only used when explicitly enabled)
  function analyzeContourAngles(ctxSmall, gw, gh) {
    const img = ctxSmall.getImageData(0, 0, gw, gh).data;
    const n = gw * gh;
    const luma = new Float32Array(n);
    const warm = new Float32Array(n);
    let mean = 0;

    for (let i = 0; i < n; i++) {
      const r = img[i * 4] / 255;
      const g = img[i * 4 + 1] / 255;
      const b = img[i * 4 + 2] / 255;
      const y = 0.299 * r + 0.587 * g + 0.114 * b;
      luma[i] = y;
      mean += y;
      warm[i] = (r > g && g > b * 0.85 && (r - b) > 0.08) ? Math.min(1, (r - b) * 1.4) : 0;
    }
    mean /= n;

    let salSum = 0, sx = 0, sy = 0;
    let minX = gw, minY = gh, maxX = 0, maxY = 0;
    for (let y = 1; y < gh - 1; y++) {
      for (let x = 1; x < gw - 1; x++) {
        const idx = y * gw + x;
        const edge = (Math.abs(luma[idx] - luma[idx - 1]) + Math.abs(luma[idx] - luma[idx - gw])) * 0.5;
        const contrast = Math.abs(luma[idx] - mean);
        const sal = (contrast < 0.035 && edge < 0.025) ? 0 : (warm[idx] * 1.35 + edge * 0.9 + contrast * 0.4);
        if (sal > 0.24) {
          salSum += sal;
          sx += x * sal;
          sy += y * sal;
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }

    const areaRatio = maxX > minX && maxY > minY ? ((maxX - minX) * (maxY - minY)) / n : 0;
    if (salSum < n * 0.012 || maxX <= minX + 4 || maxY <= minY + 4 || areaRatio > 0.92) {
      return { detected: false, pose: 'none', angles: null, box: null, confidence: 0, landmarkCount: 0, keypoints: null };
    }

    const mx = sx / salSum, my = sy / salSum;
    let leftW = 0.001, rightW = 0.001;
    let luSpan = 0, ruSpan = 0, llSpan = 0, rlSpan = 0;
    for (let y = minY; y <= maxY; y++) {
      for (let x = minX; x <= maxX; x++) {
        const idx = y * gw + x;
        const w = warm[idx] * 1.4 + Math.abs(luma[idx] - mean) * 0.4;
        if (w < 0.12) continue;
        const dx = x - mx;
        if (x < mx) leftW += w; else rightW += w;
        if (y <= my) {
          if (x < mx) luSpan = Math.max(luSpan, Math.abs(dx));
          else ruSpan = Math.max(ruSpan, Math.abs(dx));
        } else {
          if (x < mx) llSpan = Math.max(llSpan, Math.abs(dx));
          else rlSpan = Math.max(rlSpan, Math.abs(dx));
        }
      }
    }

    const halfW = Math.max(4, (maxX - minX) * 0.5);
    const lrAsym = (rightW - leftW) / (leftW + rightW);
    const rawRoll = Math.max(-80, Math.min(80, lrAsym * 75));
    const roll = Math.max(-80, Math.min(80, rawRoll - lab.baselineRollOffsetDeg));
    const lArmR = Math.min(1.4, Math.max(0.2, luSpan / halfW));
    const rArmR = Math.min(1.4, Math.max(0.2, ruSpan / halfW));
    const lLegR = Math.min(1.4, Math.max(0.2, llSpan / halfW));
    const rLegR = Math.min(1.4, Math.max(0.2, rlSpan / halfW));

    const angles = {
      lShoulder: Math.round(25 + lArmR * 75),
      rShoulder: Math.round(25 + rArmR * 75),
      lElbow: Math.round(165 - Math.abs(lArmR - 0.65) * 95),
      rElbow: Math.round(165 - Math.abs(rArmR - 0.65) * 95),
      lKnee: Math.round(165 - lLegR * 55),
      rKnee: Math.round(165 - rLegR * 55),
      lHip: 150,
      rHip: 150,
      roll: Math.round(roll),
      rawUnadjustedRoll: rawRoll,
      chirality: null,
    };

    const delta = computeAngleDelta(prevFrameAngles, angles);
    prevFrameAngles = angles;
    const pose = classifyPoseFromAngles(angles, null, delta, lab);
    const box = {
      left: Math.max(0.04, minX / gw),
      top: Math.max(0.04, minY / gh),
      right: Math.min(0.96, maxX / gw),
      bottom: Math.min(0.96, maxY / gh),
    };

    return { detected: true, pose, angles, box, confidence: 0.48, landmarkCount: 0, keypoints: null };
  }

  function ensureAudio() {
    if (!audioCtx && typeof window !== 'undefined') {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (Ctx) audioCtx = new Ctx();
    }
    if (audioCtx && audioCtx.state === 'suspended') {
      audioCtx.resume().catch(() => {});
    }
  }

  function startAlarmSound() {
    stopAlarmSound();
    ensureAudio();
    if (typeof window !== 'undefined' && window.HarmonyBridge && window.HarmonyBridge.startAlarmVibration) {
      try { window.HarmonyBridge.startAlarmVibration(); } catch (_) {}
    }
    const ringOnce = () => {
      if (!lab.alarmRinging) return;
      try {
        if (typeof navigator !== 'undefined' && navigator.vibrate) navigator.vibrate([450, 200, 450]);
        if (audioCtx) {
          const now = audioCtx.currentTime;
          [880, 1174].forEach((freq, idx) => {
            const osc = audioCtx.createOscillator();
            const gain = audioCtx.createGain();
            osc.type = 'triangle';
            osc.frequency.setValueAtTime(freq, now + idx * 0.22);
            gain.gain.setValueAtTime(0.28, now + idx * 0.22);
            gain.gain.exponentialRampToValueAtTime(0.001, now + idx * 0.22 + 0.2);
            osc.connect(gain);
            gain.connect(audioCtx.destination);
            osc.start(now + idx * 0.22);
            osc.stop(now + idx * 0.22 + 0.21);
          });
        }
      } catch (_) {}
    };
    ringOnce();
    alarmLoopTimer = setInterval(ringOnce, 950);
  }

  function stopAlarmSound() {
    if (alarmLoopTimer) {
      clearInterval(alarmLoopTimer);
      alarmLoopTimer = null;
    }
    if (typeof window !== 'undefined' && window.HarmonyBridge && window.HarmonyBridge.stopAlarmVibration) {
      try { window.HarmonyBridge.stopAlarmVibration(); } catch (_) {}
    }
    try {
      if (typeof navigator !== 'undefined' && navigator.vibrate) navigator.vibrate(0);
    } catch (_) {}
  }

  function broadcastSnapshot(includeFrameDataUrl = null) {
    const now = Date.now();
    const cutoff = now - lab.windowSec * 1000;
    lab.changeTimestamps = lab.changeTimestamps.filter(t => t >= cutoff);
    const payload = {
      monitoring: lab.monitoring,
      targetDetected: lab.poseCode !== 'none',
      poseCode: lab.poseCode,
      poseLabel: lab.poseLabel,
      modelName: lab.activeModelStatus,
      confidence: lab.confidence,
      inferenceTimeMs: lab.inferenceMs,
      landmarkCount: lab.landmarkCount,
      anglesSummary: formatAnglesSummary(lab.angles),
      lastAngleDeltaDeg: lab.lastAngleDeltaDeg,
      angleThresholdDeg: lab.angleThresholdDeg,
      changeCount: lab.changeTimestamps.length,
      threshold: lab.threshold,
      windowSec: lab.windowSec,
      zoomRatio: lab.zoomRatio,
      alarmRinging: lab.alarmRinging,
      snoozedRemainingSec: lab.snoozeUntilMs > now ? Math.ceil((lab.snoozeUntilMs - now) / 1000) : 0,
      recentHistory: lab.recentHistory.slice(0, 5),
      frameDataUrl: includeFrameDataUrl,
      ts: now,
    };
    try {
      if (typeof localStorage !== 'undefined') localStorage.setItem(STATE_KEY, JSON.stringify(payload));
      if (bc) bc.postMessage({ type: 'status', payload });
    } catch (_) {}
    return payload;
  }

  function dismissAlarm() {
    lab.alarmRinging = false;
    lab.changeTimestamps = [];
    lab.snoozeUntilMs = Math.max(lab.snoozeUntilMs, Date.now() + 4000);
    stopAlarmSound();
    broadcastSnapshot();
    updateConsoleDom();
  }

  function snoozeAlarm() {
    lab.alarmRinging = false;
    lab.changeTimestamps = [];
    lab.snoozeUntilMs = Date.now() + 180000;
    stopAlarmSound();
    broadcastSnapshot();
    updateConsoleDom();
  }

  function triggerWakeNow(reasonLabel) {
    lab.alarmRinging = true;
    if (reasonLabel) {
      lab.recentHistory.unshift(reasonLabel);
      lab.recentHistory = lab.recentHistory.slice(0, 6);
    }
    startAlarmSound();
    broadcastSnapshot();
    updateConsoleDom();
  }

  function computeCrop(sw, sh, zoom, panX, panY) {
    const z = Math.min(8.0, Math.max(1.0, Number(zoom) || 1.0));
    const cw = Math.max(16, Math.round(sw / z));
    const ch = Math.max(16, Math.round(sh / z));
    const maxOx = (sw - cw) * 0.5;
    const maxOy = (sh - ch) * 0.5;
    const cx = sw * 0.5 + Math.max(-1, Math.min(1, panX)) * maxOx;
    const cy = sh * 0.5 + Math.max(-1, Math.min(1, panY)) * maxOy;
    const left = Math.max(0, Math.min(sw - cw, Math.round(cx - cw * 0.5)));
    const top = Math.max(0, Math.min(sh - ch, Math.round(cy - ch * 0.5)));
    return { left, top, width: cw, height: ch };
  }

  function recordAngleFrame(res, bypassDebounce = false) {
    const now = Date.now();
    const cutoff = now - lab.windowSec * 1000;
    lab.changeTimestamps = lab.changeTimestamps.filter(t => t >= cutoff);

    if (!res.detected || !res.angles) {
      missingStreak++;
      if (missingStreak >= 3) {
        lab.poseCode = 'none';
        lab.poseLabel = POSE_LABELS.none;
        lab.confidence = 0;
        lab.landmarkCount = 0;
        candidatePose = 'none';
        candidateStreak = 0;
      }
      return;
    }

    missingStreak = 0;
    lab.confidence = res.confidence ?? 0.85;
    lab.landmarkCount = res.landmarkCount ?? 0;
    lab.angles = res.angles;
    if (typeof res.angles.rawUnadjustedRoll === 'number') {
      lab.lastRawUnadjustedRoll = res.angles.rawUnadjustedRoll;
    }

    const reqConfirm = bypassDebounce ? 1 : Math.max(1, Math.min(5, lab.confirmFrames));
    if (res.pose === candidatePose) {
      candidateStreak++;
    } else {
      candidatePose = res.pose;
      candidateStreak = 1;
    }

    const effectivePose = (lab.poseCode === 'none' || candidateStreak >= reqConfirm) ? res.pose : lab.poseCode;
    lab.poseCode = effectivePose;
    lab.poseLabel = POSE_LABELS[effectivePose] || POSE_LABELS.supine_calm;

    if (anchorPose === 'none' || !anchorAngles) {
      anchorPose = effectivePose;
      anchorAngles = { ...res.angles };
      lab.lastAngleDeltaDeg = 0;
      return;
    }

    const delta = computeAngleDelta(anchorAngles, res.angles);
    lab.lastAngleDeltaDeg = delta.maxDelta;

    const minDeg = lab.angleThresholdDeg;
    const hasLargeAngleChange = delta.count >= 1 && (delta.maxDelta >= minDeg || delta.meanDelta >= minDeg * 0.58);
    const hasVerifiedPoseSwitch = effectivePose !== anchorPose && delta.maxDelta >= Math.max(10, minDeg * 0.65);

    if (candidateStreak >= reqConfirm && (now - lastChangeAt >= lab.cooldownMs) && (hasLargeAngleChange || hasVerifiedPoseSwitch)) {
      lab.changeTimestamps.push(now);
      lastChangeAt = now;
      const tag = `${delta.dominant || '关节'} Δ${Math.round(delta.maxDelta)}°`;
      const msg = effectivePose !== anchorPose
        ? `${POSE_LABELS[anchorPose]}→${POSE_LABELS[effectivePose]} (${tag})`
        : `${POSE_LABELS[effectivePose]} (${tag})`;
      lab.recentHistory.unshift(msg);
      lab.recentHistory = lab.recentHistory.slice(0, 6);
      anchorPose = effectivePose;
      anchorAngles = { ...res.angles };
    }

    if (now >= lab.snoozeUntilMs && !lab.alarmRinging && lab.changeTimestamps.length >= lab.threshold) {
      triggerWakeNow(`关节角度变化达 ${lab.threshold} 次，判定苏醒`);
    }
  }

  function drawPoseOverlay(ctx, w, h, res) {
    if (!res || !res.detected) return;
    const minConf = lab.minConfidence;
    const color = lab.alarmRinging
      ? '#FB7185'
      : (res.pose === 'prone_head_up' ? '#F97316' : ((res.pose === 'limb_active' || res.pose === 'sitting_awake') ? '#FBBF24' : '#34D399'));

    if (res.box) {
      const bx = res.box.left * w;
      const by = res.box.top * h;
      const bw = (res.box.right - res.box.left) * w;
      const bh = (res.box.bottom - res.box.top) * h;
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.strokeRect(bx, by, bw, bh);
    }

    const kp = res.keypoints;
    if (!kp) return;

    const bone = (a, b, stroke = 'rgba(255,255,255,0.88)', lw = 2.2) => {
      if (!a || !b || a.c < minConf || b.c < minConf) return;
      ctx.strokeStyle = stroke;
      ctx.lineWidth = lw;
      ctx.beginPath();
      ctx.moveTo(a.x * w, a.y * h);
      ctx.lineTo(b.x * w, b.y * h);
      ctx.stroke();
    };

    bone(kp.leftEar, kp.leftEye, 'rgba(147,197,253,0.78)', 1.5);
    bone(kp.leftEye, kp.nose, 'rgba(147,197,253,0.78)', 1.5);
    bone(kp.nose, kp.rightEye, 'rgba(147,197,253,0.78)', 1.5);
    bone(kp.rightEye, kp.rightEar, 'rgba(147,197,253,0.78)', 1.5);

    bone(kp.leftShoulder, kp.rightShoulder);
    bone(kp.leftShoulder, kp.leftElbow);
    bone(kp.leftElbow, kp.leftWrist);
    bone(kp.rightShoulder, kp.rightElbow);
    bone(kp.rightElbow, kp.rightWrist);
    bone(kp.leftShoulder, kp.leftHip);
    bone(kp.rightShoulder, kp.rightHip);
    bone(kp.leftHip, kp.rightHip);
    bone(kp.leftHip, kp.leftKnee);
    bone(kp.leftKnee, kp.leftAnkle);
    bone(kp.rightHip, kp.rightKnee);
    bone(kp.rightKnee, kp.rightAnkle);

    for (const pt of Object.values(kp)) {
      if (!pt || pt.c < minConf) continue;
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(pt.x * w, pt.y * h, 3.5, 0, Math.PI * 2);
      ctx.fill();
    }

    if (lab.showHud && res.angles) {
      ctx.font = 'bold 11px monospace';
      const drawTag = (pt, deg) => {
        if (!pt || pt.c < minConf || deg == null) return;
        const txt = `${Math.round(deg)}°`;
        const px = Math.min(w - 34, Math.max(4, pt.x * w + 6));
        const py = Math.min(h - 6, Math.max(14, pt.y * h - 5));
        ctx.fillStyle = 'rgba(13,17,23,0.75)';
        ctx.fillRect(px - 3, py - 11, txt.length * 7 + 6, 14);
        ctx.fillStyle = '#E2E8F0';
        ctx.fillText(txt, px, py);
      };
      drawTag(kp.leftElbow, res.angles.lElbow);
      drawTag(kp.rightElbow, res.angles.rElbow);
      drawTag(kp.leftKnee, res.angles.lKnee);
      drawTag(kp.rightKnee, res.angles.rKnee);
    }
  }

  async function startCamera() {
    stopCamera();
    const canvas = document.getElementById('poseLabCanvas');
    const hint = document.getElementById('poseLabHint');
    if (!canvas) return;
    anchorPose = 'none';
    anchorAngles = null;
    prevSmoothedKp = null;
    prevFrameAngles = null;

    if (typeof window !== 'undefined' && window.HarmonyBridge && window.HarmonyBridge.requestCameraPermission) {
      try { window.HarmonyBridge.requestCameraPermission(); } catch (_) {}
    }

    // Preload selected pose engine (YOLO ONNX or MediaPipe BlazePose)
    const curSpec = MODEL_SPECS[lab.modelTier] || MODEL_SPECS.yolo26n;
    if (curSpec.isYolo) ensureYoloOrtSession(lab.modelTier);
    else ensurePoseLandmarker(lab.modelTier);

    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('当前环境不支持直接调用摄像头，可使用下方模拟动作与阈值面板测试');
      }
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: lab.facingMode, width: { ideal: 1920 }, height: { ideal: 1080 } },
        audio: false,
      });
      videoEl = document.createElement('video');
      videoEl.srcObject = mediaStream;
      videoEl.playsInline = true;
      videoEl.muted = true;
      await videoEl.play();
      lab.monitoring = true;
      if (hint) hint.classList.add('hidden');

      const roiCanvas = document.createElement('canvas');
      roiCanvas.width = 640;
      roiCanvas.height = 480;
      const roiCtx = roiCanvas.getContext('2d', { willReadFrequently: true });

      const yoloCanvas = document.createElement('canvas');
      yoloCanvas.width = 640;
      yoloCanvas.height = 640;
      const yoloCtx = yoloCanvas.getContext('2d', { willReadFrequently: true });
      const yoloFloatBuf = new Float32Array(3 * 640 * 640);

      const smallCanvas = document.createElement('canvas');
      smallCanvas.width = 96;
      smallCanvas.height = 96;
      const smallCtx = smallCanvas.getContext('2d', { willReadFrequently: true });

      loopTimer = setInterval(async () => {
        if (isProcessingFrame || !videoEl || videoEl.readyState < 2) return;
        const c = document.getElementById('poseLabCanvas');
        if (!c) { stopCamera(); return; }
        isProcessingFrame = true;

        try {
          const ctx = c.getContext('2d');
          const vw = videoEl.videoWidth || 640;
          const vh = videoEl.videoHeight || 480;
          const crop = computeCrop(vw, vh, lab.zoomRatio, lab.panX, lab.panY);

          // Resize ROI canvas to match crop aspect ratio at high resolution (max 720px)
          const scale = Math.min(1, 720 / Math.max(crop.width, crop.height));
          const rw = Math.max(64, Math.round(crop.width * scale));
          const rh = Math.max(64, Math.round(crop.height * scale));
          if (roiCanvas.width !== rw || roiCanvas.height !== rh) {
            roiCanvas.width = rw;
            roiCanvas.height = rh;
          }
          if (c.width !== rw || c.height !== rh) {
            c.width = rw;
            c.height = rh;
          }

          roiCtx.save();
          if (lab.facingMode === 'user') {
            roiCtx.translate(rw, 0);
            roiCtx.scale(-1, 1);
          }
          roiCtx.drawImage(videoEl, crop.left, crop.top, crop.width, crop.height, 0, 0, rw, rh);
          roiCtx.restore();

          ctx.drawImage(roiCanvas, 0, 0, rw, rh);

          let frameRes = null;
          const t0 = performance.now();
          const activeSpec = MODEL_SPECS[lab.modelTier] || MODEL_SPECS.yolo26n;

          if (activeSpec.isYolo) {
            const sess = yoloOrtSession || (yoloUnavailableReason ? null : await ensureYoloOrtSession(lab.modelTier));
            if (sess && ortWebModule) {
              const sz = 640;
              const r = Math.min(sz / rw, sz / rh);
              const nw = Math.round(rw * r), nh = Math.round(rh * r);
              const px = (sz - nw) * 0.5, py = (sz - nh) * 0.5;
              yoloCtx.fillStyle = 'rgb(114,114,114)';
              yoloCtx.fillRect(0, 0, sz, sz);
              yoloCtx.drawImage(roiCanvas, 0, 0, rw, rh, px, py, nw, nh);
              const rgba = yoloCtx.getImageData(0, 0, sz, sz).data;
              const plane = sz * sz, inv = 1 / 255;
              for (let i = 0; i < plane; i++) {
                const o = i * 4;
                yoloFloatBuf[i] = rgba[o] * inv;
                yoloFloatBuf[plane + i] = rgba[o + 1] * inv;
                yoloFloatBuf[plane * 2 + i] = rgba[o + 2] * inv;
              }
              const inName = sess.inputNames[0] || 'images';
              const tensor = new ortWebModule.Tensor('float32', yoloFloatBuf, [1, 3, sz, sz]);
              const feeds = { [inName]: tensor };
              const outMap = await sess.run(feeds);
              lab.inferenceMs = Math.max(1, Math.round(performance.now() - t0));
              const outTensor = outMap[sess.outputNames[0]];
              if (outTensor) {
                const boxThresh = Math.max(0.20, Math.min(0.72, lab.minConfidence * 0.62));
                const det = decodeYoloPoseTensor(outTensor.data, outTensor.dims, sz, px, py, nw, nh, boxThresh);
                if (det) {
                  const smoothed = smoothKeypointsAdaptive(prevSmoothedKp, det.keypoints, lab.smoothingStrength);
                  prevSmoothedKp = smoothed;
                  const validPts = Object.values(smoothed).filter(p => p && p.c >= lab.minConfidence);
                  const angles = computeAnglesFromKeypoints(smoothed, lab.minConfidence, lab.baselineRollOffsetDeg);
                  const hasShoulders = smoothed.leftShoulder?.c >= lab.minConfidence && smoothed.rightShoulder?.c >= lab.minConfidence;
                  const hasUpperLock = hasShoulders && headReferencePoint(smoothed, lab.minConfidence) != null && angles.roll != null;
                  const validAnglesCnt = [angles.lElbow, angles.rElbow, angles.lShoulder, angles.rShoulder, angles.lKnee, angles.rKnee, angles.lHip, angles.rHip, angles.roll].filter(v => v != null).length;
                  if ((validPts.length >= 4 && validAnglesCnt >= 2) || hasUpperLock) {
                    const delta = computeAngleDelta(prevFrameAngles, angles);
                    prevFrameAngles = angles;
                    const pose = classifyPoseFromAngles(angles, smoothed, delta, lab);
                    const avgConf = validPts.reduce((s, p) => s + p.c, 0) / validPts.length;
                    frameRes = {
                      detected: true,
                      pose,
                      angles,
                      keypoints: smoothed,
                      confidence: avgConf,
                      landmarkCount: validPts.length,
                      box: det.box,
                    };
                  }
                }
              }
            }
          }

          if (!frameRes && (!activeSpec.isYolo || yoloUnavailableReason)) {
            const mpTier = activeSpec.isYolo ? 'full' : lab.modelTier;
            const detector = poseLandmarker || (mpUnavailableReason ? null : await ensurePoseLandmarker(mpTier));
            if (detector) {
              const mpOut = detector.detect(roiCanvas);
              lab.inferenceMs = Math.max(1, Math.round(performance.now() - t0));
              const firstPose = mpOut && mpOut.landmarks && mpOut.landmarks[0];
              if (firstPose && firstPose.length >= 29) {
                const rawKp = mapMediaPipeLandmarks(firstPose);
                const smoothed = smoothKeypointsAdaptive(prevSmoothedKp, rawKp, lab.smoothingStrength);
                prevSmoothedKp = smoothed;

                const validPts = Object.values(smoothed).filter(p => p && p.c >= lab.minConfidence);
                const angles = computeAnglesFromKeypoints(smoothed, lab.minConfidence, lab.baselineRollOffsetDeg);

                const hasShoulders = smoothed.leftShoulder?.c >= lab.minConfidence && smoothed.rightShoulder?.c >= lab.minConfidence;
                const hasUpperLock = hasShoulders && headReferencePoint(smoothed, lab.minConfidence) != null && angles.roll != null;
                const validAnglesCnt = [angles.lElbow, angles.rElbow, angles.lShoulder, angles.rShoulder, angles.lKnee, angles.rKnee, angles.lHip, angles.rHip, angles.roll].filter(v => v != null).length;

                if ((validPts.length >= 4 && validAnglesCnt >= 2) || hasUpperLock) {
                  const delta = computeAngleDelta(prevFrameAngles, angles);
                  prevFrameAngles = angles;
                  const pose = classifyPoseFromAngles(angles, smoothed, delta, lab);
                  const xs = validPts.map(p => p.x), ys = validPts.map(p => p.y);
                  const avgConf = validPts.reduce((s, p) => s + p.c, 0) / validPts.length;
                  frameRes = {
                    detected: true,
                    pose,
                    angles,
                    keypoints: smoothed,
                    confidence: avgConf,
                    landmarkCount: validPts.length,
                    box: {
                      left: Math.max(0.02, Math.min(...xs) - 0.06),
                      top: Math.max(0.02, Math.min(...ys) - 0.06),
                      right: Math.min(0.98, Math.max(...xs) + 0.06),
                      bottom: Math.min(0.98, Math.max(...ys) + 0.06),
                    },
                  };
                }
              }
            }
          }

          if (!frameRes && lab.enableContourFallback) {
            smallCtx.drawImage(roiCanvas, 0, 0, 96, 96);
            frameRes = analyzeContourAngles(smallCtx, 96, 96);
            lab.inferenceMs = Math.max(1, Math.round(performance.now() - t0));
          }

          if (!frameRes) {
            frameRes = { detected: false, pose: 'none', angles: null, box: null, confidence: 0, landmarkCount: 0, keypoints: null };
          }

          recordAngleFrame(frameRes);
          drawPoseOverlay(ctx, rw, rh, frameRes);

          const thumb = c.toDataURL('image/jpeg', 0.55);
          broadcastSnapshot(thumb);
          updateConsoleDom();
        } catch (_) {
        } finally {
          isProcessingFrame = false;
        }
      }, 150);
    } catch (err) {
      lab.monitoring = false;
      if (hint) {
        hint.classList.remove('hidden');
        hint.textContent = err.message || '未获取到摄像头权限，仍可使用下方预设动作角度模拟与手表调试';
      }
    }
  }

  function stopCamera() {
    if (loopTimer) { clearInterval(loopTimer); loopTimer = null; }
    if (mediaStream) {
      mediaStream.getTracks().forEach(t => t.stop());
      mediaStream = null;
    }
    videoEl = null;
    isProcessingFrame = false;
    lab.monitoring = false;
  }

  function updateConsoleDom() {
    if (typeof document === 'undefined') return;
    const poseBadge = document.getElementById('poseLabPoseBadge');
    if (!poseBadge) return;
    const shortPose = SHORT_POSE_LABELS[lab.poseCode] || '未检出骨架';
    const confPct = Math.round((lab.confidence || 0) * 100);
    poseBadge.textContent = lab.poseCode === 'none'
      ? `${shortPose} · 阈值≥${Math.round(lab.minConfidence * 100)}%`
      : `${shortPose} (${confPct}%) · ${lab.changeTimestamps.length}/${lab.threshold}`;

    const zoomBadge = document.getElementById('poseLabZoomBadge');
    if (zoomBadge) {
      zoomBadge.textContent = `${lab.zoomRatio.toFixed(1)}x`;
      zoomBadge.classList.toggle('hidden', lab.zoomRatio <= 1.05);
    }

    const calBtn = document.getElementById('poseLabCalibrateBtn');
    if (calBtn) {
      calBtn.textContent = Math.abs(lab.baselineRollOffsetDeg) >= 1
        ? `🎯 已校准(${Math.round(lab.baselineRollOffsetDeg)}°)`
        : '🎯 零点校准';
    }

    const hudBox = document.getElementById('poseLabHudBox');
    if (hudBox) {
      hudBox.classList.toggle('hidden', !lab.showHud);
      const mLine = document.getElementById('poseLabHudModel');
      const aLine = document.getElementById('poseLabHudAngles');
      if (mLine) {
        const msStr = lab.inferenceMs > 0 ? ` · ${lab.inferenceMs}ms` : '';
        const ptStr = lab.landmarkCount > 0 ? ` · ${lab.landmarkCount}点` : '';
        mLine.textContent = `${lab.activeModelStatus}${msStr}${ptStr}`;
      }
      if (aLine) {
        aLine.textContent = lab.poseCode !== 'none'
          ? `Δ角 ${Math.round(lab.lastAngleDeltaDeg)}°/阈值${Math.round(lab.angleThresholdDeg)}° | ${formatAnglesSummary(lab.angles)}`
          : `未检出有效骨骼关键点 · 仅真实3D骨架防误报 · 点击「模型与阈值」可调灵敏度`;
      }
    }

    const alarmBar = document.getElementById('poseLabAlarmBar');
    if (alarmBar) alarmBar.classList.toggle('hidden', !lab.alarmRinging);

    const dot = document.getElementById('poseLabStatusDot');
    const statusTv = document.getElementById('poseLabStatusText');
    if (dot && statusTv) {
      const snoozeLeft = lab.snoozeUntilMs > Date.now() ? Math.ceil((lab.snoozeUntilMs - Date.now()) / 1000) : 0;
      if (lab.alarmRinging) {
        dot.style.backgroundColor = '#F43F5E';
        statusTv.textContent = '醒来告警';
      } else if (snoozeLeft > 0) {
        dot.style.backgroundColor = '#60A5FA';
        statusTv.textContent = `静音 ${snoozeLeft}s`;
      } else if (lab.changeTimestamps.length >= Math.ceil(lab.threshold / 2)) {
        dot.style.backgroundColor = '#FBBF24';
        statusTv.textContent = `体动 ${lab.changeTimestamps.length}/${lab.threshold}`;
      } else if (lab.poseCode !== 'none') {
        dot.style.backgroundColor = '#34D399';
        statusTv.textContent = '安睡监控';
      } else {
        dot.style.backgroundColor = '#94A3B8';
        statusTv.textContent = '待检出目标';
      }
    }
  }

  function bindCanvasGestures(canvas) {
    let dragging = false, lx = 0, ly = 0;
    let pinchStartDist = 0, pinchStartZoom = 1.0;
    let lastTapAt = 0;
    canvas.style.touchAction = 'none';

    const dist2 = (t0, t1) => Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY);
    const startPan = (x, y) => { dragging = true; lx = x; ly = y; };
    const movePan = (x, y) => {
      if (!dragging || lab.zoomRatio <= 1.05) return;
      const dx = x - lx, dy = y - ly;
      lx = x; ly = y;
      lab.panX = Math.max(-1, Math.min(1, lab.panX - (dx / Math.max(1, canvas.clientWidth)) * 1.8));
      lab.panY = Math.max(-1, Math.min(1, lab.panY - (dy / Math.max(1, canvas.clientHeight)) * 1.8));
    };

    canvas.addEventListener('mousedown', e => startPan(e.clientX, e.clientY));
    window.addEventListener('mousemove', e => movePan(e.clientX, e.clientY));
    window.addEventListener('mouseup', () => { dragging = false; });
    canvas.addEventListener('wheel', e => {
      e.preventDefault();
      const factor = e.deltaY < 0 ? 1.12 : 0.89;
      PoseWatchLab.setZoom(lab.zoomRatio * factor);
    }, { passive: false });

    canvas.addEventListener('touchstart', e => {
      if (e.touches.length === 2) {
        dragging = false;
        pinchStartDist = dist2(e.touches[0], e.touches[1]);
        pinchStartZoom = lab.zoomRatio;
      } else if (e.touches.length === 1) {
        const now = Date.now();
        if (now - lastTapAt < 280) {
          PoseWatchLab.setZoom(lab.zoomRatio < 1.8 ? 2.5 : 1.0);
          lastTapAt = 0;
          return;
        }
        lastTapAt = now;
        startPan(e.touches[0].clientX, e.touches[0].clientY);
      }
    }, { passive: true });

    canvas.addEventListener('touchmove', e => {
      if (e.touches.length === 2 && pinchStartDist > 8) {
        e.preventDefault();
        const curDist = dist2(e.touches[0], e.touches[1]);
        PoseWatchLab.setZoom(pinchStartZoom * (curDist / pinchStartDist));
      } else if (e.touches.length === 1) {
        if (lab.zoomRatio > 1.05) e.preventDefault();
        movePan(e.touches[0].clientX, e.touches[0].clientY);
      }
    }, { passive: false });

    canvas.addEventListener('touchend', e => {
      if (e.touches.length < 2) pinchStartDist = 0;
      if (e.touches.length === 0) dragging = false;
    });
  }

  function syncSettingsDomControls() {
    if (typeof document === 'undefined') return;
    document.querySelectorAll('[data-mp-tier]').forEach(btn => {
      const active = btn.getAttribute('data-mp-tier') === lab.modelTier;
      btn.classList.toggle('chip-active', active);
      btn.classList.toggle('chip-base', !active);
    });
    const setRange = (id, valId, val, text) => {
      const el = document.getElementById(id);
      const tv = document.getElementById(valId);
      if (el) el.value = val;
      if (tv) tv.textContent = text;
    };
    setRange('plMinConf', 'plMinConfVal', Math.round(lab.minConfidence * 100), `${Math.round(lab.minConfidence * 100)}%`);
    setRange('plAngleThresh', 'plAngleThreshVal', Math.round(lab.angleThresholdDeg), `≥${Math.round(lab.angleThresholdDeg)}°`);
    setRange('plRollThresh', 'plRollThreshVal', Math.round(lab.rollThresholdDeg), `±${Math.round(lab.rollThresholdDeg)}°`);
    setRange('plSmooth', 'plSmoothVal', Math.round(lab.smoothingStrength * 100), `${Math.round(lab.smoothingStrength * 100)}%`);
    setRange('plConfirm', 'plConfirmVal', lab.confirmFrames, `${lab.confirmFrames}帧`);
    setRange('plCount', 'plCountVal', lab.threshold, `${lab.threshold}次`);
    setRange('plWin', 'plWinVal', lab.windowSec, `${lab.windowSec}秒`);
    const hudChk = document.getElementById('plHudChk');
    if (hudChk) hudChk.checked = lab.showHud;
    const fbChk = document.getElementById('plFallbackChk');
    if (fbChk) fbChk.checked = lab.enableContourFallback;
  }

  function openConsole() {
    ensureAudio();
    const overlay = document.getElementById('sheetOverlay');
    const panel = document.getElementById('sheetPanel');
    panel.innerHTML = `
      <div class="flex items-center justify-between mb-2.5 gap-2">
        <div class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full chip-base text-xs font-bold">
          <span id="poseLabStatusDot" class="w-2 h-2 rounded-full" style="background-color:#34D399;"></span>
          <span id="poseLabStatusText">待检出目标</span>
        </div>
        <div class="flex items-center gap-1.5">
          <button id="poseLabCalibrateBtn" onclick="PoseWatchLab.calibrateBaseline()" class="px-2.5 py-1.5 rounded-full text-[11px] font-bold chip-base">🎯 零点校准</button>
          <button onclick="PoseWatchLab.closeConsole()" class="w-8 h-8 rounded-full text-sm font-bold chip-base flex items-center justify-center">✕</button>
        </div>
      </div>

      <div class="relative rounded-3xl overflow-hidden bg-slate-950 mb-3 border" style="border-color: var(--border-card); height: 330px;">
        <canvas id="poseLabCanvas" width="640" height="480" class="w-full h-full object-contain cursor-grab"></canvas>
        <div id="poseLabHint" class="absolute inset-0 flex items-center justify-center text-xs text-slate-400 px-6 text-center pointer-events-none">
          正在开启高清相机与 MediaPipe 3D 骨架模型...
        </div>
        <div id="poseLabPoseBadge" class="absolute top-3 left-3 px-2.5 py-1 rounded-full text-[11px] font-bold text-white pointer-events-none" style="background: rgba(13,17,23,0.75);">
          检测中
        </div>
        <button id="poseLabZoomBadge" onclick="PoseWatchLab.setZoom(1)" class="hidden absolute top-3 right-3 px-2.5 py-1 rounded-full text-[11px] font-bold text-white" style="background: rgba(13,17,23,0.75);">
          1.0x
        </button>
        <div id="poseLabHudBox" class="absolute inset-x-2.5 bottom-2.5 px-3 py-2 rounded-2xl text-[10.5px] font-mono leading-snug" style="background: rgba(13,17,23,0.80); color: #CBD5E1; border: 1px solid rgba(148,163,184,0.22);">
          <div id="poseLabHudModel" class="font-bold truncate" style="color:#93C5FD;">${lab.activeModelStatus}</div>
          <div id="poseLabHudAngles" class="truncate mt-0.5">等待骨骼关键点...</div>
        </div>
      </div>

      <div id="poseLabAlarmBar" class="hidden grid grid-cols-3 gap-2 mb-3">
        <button onclick="PoseWatchLab.dismissAlarm()" class="col-span-2 h-11 rounded-2xl text-sm font-bold text-white" style="background-color:#F43F5E;">关闭闹钟</button>
        <button onclick="PoseWatchLab.snoozeAlarm()" class="h-11 rounded-2xl text-xs font-bold chip-base">贪睡 3m</button>
      </div>

      <div class="grid grid-cols-3 gap-2 mb-2">
        <button onclick="PoseWatchLab.flipCamera()" class="h-10 rounded-2xl text-xs font-bold chip-base">切换镜头</button>
        <a href="?mode=watch" target="_blank" class="h-10 rounded-2xl text-xs font-bold chip-base inline-flex items-center justify-center">手表表盘</a>
        <button onclick="document.getElementById('poseLabSettingsBox').classList.toggle('hidden')" class="h-10 rounded-2xl text-xs font-bold chip-active">模型与阈值 ▾</button>
      </div>

      <div id="poseLabSettingsBox" class="mt-2 p-3.5 rounded-2xl border space-y-3" style="background-color: var(--bg-surface); border-color: var(--border-card);">
        <div>
          <div class="text-[11px] font-bold mb-1.5" style="color: var(--text-secondary);">检测模型选择 (YOLO Pose ONNX 内置/云端 vs BlazePose 对照)</div>
          <div class="grid grid-cols-2 gap-1.5">
            ${Object.values(MODEL_SPECS).map(m => `<button data-mp-tier="${m.code}" onclick="PoseWatchLab.setModelTier('${m.code}')" class="py-1.5 px-2 rounded-xl text-[11px] font-semibold ${lab.modelTier === m.code ? 'chip-active' : 'chip-base'}">${m.shortTitle}</button>`).join('')}
          </div>
        </div>

        <div>
          <div class="text-[11px] font-bold mb-1.5" style="color: var(--text-secondary);">一键灵敏度预设</div>
          <div class="grid grid-cols-3 gap-1.5">
            <button onclick="PoseWatchLab.applyPreset('sensitive')" class="py-1.5 rounded-xl text-[11px] font-semibold chip-base">高灵敏</button>
            <button onclick="PoseWatchLab.applyPreset('balanced')" class="py-1.5 rounded-xl text-[11px] font-semibold chip-base">标准推荐</button>
            <button onclick="PoseWatchLab.applyPreset('strict')" class="py-1.5 rounded-xl text-[11px] font-semibold chip-base">严苛防误报</button>
          </div>
        </div>

        <div class="space-y-2 text-xs">
          <div>
            <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">关键点最低置信度 (过滤被子褶皱)</span><strong id="plMinConfVal">${Math.round(lab.minConfidence * 100)}%</strong></div>
            <input id="plMinConf" type="range" min="20" max="85" step="5" value="${Math.round(lab.minConfidence * 100)}" oninput="PoseWatchLab.updateParam('minConfidence', Number(this.value)/100)" class="w-full accent-orange-500" />
          </div>
          <div>
            <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">体动计数最小关节角度变化</span><strong id="plAngleThreshVal">≥${Math.round(lab.angleThresholdDeg)}°</strong></div>
            <input id="plAngleThresh" type="range" min="8" max="60" step="1" value="${Math.round(lab.angleThresholdDeg)}" oninput="PoseWatchLab.updateParam('angleThresholdDeg', Number(this.value))" class="w-full accent-orange-500" />
          </div>
          <div>
            <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">左右翻身/侧卧判定角阈值</span><strong id="plRollThreshVal">±${Math.round(lab.rollThresholdDeg)}°</strong></div>
            <input id="plRollThresh" type="range" min="15" max="55" step="1" value="${Math.round(lab.rollThresholdDeg)}" oninput="PoseWatchLab.updateParam('rollThresholdDeg', Number(this.value))" class="w-full accent-orange-500" />
          </div>
          <div>
            <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">关键点自适应防抖滤波强度</span><strong id="plSmoothVal">${Math.round(lab.smoothingStrength * 100)}%</strong></div>
            <input id="plSmooth" type="range" min="0" max="90" step="5" value="${Math.round(lab.smoothingStrength * 100)}" oninput="PoseWatchLab.updateParam('smoothingStrength', Number(this.value)/100)" class="w-full accent-orange-500" />
          </div>
          <div class="grid grid-cols-3 gap-2">
            <div>
              <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">确认帧</span><strong id="plConfirmVal">${lab.confirmFrames}帧</strong></div>
              <input id="plConfirm" type="range" min="1" max="5" step="1" value="${lab.confirmFrames}" oninput="PoseWatchLab.updateParam('confirmFrames', Number(this.value))" class="w-full accent-orange-500" />
            </div>
            <div>
              <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">唤醒次数</span><strong id="plCountVal">${lab.threshold}次</strong></div>
              <input id="plCount" type="range" min="1" max="12" step="1" value="${lab.threshold}" oninput="PoseWatchLab.updateParam('threshold', Number(this.value))" class="w-full accent-orange-500" />
            </div>
            <div>
              <div class="flex justify-between mb-0.5"><span style="color:var(--text-secondary);">时间窗</span><strong id="plWinVal">${lab.windowSec}秒</strong></div>
              <input id="plWin" type="range" min="10" max="120" step="5" value="${lab.windowSec}" oninput="PoseWatchLab.updateParam('windowSec', Number(this.value))" class="w-full accent-orange-500" />
            </div>
          </div>
        </div>

        <div class="space-y-1.5 pt-1 text-xs">
          <label class="flex items-center justify-between cursor-pointer">
            <span style="color:var(--text-secondary);">显示实时模型耗时与关节角 HUD</span>
            <input id="plHudChk" type="checkbox" ${lab.showHud ? 'checked' : ''} onchange="PoseWatchLab.updateParam('showHud', this.checked)" />
          </label>
          <label class="flex items-center justify-between cursor-pointer">
            <span style="color:var(--text-secondary);">离线无3D骨架时启用色块轮廓兜底</span>
            <input id="plFallbackChk" type="checkbox" ${lab.enableContourFallback ? 'checked' : ''} onchange="PoseWatchLab.updateParam('enableContourFallback', this.checked)" />
          </label>
        </div>

        <div class="grid grid-cols-3 gap-2 pt-1">
          <button onclick="PoseWatchLab.simStep()" class="h-9 rounded-xl text-xs font-semibold chip-base">模拟动作</button>
          <button onclick="PoseWatchLab.resetState()" class="h-9 rounded-xl text-xs font-semibold chip-base">清零计数</button>
          <button onclick="PoseWatchLab.testAlarm()" class="h-9 rounded-xl text-xs font-semibold chip-base">测试闹钟</button>
        </div>
      </div>
    `;
    overlay.classList.remove('hidden');
    overlay.classList.add('flex');
    requestAnimationFrame(() => overlay.classList.add('sheet-open'));

    const canvas = document.getElementById('poseLabCanvas');
    if (canvas) bindCanvasGestures(canvas);
    startCamera();
    updateConsoleDom();
  }

  function closeConsole() {
    stopCamera();
    if (typeof window !== 'undefined' && window.App && window.App.closeSheet) window.App.closeSheet();
  }

  function bootWatchFaceModeIfRequested() {
    if (typeof window === 'undefined' || typeof document === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    if (params.get('mode') !== 'watch') return;
    const hostParam = params.get('host') || '';

    document.body.innerHTML = `
      <div style="min-height:100dvh;background:#090B10;color:#F8FAFC;display:flex;align-items:center;justify-content:center;padding:10px;font-family:sans-serif;">
        <div id="wDial" style="width:min(95vw,336px);min-height:min(95vw,336px);border-radius:44px;background:#111624;border:3px solid #2EC4B6;padding:16px 14px;display:flex;flex-direction:column;align-items:center;justify-content:space-between;text-align:center;box-shadow:0 0 24px rgba(46,196,182,0.22);">
          <div style="font-size:11px;color:#94A3B8;background:rgba(255,255,255,0.07);padding:3px 10px;border-radius:999px;">⌚ 婴儿看护手表端</div>
          <div>
            <div id="wStatus" style="font-size:20px;font-weight:800;margin-top:4px;">🌙 安睡监控中</div>
            <div id="wPose" style="font-size:13px;color:#5EEAD4;margin-top:4px;">姿态：等待同步...</div>
          </div>
          <img id="wThumb" style="display:none;width:100%;height:106px;object-fit:cover;border-radius:12px;border:1px solid rgba(255,255,255,0.15);" />
          <div style="background:rgba(255,255,255,0.06);padding:7px 12px;border-radius:14px;width:100%;font-size:12.5px;">
            大幅角度变化：<strong id="wCnt" style="color:#FCD34D;font-size:16px;">0 / 4</strong>
            <div id="wZoom" style="font-size:10.5px;color:#94A3B8;margin-top:2px;">阈值 ≥22° · 放大 1.0x</div>
          </div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;width:100%;">
            <button id="wBtnDismiss" style="grid-column:span 2;background:#FF3B5C;color:#fff;border:none;border-radius:999px;padding:10px;font-weight:700;font-size:13.5px;cursor:pointer;">🔕 关闭闹钟</button>
            <button id="wBtnSnooze" style="background:#3B82F6;color:#fff;border:none;border-radius:999px;padding:8px;font-weight:700;font-size:12px;cursor:pointer;">💤 贪睡 3m</button>
            <button id="wBtnZoom" style="background:#334155;color:#fff;border:none;border-radius:999px;padding:8px;font-weight:700;font-size:12px;cursor:pointer;">🔍 放大切换</button>
          </div>
        </div>
      </div>
    `;

    const applyWatchData = (d) => {
      if (!d) return;
      const dial = document.getElementById('wDial');
      const deltaStr = d.lastAngleDeltaDeg !== undefined ? ` (Δ${Math.round(d.lastAngleDeltaDeg)}°)` : '';
      document.getElementById('wPose').textContent = '当前姿态：' + (d.poseLabel || '待命') + deltaStr;
      document.getElementById('wCnt').textContent = `${d.changeCount || 0} / ${d.threshold || 4} 次`;
      document.getElementById('wZoom').textContent = `角度阈值 ≥${Math.round(d.angleThresholdDeg || 22)}° · 放大 ${Number(d.zoomRatio || 1).toFixed(1)}x`;
      if (d.frameDataUrl) {
        const img = document.getElementById('wThumb');
        img.src = d.frameDataUrl;
        img.style.display = 'block';
      }
      if (d.alarmRinging) {
        dial.style.borderColor = '#FF3B5C';
        dial.style.background = '#330A14';
        document.getElementById('wStatus').textContent = '🚨 宝宝要醒了！';
        if (!lab.alarmRinging) { lab.alarmRinging = true; startAlarmSound(); }
      } else {
        dial.style.borderColor = '#2EC4B6';
        dial.style.background = '#111624';
        document.getElementById('wStatus').textContent = d.snoozedRemainingSec > 0 ? `💤 贪睡 (${d.snoozedRemainingSec}s)` : '🌙 安睡监控中';
        if (lab.alarmRinging) { lab.alarmRinging = false; stopAlarmSound(); }
      }
    };

    document.getElementById('wBtnDismiss').onclick = () => {
      ensureAudio();
      if (hostParam) fetch(`${hostParam}/api/alarm/dismiss`, { method: 'POST' }).catch(() => {});
      if (bc) bc.postMessage({ type: 'cmd', action: 'dismiss' });
      lab.alarmRinging = false;
      stopAlarmSound();
    };
    document.getElementById('wBtnSnooze').onclick = () => {
      ensureAudio();
      if (hostParam) fetch(`${hostParam}/api/alarm/snooze`, { method: 'POST' }).catch(() => {});
      if (bc) bc.postMessage({ type: 'cmd', action: 'snooze' });
      lab.alarmRinging = false;
      stopAlarmSound();
    };
    document.getElementById('wBtnZoom').onclick = () => {
      const nextZ = lab.zoomRatio >= 4 ? 1.5 : (lab.zoomRatio + 1.0);
      lab.zoomRatio = nextZ;
      if (hostParam) fetch(`${hostParam}/api/zoom?ratio=${nextZ}`, { method: 'POST' }).catch(() => {});
      if (bc) bc.postMessage({ type: 'cmd', action: 'zoom', ratio: nextZ });
    };

    if (bc) {
      bc.onmessage = (ev) => {
        if (ev.data && ev.data.type === 'status') applyWatchData(ev.data.payload);
      };
    }
    setInterval(async () => {
      if (hostParam) {
        try {
          const r = await fetch(`${hostParam}/api/status`);
          applyWatchData(await r.json());
          return;
        } catch (_) {}
      }
      try {
        const raw = localStorage.getItem(STATE_KEY);
        if (raw) applyWatchData(JSON.parse(raw));
      } catch (_) {}
    }, 900);
  }

  if (bc) {
    bc.onmessage = (ev) => {
      if (!ev.data || ev.data.type !== 'cmd') return;
      if (ev.data.action === 'dismiss') dismissAlarm();
      if (ev.data.action === 'snooze') snoozeAlarm();
      if (ev.data.action === 'zoom' && ev.data.ratio) {
        lab.zoomRatio = Math.min(8, Math.max(1, Number(ev.data.ratio)));
        persistConfig();
        updateConsoleDom();
      }
    };
  }

  const api = {
    toggleHiddenEntry() {
      const card = document.getElementById('poseLabWebHiddenCard');
      if (card) card.classList.toggle('hidden');
    },
    openConsole,
    closeConsole,
    setZoom(val) {
      lab.zoomRatio = Math.min(8.0, Math.max(1.0, Number(val) || 1.0));
      if (lab.zoomRatio <= 1.05) { lab.panX = 0; lab.panY = 0; }
      persistConfig();
      broadcastSnapshot();
      updateConsoleDom();
    },
    setModelTier(tier) {
      if (!MODEL_SPECS[tier]) return;
      lab.modelTier = tier;
      prevSmoothedKp = null;
      persistConfig();
      syncSettingsDomControls();
      if (MODEL_SPECS[tier].isYolo) ensureYoloOrtSession(tier);
      else ensurePoseLandmarker(tier);
    },
    applyPreset(name) {
      const p = PRESET_CONFIGS[name];
      if (!p) return;
      Object.assign(lab, p);
      persistConfig();
      syncSettingsDomControls();
      broadcastSnapshot();
      updateConsoleDom();
    },
    updateParam(key, val) {
      lab[key] = val;
      persistConfig();
      syncSettingsDomControls();
      broadcastSnapshot();
      updateConsoleDom();
    },
    calibrateBaseline() {
      if (Math.abs(lab.baselineRollOffsetDeg) >= 1) {
        lab.baselineRollOffsetDeg = 0;
        persistConfig();
        updateConsoleDom();
        return;
      }
      if (typeof lab.lastRawUnadjustedRoll === 'number') {
        lab.baselineRollOffsetDeg = Math.max(-65, Math.min(65, lab.lastRawUnadjustedRoll));
        anchorPose = 'none';
        anchorAngles = null;
        persistConfig();
        updateConsoleDom();
      }
    },
    resetState() {
      lab.changeTimestamps = [];
      lab.recentHistory = [];
      lab.lastAngleDeltaDeg = 0;
      anchorPose = 'none';
      anchorAngles = null;
      prevSmoothedKp = null;
      broadcastSnapshot();
      updateConsoleDom();
    },
    flipCamera() {
      lab.facingMode = lab.facingMode === 'environment' ? 'user' : 'environment';
      startCamera();
    },
    simStep() {
      ensureAudio();
      const order = ['supine_calm', 'side_left', 'limb_active', 'side_right', 'prone_head_up', 'sitting_awake'];
      const next = order[(order.indexOf(lab.poseCode) + 1) % order.length];
      lastChangeAt = 0;
      recordAngleFrame({
        detected: true,
        pose: next,
        angles: PRESET_ANGLES[next],
        confidence: 0.94,
        landmarkCount: 17,
        box: { left: 0.25, top: 0.2, right: 0.75, bottom: 0.8 },
      }, true);
      broadcastSnapshot();
      updateConsoleDom();
    },
    testAlarm() {
      ensureAudio();
      const now = Date.now();
      lab.changeTimestamps = Array.from({ length: lab.threshold }, (_, i) => now - i * 400);
      lab.poseCode = 'limb_active';
      lab.poseLabel = POSE_LABELS.limb_active;
      lab.angles = PRESET_ANGLES.limb_active;
      lab.confidence = 0.96;
      lab.lastAngleDeltaDeg = 68;
      triggerWakeNow('手动测试唤醒闹钟+手表 (Δ68°)');
    },
    dismissAlarm,
    snoozeAlarm,
    _engine: {
      jointAngleDeg,
      smoothKeypointsAdaptive,
      computeAnglesFromKeypoints,
      computeAngleDelta,
      classifyPoseFromAngles,
      decodeYoloPoseTensor,
    },
  };

  if (typeof window !== 'undefined') {
    window.PoseWatchLab = api;
    if (typeof document !== 'undefined') {
      document.addEventListener('DOMContentLoaded', bootWatchFaceModeIfRequested);
    }
  }
  if (typeof globalThis !== 'undefined') {
    globalThis.PoseWatchLab = api;
  }
})();
