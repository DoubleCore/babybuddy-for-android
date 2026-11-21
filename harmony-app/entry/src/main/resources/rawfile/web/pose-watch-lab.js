// Experimental Module: Baby Pose Detection, 1x-8x Camera Zoom, Fast Wake Alarm & Smartwatch UI
// Hidden under the Quick Care functional area ("待测 ▾") and accessible via ?mode=watch

(() => {
  const CHANNEL_NAME = 'babybuddy_pose_watch_channel_v1';
  const STATE_KEY = 'babybuddy_pose_watch_state_v1';
  const bc = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL_NAME) : null;

  const POSE_LABELS = {
    none: '未检测到婴儿',
    supine_calm: '仰卧安睡',
    side_left: '左侧翻身',
    side_right: '右侧翻身',
    prone_head_up: '趴卧/抬头',
    limb_active: '四肢挥动/踢腿',
  };

  let lab = {
    monitoring: false,
    zoomRatio: 2.0,
    panX: 0,
    panY: 0,
    threshold: 5,
    windowSec: 30,
    poseCode: 'none',
    poseLabel: POSE_LABELS.none,
    motionScore: 0,
    changeTimestamps: [],
    recentHistory: [],
    alarmRinging: false,
    snoozeUntilMs: 0,
    facingMode: 'environment',
    remoteHostUrl: '',
  };

  let mediaStream = null;
  let videoEl = null;
  let loopTimer = null;
  let prevLuma = null;
  let bgLuma = null;
  let lastStablePose = 'none';
  let lastChangeAt = 0;
  let audioCtx = null;
  let alarmLoopTimer = null;

  function ensureAudio() {
    if (!audioCtx) {
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
    if (window.HarmonyBridge && typeof window.HarmonyBridge.startAlarmVibration === 'function') {
      try { window.HarmonyBridge.startAlarmVibration(); } catch (_) {}
    }
    const ringOnce = () => {
      if (!lab.alarmRinging) return;
      try {
        if (navigator.vibrate) navigator.vibrate([450, 200, 450]);
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
    if (window.HarmonyBridge && typeof window.HarmonyBridge.stopAlarmVibration === 'function') {
      try { window.HarmonyBridge.stopAlarmVibration(); } catch (_) {}
    }
    try {
      if (navigator.vibrate) navigator.vibrate(0);
    } catch (_) {}
  }

  function broadcastSnapshot(includeFrameDataUrl = null) {
    const now = Date.now();
    const cutoff = now - lab.windowSec * 1000;
    lab.changeTimestamps = lab.changeTimestamps.filter(t => t >= cutoff);
    const payload = {
      monitoring: lab.monitoring,
      poseCode: lab.poseCode,
      poseLabel: lab.poseLabel,
      changeCount: lab.changeTimestamps.length,
      threshold: lab.threshold,
      windowSec: lab.windowSec,
      zoomRatio: lab.zoomRatio,
      motionScore: lab.motionScore,
      alarmRinging: lab.alarmRinging,
      snoozedRemainingSec: lab.snoozeUntilMs > now ? Math.ceil((lab.snoozeUntilMs - now) / 1000) : 0,
      recentHistory: lab.recentHistory.slice(0, 5),
      frameDataUrl: includeFrameDataUrl,
      ts: now,
    };
    try {
      localStorage.setItem(STATE_KEY, JSON.stringify(payload));
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

  function analyzeGridFrame(ctxSmall, gw, gh) {
    const img = ctxSmall.getImageData(0, 0, gw, gh).data;
    const n = gw * gh;
    const curr = new Float32Array(n);
    const warm = new Float32Array(n);
    let mean = 0;

    for (let i = 0; i < n; i++) {
      const r = img[i * 4] / 255;
      const g = img[i * 4 + 1] / 255;
      const b = img[i * 4 + 2] / 255;
      const y = 0.299 * r + 0.587 * g + 0.114 * b;
      curr[i] = y;
      mean += y;
      warm[i] = (r > g && g > b * 0.85 && (r - b) > 0.06) ? Math.min(1, (r - b) * 1.4) : 0;
    }
    mean /= n;
    if (!bgLuma) bgLuma = new Float32Array(curr);

    let diffSum = 0;
    let activeCnt = 0;
    let salSum = 0, sx = 0, sy = 0;
    let minX = gw, minY = gh, maxX = 0, maxY = 0;
    const motion = new Float32Array(n);

    for (let y = 1; y < gh - 1; y++) {
      for (let x = 1; x < gw - 1; x++) {
        const idx = y * gw + x;
        const fd = prevLuma ? Math.abs(curr[idx] - prevLuma[idx]) : 0;
        const bd = Math.abs(curr[idx] - bgLuma[idx]);
        bgLuma[idx] = bgLuma[idx] * 0.96 + curr[idx] * 0.04;
        motion[idx] = fd;
        diffSum += fd;
        if (fd > 0.045) activeCnt++;
        const edge = Math.abs(curr[idx] - curr[idx - 1]) * 0.5;
        const sal = fd * 3.2 + bd * 1.2 + warm[idx] * 0.85 + edge * 0.8;
        if (sal > 0.2) {
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
    prevLuma = curr;

    const motionScore = Math.min(1, (activeCnt / n) * 3.6 + (diffSum / n) * 4.5);
    if (salSum < n * 0.008 || maxX <= minX + 3 || maxY <= minY + 3) {
      return { detected: false, pose: 'none', motionScore, box: null };
    }

    const cx = (sx / salSum) / gw;
    const cy = (sy / salSum) / gh;
    const hw = Math.min(0.4, Math.max(0.14, ((maxX - minX) / gw) * 0.5));
    const hh = Math.min(0.42, Math.max(0.16, ((maxY - minY) / gh) * 0.5));
    const box = {
      left: Math.max(0.04, cx - hw),
      top: Math.max(0.04, cy - hh),
      right: Math.min(0.96, cx + hw),
      bottom: Math.min(0.96, cy + hh),
    };

    let leftMass = 0, rightMass = 0, upperM = 0, lowerM = 0;
    const x0 = Math.floor(box.left * gw), x1 = Math.floor(box.right * gw);
    const y0 = Math.floor(box.top * gh), y1 = Math.floor(box.bottom * gh);
    const mx = (x0 + x1) >> 1, my = (y0 + y1) >> 1;
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const idx = y * gw + x;
        const w = warm[idx] + Math.abs(curr[idx] - mean);
        if (x <= mx) leftMass += w; else rightMass += w;
        if (y <= my) upperM += motion[idx]; else lowerM += motion[idx];
      }
    }
    const asym = (rightMass - leftMass) / Math.max(0.001, leftMass + rightMass);
    const aspect = (box.right - box.left) / Math.max(0.05, box.bottom - box.top);

    let pose = 'supine_calm';
    if (motionScore >= 0.22) pose = 'limb_active';
    else if (aspect > 1.32 && upperM > lowerM * 1.2) pose = 'prone_head_up';
    else if (asym < -0.15) pose = 'side_left';
    else if (asym > 0.15) pose = 'side_right';

    return { detected: true, pose, motionScore, box, asym };
  }

  function recordPoseFrame(res) {
    const now = Date.now();
    const cutoff = now - lab.windowSec * 1000;
    lab.changeTimestamps = lab.changeTimestamps.filter(t => t >= cutoff);
    lab.motionScore = res.motionScore;

    if (!res.detected) {
      lab.poseCode = 'none';
      lab.poseLabel = POSE_LABELS.none;
      return;
    }

    lab.poseCode = res.pose;
    lab.poseLabel = POSE_LABELS[res.pose] || POSE_LABELS.supine_calm;

    if (lastStablePose === 'none') {
      lastStablePose = res.pose;
    } else if (now - lastChangeAt >= 1500 && (res.pose !== lastStablePose || res.motionScore >= 0.25)) {
      lab.changeTimestamps.push(now);
      lastChangeAt = now;
      const msg = res.pose !== lastStablePose
        ? `${POSE_LABELS[lastStablePose]} → ${POSE_LABELS[res.pose]}`
        : `${POSE_LABELS[res.pose]} (体动)`;
      lab.recentHistory.unshift(msg);
      lab.recentHistory = lab.recentHistory.slice(0, 6);
      lastStablePose = res.pose;
    }

    if (now >= lab.snoozeUntilMs && !lab.alarmRinging && lab.changeTimestamps.length >= lab.threshold) {
      triggerWakeNow('连续姿态变化触发苏醒告警');
    }
  }

  async function startCamera() {
    stopCamera();
    const canvas = document.getElementById('poseLabCanvas');
    const hint = document.getElementById('poseLabHint');
    if (!canvas) return;
    prevLuma = null;
    bgLuma = null;

    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('当前浏览器环境不支持本地摄像头，可使用下方模拟按钮或手表模式');
      }
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: lab.facingMode, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      videoEl = document.createElement('video');
      videoEl.srcObject = mediaStream;
      videoEl.playsInline = true;
      videoEl.muted = true;
      await videoEl.play();
      lab.monitoring = true;
      if (window.HarmonyBridge && typeof window.HarmonyBridge.setKeepScreenOn === 'function') {
        try { window.HarmonyBridge.setKeepScreenOn(true); } catch (_) {}
      }
      if (hint) hint.classList.add('hidden');

      const smallCanvas = document.createElement('canvas');
      smallCanvas.width = 80;
      smallCanvas.height = 80;
      const smallCtx = smallCanvas.getContext('2d', { willReadFrequently: true });

      loopTimer = setInterval(() => {
        if (!videoEl || videoEl.readyState < 2) return;
        const c = document.getElementById('poseLabCanvas');
        if (!c) { stopCamera(); return; }
        const ctx = c.getContext('2d');
        const vw = videoEl.videoWidth || 640;
        const vh = videoEl.videoHeight || 480;
        const crop = computeCrop(vw, vh, lab.zoomRatio, lab.panX, lab.panY);

        ctx.drawImage(videoEl, crop.left, crop.top, crop.width, crop.height, 0, 0, c.width, c.height);
        smallCtx.drawImage(videoEl, crop.left, crop.top, crop.width, crop.height, 0, 0, 80, 80);

        const res = analyzeGridFrame(smallCtx, 80, 80);
        recordPoseFrame(res);

        // Draw target bounding box & skeleton cue
        if (res.detected && res.box) {
          const bx = res.box.left * c.width;
          const by = res.box.top * c.height;
          const bw = (res.box.right - res.box.left) * c.width;
          const bh = (res.box.bottom - res.box.top) * c.height;
          ctx.strokeStyle = lab.alarmRinging ? '#FF3B5C' : (res.pose === 'limb_active' ? '#FF9F1C' : '#2EC4B6');
          ctx.lineWidth = 2.5;
          ctx.strokeRect(bx, by, bw, bh);
          ctx.fillStyle = 'rgba(15, 23, 42, 0.78)';
          ctx.fillRect(bx, Math.max(4, by - 22), Math.min(c.width - bx - 4, 130), 20);
          ctx.fillStyle = '#FFFFFF';
          ctx.font = 'bold 11px sans-serif';
          ctx.fillText(lab.poseLabel, bx + 6, Math.max(18, by - 8));
        }

        const thumb = c.toDataURL('image/jpeg', 0.5);
        broadcastSnapshot(thumb);
        updateConsoleDom();
      }, 150);
    } catch (err) {
      lab.monitoring = false;
      if (hint) {
        hint.classList.remove('hidden');
        hint.textContent = err.message || '未获取到摄像头权限，您仍可使用模拟姿态变化与手表联动调试';
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
    lab.monitoring = false;
    if (window.HarmonyBridge && typeof window.HarmonyBridge.setKeepScreenOn === 'function') {
      try { window.HarmonyBridge.setKeepScreenOn(false); } catch (_) {}
    }
  }

  function updateConsoleDom() {
    const poseEl = document.getElementById('poseLabCurrentPose');
    if (!poseEl) return;
    poseEl.textContent = lab.poseLabel;
    document.getElementById('poseLabChangeCnt').textContent = `${lab.changeTimestamps.length} / ${lab.threshold} 次 (${lab.windowSec}s)`;
    document.getElementById('poseLabZoomLabel').textContent = `🔍 远距画面放大：${lab.zoomRatio.toFixed(1)}x`;
    document.getElementById('poseLabHistory').textContent = lab.recentHistory.length
      ? `最近轨迹：${lab.recentHistory.join(' · ')}`
      : '最近轨迹：暂无明显姿态切换';

    const banner = document.getElementById('poseLabBanner');
    if (lab.alarmRinging) {
      banner.style.backgroundColor = '#4C0519';
      banner.style.color = '#FDA4AF';
      banner.textContent = '🚨 判定即将醒来！已拉响闹钟并同步手表端';
    } else {
      banner.style.backgroundColor = '#112A28';
      banner.style.color = '#6EE7B7';
      banner.textContent = '🌙 姿态看护运行中 · 频变达标即刻唤醒闹钟与手表';
    }
  }

  function bindCanvasDrag(canvas) {
    let dragging = false, lx = 0, ly = 0;
    const start = (x, y) => { dragging = true; lx = x; ly = y; };
    const move = (x, y) => {
      if (!dragging || lab.zoomRatio <= 1.05) return;
      const dx = x - lx, dy = y - ly;
      lx = x; ly = y;
      lab.panX = Math.max(-1, Math.min(1, lab.panX - (dx / canvas.clientWidth) * 1.8));
      lab.panY = Math.max(-1, Math.min(1, lab.panY - (dy / canvas.clientHeight) * 1.8));
    };
    canvas.addEventListener('mousedown', e => start(e.clientX, e.clientY));
    window.addEventListener('mousemove', e => move(e.clientX, e.clientY));
    window.addEventListener('mouseup', () => { dragging = false; });
    canvas.addEventListener('touchstart', e => {
      if (e.touches.length === 1) start(e.touches[0].clientX, e.touches[0].clientY);
    }, { passive: true });
    canvas.addEventListener('touchmove', e => {
      if (e.touches.length === 1) move(e.touches[0].clientX, e.touches[0].clientY);
    }, { passive: true });
    canvas.addEventListener('touchend', () => { dragging = false; });
  }

  function openConsole() {
    ensureAudio();
    const watchUrl = `${window.location.origin}${window.location.pathname}?mode=watch`;
    const overlay = document.getElementById('sheetOverlay');
    const panel = document.getElementById('sheetPanel');
    panel.innerHTML = `
      <div class="flex items-center justify-between mb-2.5">
        <div>
          <h3 class="text-base font-bold">🧪 婴儿姿态看护（待测项目）</h3>
          <p class="text-[11px]" style="color: var(--text-secondary);">目标检测 · 远距画面放大 · 闹钟与手表访问</p>
        </div>
        <button onclick="PoseWatchLab.closeConsole()" class="px-3 py-1 rounded-full text-xs font-semibold chip-base">收起</button>
      </div>

      <div id="poseLabBanner" class="rounded-2xl px-3.5 py-2.5 text-xs font-bold mb-3" style="background-color:#112A28; color:#6EE7B7;">
        🌙 姿态看护待命 · 频变达标即刻唤醒闹钟与手表
      </div>

      <div class="relative rounded-2xl overflow-hidden bg-slate-950 mb-2.5 border" style="border-color: var(--border-card); height: 210px;">
        <canvas id="poseLabCanvas" width="480" height="300" class="w-full h-full object-contain cursor-grab"></canvas>
        <div id="poseLabHint" class="absolute inset-0 flex items-center justify-center text-xs text-slate-400 px-6 text-center pointer-events-none">
          正在请求摄像头画面...
        </div>
      </div>

      <div class="mb-3 p-3 rounded-2xl border" style="background-color: var(--bg-surface); border-color: var(--border-card);">
        <div class="flex items-center justify-between mb-1.5">
          <span class="text-xs font-bold" id="poseLabZoomLabel">🔍 远距画面放大：${lab.zoomRatio.toFixed(1)}x</span>
          <span class="text-[10.5px]" style="color: var(--text-muted);">拖动画面可移动放大区域</span>
        </div>
        <input id="poseLabZoomInput" type="range" min="1" max="8" step="0.1" value="${lab.zoomRatio}"
          oninput="PoseWatchLab.setZoom(this.value)" class="w-full accent-orange-500 mb-2" />
        <div class="flex gap-1.5 overflow-x-auto no-scrollbar">
          ${[1, 2, 3.5, 5, 8].map(z => `<button onclick="PoseWatchLab.setZoom(${z})" class="px-2.5 py-1 rounded-full text-[11px] font-semibold chip-base">${z}x</button>`).join('')}
          <button onclick="PoseWatchLab.flipCamera()" class="px-2.5 py-1 rounded-full text-[11px] font-semibold chip-base">切换前后镜头</button>
        </div>
      </div>

      <div class="mb-3 p-3 rounded-2xl border space-y-1.5" style="background-color: var(--bg-surface); border-color: var(--border-card);">
        <div class="flex items-center justify-between text-xs">
          <span style="color: var(--text-secondary);">当前识别姿态</span>
          <span class="font-bold" id="poseLabCurrentPose">${lab.poseLabel}</span>
        </div>
        <div class="flex items-center justify-between text-xs">
          <span style="color: var(--text-secondary);">窗口内姿态变化</span>
          <span class="font-bold" style="color: var(--brand-primary);" id="poseLabChangeCnt">0 / ${lab.threshold} 次 (${lab.windowSec}s)</span>
        </div>
        <div class="flex items-center justify-between text-xs pt-1">
          <span style="color: var(--text-secondary);">唤醒变化次数阈值</span>
          <div class="flex items-center gap-2">
            <button onclick="PoseWatchLab.stepThreshold(-1)" class="w-7 h-7 rounded-full chip-base font-bold">-</button>
            <span class="font-bold" id="poseLabThreshVal">${lab.threshold} 次</span>
            <button onclick="PoseWatchLab.stepThreshold(1)" class="w-7 h-7 rounded-full chip-base font-bold">+</button>
          </div>
        </div>
        <p class="text-[11px] pt-1" style="color: var(--text-muted);" id="poseLabHistory">最近轨迹：暂无明显姿态切换</p>
      </div>

      <div class="mb-3 p-3 rounded-2xl border space-y-2" style="background-color: var(--bg-surface); border-color: var(--border-card);">
        <div class="text-xs font-bold">① 唤醒闹钟控制 &amp; ② 手表端访问</div>
        <div class="grid grid-cols-2 gap-2">
          <button onclick="PoseWatchLab.dismissAlarm()" class="h-10 rounded-xl text-xs font-bold text-white" style="background-color:#E11D48;">🔕 关闭闹钟</button>
          <button onclick="PoseWatchLab.snoozeAlarm()" class="h-10 rounded-xl text-xs font-bold chip-base">💤 贪睡 3 分钟</button>
          <button onclick="PoseWatchLab.simStep()" class="h-9 rounded-xl text-[11px] font-semibold chip-base">➕ 模拟 1 次姿态变化</button>
          <button onclick="PoseWatchLab.testAlarm()" class="h-9 rounded-xl text-[11px] font-semibold chip-base">🔔 测试闹钟+手表</button>
        </div>
        <div class="pt-1 border-t text-[11px]" style="border-color: var(--border-card); color: var(--text-secondary);">
          ⌚ 手表模式入口：<code class="px-1.5 py-0.5 rounded bg-black/10 select-all">${watchUrl}</code>
          <div class="mt-2">
            <a href="?mode=watch" target="_blank" class="inline-flex items-center justify-center w-full h-9 rounded-xl text-xs font-bold text-white" style="background-color: var(--brand-primary);">
              ⌚ 打开手表端表盘视图 (Watch Mode)
            </a>
          </div>
        </div>
      </div>
    `;
    overlay.classList.remove('hidden');
    overlay.classList.add('flex');
    requestAnimationFrame(() => overlay.classList.add('sheet-open'));

    const canvas = document.getElementById('poseLabCanvas');
    if (canvas) bindCanvasDrag(canvas);
    startCamera();
    updateConsoleDom();
  }

  function closeConsole() {
    stopCamera();
    if (window.App && window.App.closeSheet) window.App.closeSheet();
  }

  function bootWatchFaceModeIfRequested() {
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
            姿态变化：<strong id="wCnt" style="color:#FCD34D;font-size:16px;">0 / 5</strong>
            <div id="wZoom" style="font-size:10.5px;color:#94A3B8;margin-top:2px;">画面放大 2.0x</div>
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
      document.getElementById('wPose').textContent = '当前姿态：' + (d.poseLabel || '待命');
      document.getElementById('wCnt').textContent = `${d.changeCount || 0} / ${d.threshold || 5} 次`;
      document.getElementById('wZoom').textContent = `画面放大 ${Number(d.zoomRatio || 1).toFixed(1)}x`;
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
        updateConsoleDom();
      }
    };
  }

  window.PoseWatchLab = {
    toggleHiddenEntry() {
      const card = document.getElementById('poseLabWebHiddenCard');
      if (card) card.classList.toggle('hidden');
    },
    openConsole,
    closeConsole,
    setZoom(val) {
      lab.zoomRatio = Math.min(8.0, Math.max(1.0, Number(val) || 1.0));
      if (lab.zoomRatio <= 1.05) { lab.panX = 0; lab.panY = 0; }
      const input = document.getElementById('poseLabZoomInput');
      if (input) input.value = lab.zoomRatio;
      broadcastSnapshot();
      updateConsoleDom();
    },
    flipCamera() {
      lab.facingMode = lab.facingMode === 'environment' ? 'user' : 'environment';
      startCamera();
    },
    stepThreshold(delta) {
      lab.threshold = Math.min(12, Math.max(2, lab.threshold + delta));
      const el = document.getElementById('poseLabThreshVal');
      if (el) el.textContent = `${lab.threshold} 次`;
      broadcastSnapshot();
      updateConsoleDom();
    },
    simStep() {
      ensureAudio();
      const order = ['supine_calm', 'side_left', 'limb_active', 'side_right', 'prone_head_up'];
      const next = order[(order.indexOf(lab.poseCode) + 1) % order.length];
      lastChangeAt = 0;
      recordPoseFrame({ detected: true, pose: next, motionScore: 0.35, box: { left: 0.25, top: 0.2, right: 0.75, bottom: 0.8 } });
      broadcastSnapshot();
      updateConsoleDom();
    },
    testAlarm() {
      ensureAudio();
      const now = Date.now();
      lab.changeTimestamps = Array.from({ length: lab.threshold }, (_, i) => now - i * 400);
      lab.poseCode = 'limb_active';
      lab.poseLabel = POSE_LABELS.limb_active;
      triggerWakeNow('手动测试唤醒闹钟+手表');
    },
    dismissAlarm,
    snoozeAlarm,
  };

  document.addEventListener('DOMContentLoaded', bootWatchFaceModeIfRequested);
})();
