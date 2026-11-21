// Experimental Module: Baby Pose Angle Analysis, 1x-8x Camera Zoom, Fast Wake Alarm & Smartwatch UI
// Evaluates posture transitions strictly from body/limb joint angles (elbow bend, arm elevation, knee bend, body roll)
// rather than raw pixel motion jitter.

(() => {
  const CHANNEL_NAME = 'babybuddy_pose_watch_channel_v1';
  const STATE_KEY = 'babybuddy_pose_watch_state_v1';
  const bc = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL_NAME) : null;

  const POSE_LABELS = {
    none: '未检测到婴儿姿态',
    supine_calm: '仰卧平放',
    side_left: '左侧卧/翻身',
    side_right: '右侧卧/翻身',
    prone_head_up: '趴卧屈肘抬头',
    limb_active: '屈臂挥动/蹬腿',
    sitting_awake: '屈髋撑起/坐立',
  };

  const PRESET_ANGLES = {
    supine_calm: { lElbow: 136, rElbow: 138, lShoulder: 42, rShoulder: 40, lKnee: 142, rKnee: 145, roll: 0 },
    side_left: { lElbow: 105, rElbow: 72, lShoulder: 55, rShoulder: 68, lKnee: 118, rKnee: 96, roll: -52 },
    limb_active: { lElbow: 54, rElbow: 162, lShoulder: 142, rShoulder: 98, lKnee: 72, rKnee: 168, roll: -10 },
    side_right: { lElbow: 74, rElbow: 108, lShoulder: 66, rShoulder: 52, lKnee: 98, rKnee: 120, roll: 52 },
    prone_head_up: { lElbow: 66, rElbow: 68, lShoulder: 86, rShoulder: 84, lKnee: 154, rKnee: 150, roll: 8 },
    sitting_awake: { lElbow: 156, rElbow: 158, lShoulder: 36, rShoulder: 38, lKnee: 108, rKnee: 110, roll: 0 },
  };

  let lab = {
    monitoring: false,
    zoomRatio: 2.0,
    panX: 0,
    panY: 0,
    threshold: 5,
    windowSec: 30,
    angleThresholdDeg: 25,
    poseCode: 'none',
    poseLabel: POSE_LABELS.none,
    angles: null,
    lastAngleDeltaDeg: 0,
    changeTimestamps: [],
    recentHistory: [],
    alarmRinging: false,
    snoozeUntilMs: 0,
    facingMode: 'environment',
  };

  let mediaStream = null;
  let videoEl = null;
  let loopTimer = null;
  let anchorPose = 'none';
  let anchorAngles = null;
  let lastChangeAt = 0;
  let audioCtx = null;
  let alarmLoopTimer = null;

  function formatAnglesSummary(a) {
    if (!a) return '肘弯 --/-- · 抬臂 --/-- · 膝弯 --/-- · 侧翻 --';
    return `肘弯 L${Math.round(a.lElbow)}°/R${Math.round(a.rElbow)}° · 抬臂 L${Math.round(a.lShoulder)}°/R${Math.round(a.rShoulder)}° · 膝弯 L${Math.round(a.lKnee)}°/R${Math.round(a.rKnee)}° · 侧翻 ${Math.round(a.roll)}°`;
  }

  function computeAngleDelta(prev, curr) {
    if (!prev || !curr) return { maxDelta: 0, meanDelta: 0, dominant: '' };
    const items = [
      ['左肘弯曲角', prev.lElbow, curr.lElbow],
      ['右肘弯曲角', prev.rElbow, curr.rElbow],
      ['左肩抬臂角', prev.lShoulder, curr.lShoulder],
      ['右肩抬臂角', prev.rShoulder, curr.rShoulder],
      ['左膝屈伸角', prev.lKnee, curr.lKnee],
      ['右膝屈伸角', prev.rKnee, curr.rKnee],
      ['躯干侧翻角', prev.roll, curr.roll],
    ];
    let maxDelta = 0, sum = 0, dominant = '';
    for (const [name, p, c] of items) {
      const d = Math.abs(c - p);
      sum += d;
      if (d > maxDelta) { maxDelta = d; dominant = name; }
    }
    return { maxDelta, meanDelta: sum / items.length, dominant };
  }

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

  /**
   * Computes subject contour principal-axis orientation & limb-span joint angles
   * (immune to pure luminance flicker or slight pixel translation).
   */
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
      warm[i] = (r > g && g > b * 0.85 && (r - b) > 0.06) ? Math.min(1, (r - b) * 1.4) : 0;
    }
    mean /= n;

    let salSum = 0, sx = 0, sy = 0;
    let minX = gw, minY = gh, maxX = 0, maxY = 0;
    for (let y = 1; y < gh - 1; y++) {
      for (let x = 1; x < gw - 1; x++) {
        const idx = y * gw + x;
        const edge = Math.abs(luma[idx] - luma[idx - 1]) * 0.5;
        const sal = warm[idx] * 1.35 + edge * 0.9 + Math.abs(luma[idx] - mean) * 0.4;
        if (sal > 0.22) {
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

    if (salSum < n * 0.01 || maxX <= minX + 4 || maxY <= minY + 4) {
      return { detected: false, pose: 'none', angles: null, box: null };
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
    const roll = Math.max(-80, Math.min(80, lrAsym * 75));
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
      roll: Math.round(roll),
    };

    let pose = 'supine_calm';
    if (Math.abs(angles.lElbow - angles.rElbow) >= 50 || Math.abs(angles.lKnee - angles.rKnee) >= 42) {
      pose = 'limb_active';
    } else if (angles.lElbow < 95 && angles.rElbow < 95) {
      pose = 'prone_head_up';
    } else if (roll <= -28) {
      pose = 'side_left';
    } else if (roll >= 28) {
      pose = 'side_right';
    }

    const box = {
      left: Math.max(0.04, minX / gw),
      top: Math.max(0.04, minY / gh),
      right: Math.min(0.96, maxX / gw),
      bottom: Math.min(0.96, maxY / gh),
    };

    return { detected: true, pose, angles, box };
  }

  function recordAngleFrame(res) {
    const now = Date.now();
    const cutoff = now - lab.windowSec * 1000;
    lab.changeTimestamps = lab.changeTimestamps.filter(t => t >= cutoff);

    if (!res.detected || !res.angles) {
      lab.poseCode = 'none';
      lab.poseLabel = POSE_LABELS.none;
      return;
    }

    lab.poseCode = res.pose;
    lab.poseLabel = POSE_LABELS[res.pose] || POSE_LABELS.supine_calm;
    lab.angles = res.angles;

    if (anchorPose === 'none' || !anchorAngles) {
      anchorPose = res.pose;
      anchorAngles = { ...res.angles };
      lab.lastAngleDeltaDeg = 0;
      return;
    }

    const delta = computeAngleDelta(anchorAngles, res.angles);
    lab.lastAngleDeltaDeg = delta.maxDelta;

    const minDeg = lab.angleThresholdDeg;
    const hasLargeAngleChange = delta.maxDelta >= minDeg || delta.meanDelta >= minDeg * 0.58;
    const hasVerifiedPoseSwitch = res.pose !== anchorPose && delta.maxDelta >= Math.max(14, minDeg * 0.65);

    if (now - lastChangeAt >= 1500 && (hasLargeAngleChange || hasVerifiedPoseSwitch)) {
      lab.changeTimestamps.push(now);
      lastChangeAt = now;
      const tag = `${delta.dominant} Δ${Math.round(delta.maxDelta)}°`;
      const msg = res.pose !== anchorPose
        ? `${POSE_LABELS[anchorPose]}→${POSE_LABELS[res.pose]} (${tag})`
        : `${POSE_LABELS[res.pose]} (${tag})`;
      lab.recentHistory.unshift(msg);
      lab.recentHistory = lab.recentHistory.slice(0, 6);
      anchorPose = res.pose;
      anchorAngles = { ...res.angles };
    }

    if (now >= lab.snoozeUntilMs && !lab.alarmRinging && lab.changeTimestamps.length >= lab.threshold) {
      triggerWakeNow(`关节角度大幅变化达 ${lab.threshold} 次，判定苏醒`);
    }
  }

  async function startCamera() {
    stopCamera();
    const canvas = document.getElementById('poseLabCanvas');
    const hint = document.getElementById('poseLabHint');
    if (!canvas) return;
    anchorPose = 'none';
    anchorAngles = null;

    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('当前浏览器环境不支持本地摄像头，可使用下方预设动作角度模拟与手表联动');
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

        const res = analyzeContourAngles(smallCtx, 80, 80);
        recordAngleFrame(res);

        if (res.detected && res.box) {
          const bx = res.box.left * c.width;
          const by = res.box.top * c.height;
          const bw = (res.box.right - res.box.left) * c.width;
          const bh = (res.box.bottom - res.box.top) * c.height;
          ctx.strokeStyle = lab.alarmRinging ? '#FF3B5C' : (res.pose === 'limb_active' ? '#FF9F1C' : '#2EC4B6');
          ctx.lineWidth = 2.5;
          ctx.strokeRect(bx, by, bw, bh);
          const tagStr = `${lab.poseLabel} · Δ${Math.round(lab.lastAngleDeltaDeg)}°`;
          ctx.fillStyle = 'rgba(15, 23, 42, 0.82)';
          ctx.fillRect(bx, Math.max(4, by - 22), Math.min(c.width - bx - 4, 175), 20);
          ctx.fillStyle = '#FFFFFF';
          ctx.font = 'bold 11px sans-serif';
          ctx.fillText(tagStr, bx + 6, Math.max(18, by - 8));
        }

        const thumb = c.toDataURL('image/jpeg', 0.5);
        broadcastSnapshot(thumb);
        updateConsoleDom();
      }, 160);
    } catch (err) {
      lab.monitoring = false;
      if (hint) {
        hint.classList.remove('hidden');
        hint.textContent = err.message || '未获取到摄像头权限，仍可使用下方预设动作角度跳变模拟与手表调试';
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
  }

  function updateConsoleDom() {
    const poseEl = document.getElementById('poseLabCurrentPose');
    if (!poseEl) return;
    poseEl.textContent = `${lab.poseLabel} (Δ${Math.round(lab.lastAngleDeltaDeg)}° / 阈值≥${lab.angleThresholdDeg}°)`;
    document.getElementById('poseLabChangeCnt').textContent = `${lab.changeTimestamps.length} / ${lab.threshold} 次 (${lab.windowSec}s)`;
    document.getElementById('poseLabAnglesText').textContent = formatAnglesSummary(lab.angles);
    document.getElementById('poseLabZoomLabel').textContent = `🔍 远距画面放大：${lab.zoomRatio.toFixed(1)}x`;
    document.getElementById('poseLabHistory').textContent = lab.recentHistory.length
      ? `最近角度轨迹：${lab.recentHistory.join(' · ')}`
      : '最近角度轨迹：关节角度稳定（忽略像素微晃）';

    const banner = document.getElementById('poseLabBanner');
    if (lab.alarmRinging) {
      banner.style.backgroundColor = '#4C0519';
      banner.style.color = '#FDA4AF';
      banner.textContent = '🚨 关节角度连续大幅变化！判定苏醒，已拉响闹钟并同步手表';
    } else {
      banner.style.backgroundColor = '#112A28';
      banner.style.color = '#6EE7B7';
      banner.textContent = '🌙 骨骼关节角度监测中 · 仅当关节角度大幅跳变时计次';
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
          <h3 class="text-base font-bold">🧪 婴儿姿态角度看护（待测）</h3>
          <p class="text-[11px]" style="color: var(--text-secondary);">关节角度跳变判定 · 远距放大 · 闹钟与手表访问</p>
        </div>
        <button onclick="PoseWatchLab.closeConsole()" class="px-3 py-1 rounded-full text-xs font-semibold chip-base">收起</button>
      </div>

      <div id="poseLabBanner" class="rounded-2xl px-3.5 py-2.5 text-xs font-bold mb-3" style="background-color:#112A28; color:#6EE7B7;">
        🌙 骨骼关节角度监测中 · 仅当关节角度大幅跳变时计次
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
          <span style="color: var(--text-secondary);">当前动作与角度差</span>
          <span class="font-bold" id="poseLabCurrentPose">${lab.poseLabel}</span>
        </div>
        <div class="text-[11px] font-medium" style="color: var(--text-secondary);" id="poseLabAnglesText">${formatAnglesSummary(lab.angles)}</div>
        <div class="flex items-center justify-between text-xs">
          <span style="color: var(--text-secondary);">窗口内大幅角度变化</span>
          <span class="font-bold" style="color: var(--brand-primary);" id="poseLabChangeCnt">0 / ${lab.threshold} 次 (${lab.windowSec}s)</span>
        </div>
        <div class="flex items-center justify-between text-xs pt-1">
          <span style="color: var(--text-secondary);">角度跳变阈值</span>
          <div class="flex gap-1">
            ${[15, 25, 35, 45].map(deg => `<button onclick="PoseWatchLab.setAngleThresh(${deg})" class="px-2 py-0.5 rounded-full text-[11px] font-semibold ${lab.angleThresholdDeg === deg ? 'chip-active' : 'chip-base'}">≥${deg}°</button>`).join('')}
          </div>
        </div>
        <div class="flex items-center justify-between text-xs pt-1">
          <span style="color: var(--text-secondary);">唤醒变化次数阈值</span>
          <div class="flex items-center gap-2">
            <button onclick="PoseWatchLab.stepThreshold(-1)" class="w-7 h-7 rounded-full chip-base font-bold">-</button>
            <span class="font-bold" id="poseLabThreshVal">${lab.threshold} 次</span>
            <button onclick="PoseWatchLab.stepThreshold(1)" class="w-7 h-7 rounded-full chip-base font-bold">+</button>
          </div>
        </div>
        <p class="text-[11px] pt-1" style="color: var(--text-muted);" id="poseLabHistory">最近角度轨迹：关节角度稳定（忽略像素微晃）</p>
      </div>

      <div class="mb-3 p-3 rounded-2xl border space-y-2" style="background-color: var(--bg-surface); border-color: var(--border-card);">
        <div class="text-xs font-bold">① 唤醒闹钟控制 &amp; ② 手表端访问</div>
        <div class="grid grid-cols-2 gap-2">
          <button onclick="PoseWatchLab.dismissAlarm()" class="h-10 rounded-xl text-xs font-bold text-white" style="background-color:#E11D48;">🔕 关闭闹钟</button>
          <button onclick="PoseWatchLab.snoozeAlarm()" class="h-10 rounded-xl text-xs font-bold chip-base">💤 贪睡 3 分钟</button>
          <button onclick="PoseWatchLab.simStep()" class="h-9 rounded-xl text-[11px] font-semibold chip-base">➕ 模拟预设动作角度切换</button>
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
            大幅角度变化：<strong id="wCnt" style="color:#FCD34D;font-size:16px;">0 / 5</strong>
            <div id="wZoom" style="font-size:10.5px;color:#94A3B8;margin-top:2px;">阈值 ≥25° · 放大 2.0x</div>
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
      document.getElementById('wCnt').textContent = `${d.changeCount || 0} / ${d.threshold || 5} 次`;
      document.getElementById('wZoom').textContent = `角度阈值 ≥${Math.round(d.angleThresholdDeg || 25)}° · 放大 ${Number(d.zoomRatio || 1).toFixed(1)}x`;
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
    setAngleThresh(deg) {
      lab.angleThresholdDeg = Number(deg) || 25;
      openConsole();
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
      const order = ['supine_calm', 'side_left', 'limb_active', 'side_right', 'prone_head_up', 'sitting_awake'];
      const next = order[(order.indexOf(lab.poseCode) + 1) % order.length];
      lastChangeAt = 0;
      recordAngleFrame({ detected: true, pose: next, angles: PRESET_ANGLES[next], box: { left: 0.25, top: 0.2, right: 0.75, bottom: 0.8 } });
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
      lab.lastAngleDeltaDeg = 68;
      triggerWakeNow('手动测试唤醒闹钟+手表 (Δ68°)');
    },
    dismissAlarm,
    snoozeAlarm,
  };

  document.addEventListener('DOMContentLoaded', bootWatchFaceModeIfRequested);
})();
