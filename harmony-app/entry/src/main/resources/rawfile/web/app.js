// Baby Buddy Local Offline Mobile Web App
// Warm Nursery UI + Full Offline Persistence + 12 Care/Health Types + WHO Growth Curves

(() => {
  const STORAGE_KEY = 'babybuddy_mobile_local_v1';

  const CATEGORIES = [
    { id: 'feeding', label: '喂养', group: 'care', icon: 'milk', bg: 'var(--cat-feeding-bg)', fg: 'var(--cat-feeding-fg)', hasTimer: true },
    { id: 'diaper', label: '换尿布', group: 'care', icon: 'baby', bg: 'var(--cat-diaper-bg)', fg: 'var(--cat-diaper-fg)', hasTimer: false },
    { id: 'sleep', label: '睡眠', group: 'care', icon: 'moon', bg: 'var(--cat-sleep-bg)', fg: 'var(--cat-sleep-fg)', hasTimer: true },
    { id: 'pumping', label: '吸奶', group: 'care', icon: 'droplet', bg: 'var(--cat-pumping-bg)', fg: 'var(--cat-pumping-fg)', hasTimer: true },
    { id: 'tummy', label: '趴卧', group: 'care', icon: 'sparkles', bg: 'var(--cat-tummy-bg)', fg: 'var(--cat-tummy-fg)', hasTimer: true },
    { id: 'note', label: '备注', group: 'care', icon: 'sticky-note', bg: 'var(--cat-notes-bg)', fg: 'var(--cat-notes-fg)', hasTimer: false },

    { id: 'medication', label: '用药', group: 'health', icon: 'pill', bg: 'var(--cat-meds-bg)', fg: 'var(--cat-meds-fg)' },
    { id: 'temperature', label: '体温', group: 'health', icon: 'thermometer', bg: 'var(--cat-temp-bg)', fg: 'var(--cat-temp-fg)' },
    { id: 'weight', label: '体重', group: 'health', icon: 'scale', bg: 'var(--cat-weight-bg)', fg: 'var(--cat-weight-fg)' },
    { id: 'height', label: '身高', group: 'health', icon: 'ruler', bg: 'var(--cat-height-bg)', fg: 'var(--cat-height-fg)' },
    { id: 'head', label: '头围', group: 'health', icon: 'circle-dot', bg: 'var(--cat-head-bg)', fg: 'var(--cat-head-fg)' },
    { id: 'bmi', label: 'BMI', group: 'health', icon: 'activity', bg: 'var(--cat-bmi-bg)', fg: 'var(--cat-bmi-fg)' },
  ];

  const STOOL_COLORS = [
    { name: '黑色', hex: '#2B2B2B' },
    { name: '棕色', hex: '#C2781E' },
    { name: '灰色', hex: '#8D9399' },
    { name: '绿色', hex: '#3A9E52' },
    { name: '橙色', hex: '#EA832B' },
    { name: '红色', hex: '#D64545' },
    { name: '白色', hex: '#F2EFE9' },
    { name: '黄色', hex: '#E6C229' },
  ];

  const FEEDING_TYPES = ['母乳', '配方奶', '强化母乳', '辅食'];
  const FEEDING_METHODS = ['奶瓶', '左侧亲喂', '右侧亲喂', '双侧亲喂', '家长喂食', '自主进食'];
  const MED_UNITS = ['mg (毫克)', 'ml (毫升)', '片/粒', '滴'];
  const MED_INTERVALS = [
    { label: '无', hours: 0 },
    { label: '4h', hours: 4 },
    { label: '6h', hours: 6 },
    { label: '8h', hours: 8 },
    { label: '12h', hours: 12 },
    { label: '24h', hours: 24 },
  ];

  function uid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  function createSeedState() {
    const now = Date.now();
    const h = 3600 * 1000;
    const m = 60 * 1000;
    const day = 24 * h;

    const child1Id = 'c_nuomi';
    const child2Id = 'c_tuanzi';

    const birth1 = new Date(now - 102 * day).toISOString().slice(0, 10);
    const due1 = new Date(now - 65 * day).toISOString().slice(0, 10); // Premature corrected age ~2m5d
    const birth2 = new Date(now - 102 * day).toISOString().slice(0, 10);

    const tags = [
      { id: 't_spurt', name: '猛涨期', color: '#E07A5F' },
      { id: 't_motor', name: '大运动发育', color: '#469E70' },
      { id: 't_vit', name: '每日营养', color: '#D84B78' },
      { id: 't_check', name: '儿保体检', color: '#5B7FD1' },
    ];

    const entries = [
      // Today
      { id: uid(), childId: child1Id, type: 'feeding', start: now - 80 * m, end: now - 62 * m, amount: 120, feedingType: '配方奶', feedingMethod: '奶瓶', notes: '喝得很香，拍嗝顺利', tags: ['t_spurt'] },
      { id: uid(), childId: child1Id, type: 'diaper', start: now - 38 * m, end: now - 38 * m, wet: true, solid: true, stoolColor: '黄色', diaperAmount: 2, notes: '黄金糊状便便', tags: [] },
      { id: uid(), childId: child1Id, type: 'sleep', start: now - 230 * m, end: now - 52 * m, isNap: true, notes: '上午小睡安稳', tags: [] },
      { id: uid(), childId: child1Id, type: 'tummy', start: now - 160 * m, end: now - 145 * m, milestone: '抬头 45° 很稳', notes: '趴卧互动笑得很开心', tags: ['t_motor'] },
      { id: uid(), childId: child1Id, type: 'pumping', start: now - 260 * m, end: now - 242 * m, amount: 140, notes: '储奶袋冷藏', tags: [] },
      { id: uid(), childId: child1Id, type: 'medication', start: now - 4 * h, end: now - 4 * h, medName: '维生素 D3', dosage: 1, medUnit: '滴', intervalHours: 24, notes: '随晨奶服用', tags: ['t_vit'] },
      { id: uid(), childId: child1Id, type: 'feeding', start: now - 4.2 * h, end: now - 4.0 * h, amount: 90, feedingType: '母乳', feedingMethod: '左侧亲喂', notes: '', tags: [] },
      { id: uid(), childId: child1Id, type: 'feeding', start: now - 7.1 * h, end: now - 6.8 * h, amount: 110, feedingType: '母乳', feedingMethod: '右侧亲喂', notes: '', tags: [] },
      { id: uid(), childId: child1Id, type: 'sleep', start: now - 13 * h, end: now - 6.5 * h, isNap: false, notes: '夜间长觉', tags: [] },
      // Growth measurements over past 2 months
      { id: uid(), childId: child1Id, type: 'weight', start: now - 60 * day, end: now - 60 * day, value: 4.15, notes: '出生后 1 个月复查', tags: ['t_check'] },
      { id: uid(), childId: child1Id, type: 'weight', start: now - 30 * day, end: now - 30 * day, value: 5.38, notes: '满 2 月龄体检', tags: ['t_check'] },
      { id: uid(), childId: child1Id, type: 'weight', start: now - 7 * day, end: now - 7 * day, value: 6.13, notes: '', tags: [] },
      { id: uid(), childId: child1Id, type: 'weight', start: now - 1 * day, end: now - 1 * day, value: 6.45, notes: '体重追赶良好', tags: ['t_check'] },
      { id: uid(), childId: child1Id, type: 'height', start: now - 30 * day, end: now - 30 * day, value: 57.5, notes: '', tags: [] },
      { id: uid(), childId: child1Id, type: 'height', start: now - 1 * day, end: now - 1 * day, value: 61.2, notes: '', tags: ['t_check'] },
      { id: uid(), childId: child1Id, type: 'head', start: now - 1 * day, end: now - 1 * day, value: 40.3, notes: '', tags: [] },
      { id: uid(), childId: child1Id, type: 'temperature', start: now - 1 * day, end: now - 1 * day, value: 36.6, notes: '体温正常', tags: [] },
    ];

    // Past 6 days feeding/sleep/diaper history for rich stats charts
    for (let d = 1; d <= 6; d++) {
      const base = now - d * day;
      entries.push(
        { id: uid(), childId: child1Id, type: 'feeding', start: base - 2 * h, end: base - 1.7 * h, amount: 120 + (d % 3) * 15, feedingType: '配方奶', feedingMethod: '奶瓶', notes: '', tags: [] },
        { id: uid(), childId: child1Id, type: 'feeding', start: base - 5 * h, end: base - 4.7 * h, amount: 110, feedingType: '母乳', feedingMethod: d % 2 === 0 ? '左侧亲喂' : '右侧亲喂', notes: '', tags: [] },
        { id: uid(), childId: child1Id, type: 'feeding', start: base - 9 * h, end: base - 8.7 * h, amount: 130, feedingType: '母乳', feedingMethod: '双侧亲喂', notes: '', tags: [] },
        { id: uid(), childId: child1Id, type: 'feeding', start: base - 13 * h, end: base - 12.7 * h, amount: 125, feedingType: '配方奶', feedingMethod: '奶瓶', notes: '', tags: [] },
        { id: uid(), childId: child1Id, type: 'sleep', start: base - 12 * h, end: base - 4 * h, isNap: false, notes: '夜间睡眠', tags: [] },
        { id: uid(), childId: child1Id, type: 'sleep', start: base + 1 * h, end: base + 2.5 * h, isNap: true, notes: '午睡', tags: [] },
        { id: uid(), childId: child1Id, type: 'diaper', start: base - 1 * h, end: base - 1 * h, wet: true, solid: false, stoolColor: '', diaperAmount: 1, notes: '', tags: [] },
        { id: uid(), childId: child1Id, type: 'diaper', start: base - 6 * h, end: base - 6 * h, wet: true, solid: true, stoolColor: d % 2 === 0 ? '黄色' : '绿色', diaperAmount: 2, notes: '', tags: [] }
      );
    }

    // Second child sample entry
    entries.push(
      { id: uid(), childId: child2Id, type: 'feeding', start: now - 50 * m, end: now - 35 * m, amount: 130, feedingType: '配方奶', feedingMethod: '奶瓶', notes: '', tags: [] }
    );

    return {
      children: [
        { id: child1Id, firstName: '糯米', lastName: '', birthDate: birth1, birthTime: '08:30', dueDate: due1, sex: 'girl', avatarEmoji: '👶🏻' },
        { id: child2Id, firstName: '团子', lastName: '', birthDate: birth2, birthTime: '08:34', dueDate: null, sex: 'boy', avatarEmoji: '🧸' },
      ],
      activeChildId: child1Id,
      entries,
      timers: [
        { id: 'tm_live_feed', childId: child1Id, type: 'feeding', title: '左侧亲喂', start: now - 758 * 1000 },
      ],
      tags,
      settings: {
        darkMode: false,
        napStartHour: 6,
        napEndHour: 18,
        hideEmptySummary: false,
        autoTrimOverlaps: true,
        medNotifications: true,
      }
    };
  }

  let state = loadState();
  let currentPage = 'today';
  let activeCategoryFilter = 'all';
  let activeTagFilter = 'all';
  let statsRangeDays = 7;
  let whoMetric = 'weight';
  let timerTickHandle = null;

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && Array.isArray(parsed.children)) return parsed;
      }
    } catch (e) {
      console.warn('Load state failed, using seed:', e);
    }
    const seed = createSeedState();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(seed));
    return seed;
  }

  function saveState() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function getActiveChild() {
    return state.children.find(c => c.id === state.activeChildId) || state.children[0] || null;
  }

  function formatAge(dateStr) {
    if (!dateStr) return '新生儿';
    const d = new Date(dateStr + 'T00:00:00');
    const diffDays = Math.max(0, Math.floor((Date.now() - d.getTime()) / (24 * 3600 * 1000)));
    if (diffDays < 30) return `出生 ${diffDays} 天`;
    const months = Math.floor(diffDays / 30.4375);
    const days = Math.round(diffDays - months * 30.4375);
    if (months >= 12) {
      const yrs = Math.floor(months / 12);
      const remM = months % 12;
      return `${yrs} 岁 ${remM} 个月`;
    }
    return `${months} 个月 ${Math.max(0, days)} 天`;
  }

  function getAgeMonths(child, useCorrected = true) {
    if (!child) return 3;
    const refDate = (useCorrected && child.dueDate) ? child.dueDate : child.birthDate;
    if (!refDate) return 3;
    const diffMs = Date.now() - new Date(refDate + 'T00:00:00').getTime();
    return Math.max(0, diffMs / (30.4375 * 24 * 3600 * 1000));
  }

  function formatElapsed(ms) {
    if (ms < 0) ms = 0;
    const mins = Math.floor(ms / 60000);
    if (mins < 1) return '刚刚';
    if (mins < 60) return `${mins} 分钟前`;
    const hrs = Math.floor(mins / 60);
    const remM = mins % 60;
    if (hrs < 24) return `${hrs} 小时 ${remM} 分前`;
    const days = Math.floor(hrs / 24);
    return `${days} 天前`;
  }

  function formatHMS(ms) {
    const totalSec = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  function formatTime(ts) {
    const d = new Date(ts);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  function formatDateHeader(ts) {
    const d = new Date(ts);
    const today = new Date();
    const yesterday = new Date(Date.now() - 86400000);
    const ds = d.toDateString();
    if (ds === today.toDateString()) return `今天 · ${d.getMonth() + 1}月${d.getDate()}日`;
    if (ds === yesterday.toDateString()) return `昨天 · ${d.getMonth() + 1}月${d.getDate()}日`;
    return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
  }

  function showToast(msg) {
    const el = document.getElementById('toastBanner');
    el.textContent = msg;
    el.style.opacity = '1';
    clearTimeout(el._timer);
    el._timer = setTimeout(() => { el.style.opacity = '0'; }, 2400);
  }

  function applyTheme() {
    const html = document.documentElement;
    const icon = document.getElementById('themeToggleIcon');
    if (state.settings.darkMode) {
      html.classList.add('dark');
      if (icon) icon.setAttribute('data-lucide', 'sun');
    } else {
      html.classList.remove('dark');
      if (icon) icon.setAttribute('data-lucide', 'moon');
    }
    if (window.HarmonyBridge && typeof window.HarmonyBridge.syncThemeMode === 'function') {
      try {
        window.HarmonyBridge.syncThemeMode(Boolean(state.settings.darkMode));
      } catch (_) {}
    }
    if (window.lucide) lucide.createIcons();
  }

  function renderProfileHeader() {
    const child = getActiveChild();
    const card = document.getElementById('babyProfileCard');
    if (!child) {
      document.getElementById('activeBabyName').textContent = '尚未添加宝宝';
      document.getElementById('activeBabyCorrectedAge').textContent = '点击下方按钮创建第一个宝宝档案';
      document.getElementById('activeBabyAgeBadge').textContent = '纯本地离线';
      document.getElementById('activeBabySexBadge').textContent = '';
      document.getElementById('babySwitcherRow').innerHTML = `
        <button onclick="App.openBabyProfileModal(false)" class="px-3 py-1.5 rounded-full text-xs font-semibold chip-active flex items-center gap-1">
          <i data-lucide="plus" class="w-3.5 h-3.5"></i> 添加宝宝
        </button>
      `;
      if (window.lucide) lucide.createIcons();
      return;
    }

    document.getElementById('activeBabyAvatar').textContent = child.avatarEmoji || '👶🏻';
    document.getElementById('activeBabyName').textContent = `${child.firstName}${child.lastName ? ' ' + child.lastName : ''}`;
    document.getElementById('activeBabySexBadge').textContent = child.sex === 'girl' ? '女宝' : (child.sex === 'boy' ? '男宝' : '宝宝');
    document.getElementById('activeBabyAgeBadge').textContent = formatAge(child.birthDate);
    const corrEl = document.getElementById('activeBabyCorrectedAge');
    if (child.dueDate) {
      corrEl.textContent = `矫正月龄: ${formatAge(child.dueDate)} · 出生 ${child.birthDate}`;
    } else {
      corrEl.textContent = `出生日期: ${child.birthDate}${child.birthTime ? ' ' + child.birthTime : ''}`;
    }

    const chipsHtml = state.children.map(c => {
      const active = c.id === child.id;
      return `<button onclick="App.selectChild('${c.id}')" class="px-3 py-1 rounded-full text-xs font-semibold whitespace-nowrap flex items-center gap-1 ${active ? 'chip-active' : 'chip-base'}">
        <span>${c.avatarEmoji || '👶🏻'}</span><span>${c.firstName}</span>
      </button>`;
    }).join('') + `
      <button onclick="App.openBabyProfileModal(false)" class="px-3 py-1 rounded-full text-xs font-semibold whitespace-nowrap border border-dashed flex items-center gap-1" style="border-color: var(--border-active); color: var(--brand-primary);">
        <i data-lucide="plus" class="w-3 h-3"></i> 添加宝宝
      </button>
    `;
    document.getElementById('babySwitcherRow').innerHTML = chipsHtml;
    if (window.lucide) lucide.createIcons();
  }

  function getChildEntries() {
    const child = getActiveChild();
    if (!child) return [];
    return state.entries
      .filter(e => e.childId === child.id)
      .sort((a, b) => b.start - a.start);
  }

  function renderSummary() {
    const entries = getChildEntries();
    const now = Date.now();
    const startOfToday = new Date();
    startOfToday.setHours(0, 0, 0, 0);
    const todayTs = startOfToday.getTime();

    const lastFeed = entries.find(e => e.type === 'feeding');
    const lastSleep = entries.find(e => e.type === 'sleep');
    const lastDiaper = entries.find(e => e.type === 'diaper');

    document.getElementById('sumLastFeedAgo').textContent = lastFeed ? formatElapsed(now - lastFeed.start) : '暂无记录';
    document.getElementById('sumLastFeedDetail').textContent = lastFeed ? `${lastFeed.feedingMethod || lastFeed.feedingType}${lastFeed.amount ? ' · ' + lastFeed.amount + ' ml' : ''}` : '点击下方喂养磁贴记录';

    document.getElementById('sumLastSleepAgo').textContent = lastSleep ? `已清醒 ${formatElapsed(now - (lastSleep.end || lastSleep.start)).replace('前', '')}` : '暂无记录';
    document.getElementById('sumLastSleepDetail').textContent = lastSleep ? `${lastSleep.isNap ? '白天小睡' : '夜间睡眠'} ${Math.round(((lastSleep.end || lastSleep.start) - lastSleep.start) / 60000)} 分` : '—';

    document.getElementById('sumLastDiaperAgo').textContent = lastDiaper ? formatElapsed(now - lastDiaper.start) : '暂无记录';
    document.getElementById('sumLastDiaperDetail').textContent = lastDiaper ? [lastDiaper.wet ? '湿' : '', lastDiaper.solid ? '便' : '', lastDiaper.stoolColor].filter(Boolean).join(' · ') || '干净更换' : '—';

    const todayEntries = entries.filter(e => e.start >= todayTs);
    const todayFeeds = todayEntries.filter(e => e.type === 'feeding');
    const feedMl = todayFeeds.reduce((s, e) => s + (Number(e.amount) || 0), 0);
    document.getElementById('sumTodayFeed').textContent = `${todayFeeds.length} 次${feedMl > 0 ? ' · ' + feedMl + ' ml' : ''}`;

    const todaySleeps = todayEntries.filter(e => e.type === 'sleep');
    let sleepMin = 0, napMin = 0;
    todaySleeps.forEach(e => {
      const m = Math.max(0, Math.round(((e.end || e.start) - e.start) / 60000));
      sleepMin += m;
      if (e.isNap) napMin += m;
    });
    const sh = Math.floor(sleepMin / 60), sm = sleepMin % 60;
    document.getElementById('sumTodaySleep').textContent = sleepMin > 0 ? `${sh}h${sm}m (小睡${napMin}m)` : '0 分钟';

    const todayDiapers = todayEntries.filter(e => e.type === 'diaper');
    const wetCount = todayDiapers.filter(e => e.wet).length;
    const solidCount = todayDiapers.filter(e => e.solid).length;
    document.getElementById('sumTodayDiaper').textContent = `共 ${todayDiapers.length} 次 (湿${wetCount}/便${solidCount})`;

    const pumpMl = todayEntries.filter(e => e.type === 'pumping').reduce((s, e) => s + (Number(e.amount) || 0), 0);
    const tummyMin = todayEntries.filter(e => e.type === 'tummy').reduce((s, e) => s + Math.max(0, Math.round(((e.end || e.start) - e.start) / 60000)), 0);
    document.getElementById('sumPumpingTummy').textContent = `今日吸奶 ${pumpMl} ml · 趴卧练习 ${tummyMin} 分钟`;

    // Next Medication Reminder Badge
    const lastMedWithInterval = entries.find(e => e.type === 'medication' && e.intervalHours > 0);
    const medBadge = document.getElementById('sumNextMedsBadge');
    if (lastMedWithInterval) {
      const nextAt = lastMedWithInterval.start + lastMedWithInterval.intervalHours * 3600 * 1000;
      const remainMs = nextAt - now;
      medBadge.classList.remove('hidden');
      if (remainMs <= 0) {
        medBadge.textContent = `${lastMedWithInterval.medName} · 已到可服时间`;
      } else {
        const rh = Math.floor(remainMs / 3600000);
        const rm = Math.floor((remainMs % 3600000) / 60000);
        medBadge.textContent = `${lastMedWithInterval.medName} · ${rh}h${rm}m后可服`;
      }
    } else {
      medBadge.classList.add('hidden');
    }
  }

  function renderTimersStrip() {
    const child = getActiveChild();
    const strip = document.getElementById('quickTimersStrip');
    if (!child) { strip.innerHTML = ''; return; }
    const timers = state.timers.filter(t => t.childId === child.id);
    const now = Date.now();

    const timerChips = timers.map(t => {
      return `<button onclick="App.openTimerActionSheet('${t.id}')" class="px-3 py-1.5 rounded-full text-xs font-bold whitespace-nowrap flex items-center gap-1.5 border shadow-sm" style="background-color: var(--bg-card-elevated); border-color: var(--border-active); color: var(--brand-primary);">
        <span class="w-2 h-2 rounded-full animate-live-dot" style="background-color: var(--brand-primary);"></span>
        <span>${t.title || '计时中'}</span>
        <span class="font-mono tabular-nums" data-timer-id="${t.id}">${formatHMS(now - t.start)}</span>
      </button>`;
    }).join('');

    strip.innerHTML = timerChips + `
      <button onclick="App.openAddQuickTimerSheet()" class="px-3 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap border border-dashed flex items-center gap-1" style="border-color: var(--border-card); color: var(--text-secondary); background-color: var(--bg-card);">
        <i data-lucide="timer" class="w-3.5 h-3.5"></i> + 快捷计时器
      </button>
    `;
    if (window.lucide) lucide.createIcons();
  }

  function renderActionGrids() {
    const child = getActiveChild();
    const activeTimerTypes = new Set(child ? state.timers.filter(t => t.childId === child.id).map(t => t.type) : []);

    const makeTile = (cat, compact) => {
      const running = activeTimerTypes.has(cat.id);
      return `<button onclick="App.openLogSheet('${cat.id}')" class="tile-btn ${running ? 'is-running' : ''} ${compact ? 'py-2.5 px-2' : 'py-3 px-2'} flex flex-col items-center justify-center gap-1.5 relative">
        ${running ? `<span class="absolute top-1.5 right-1.5 w-2 h-2 rounded-full animate-live-dot" style="background-color: var(--brand-primary);"></span>` : ''}
        <div class="${compact ? 'w-8 h-8 rounded-xl' : 'w-10 h-10 rounded-2xl'} flex items-center justify-center" style="background-color: ${cat.bg}; color: ${cat.fg};">
          <i data-lucide="${cat.icon}" class="${compact ? 'w-4 h-4' : 'w-5 h-5'}"></i>
        </div>
        <span class="text-xs font-bold truncate max-w-full" style="color: ${running ? 'var(--brand-primary)' : 'var(--text-primary)'};">${cat.label}</span>
      </button>`;
    };

    document.getElementById('dailyCareGrid').innerHTML = CATEGORIES.filter(c => c.group === 'care').map(c => makeTile(c, false)).join('');
    document.getElementById('growthHealthGrid').innerHTML = CATEGORIES.filter(c => c.group === 'health').map(c => makeTile(c, true)).join('');
    if (window.lucide) lucide.createIcons();
  }

  function renderTimelineFilters() {
    const filterList = [
      { id: 'all', label: '全部' },
      { id: 'feeding', label: '喂养' },
      { id: 'sleep', label: '睡眠' },
      { id: 'diaper', label: '换尿布' },
      { id: 'pumping', label: '吸奶' },
      { id: 'tummy', label: '趴卧' },
      { id: 'medication', label: '用药' },
      { id: 'growth', label: '成长健康' },
      { id: 'note', label: '备注' },
    ];
    document.getElementById('timelineFilterChips').innerHTML = filterList.map(f => `
      <button onclick="App.setCategoryFilter('${f.id}')" class="px-3 py-1 rounded-full text-xs font-semibold whitespace-nowrap ${activeCategoryFilter === f.id ? 'chip-active' : 'chip-base'}">${f.label}</button>
    `).join('');

    const tagObj = state.tags.find(t => t.id === activeTagFilter);
    document.getElementById('timelineTagFilterLabel').textContent = tagObj ? tagObj.name : '全部标签';
  }

  function getEntryDisplay(e) {
    const cat = CATEGORIES.find(c => c.id === e.type) || CATEGORIES[0];
    let title = cat.label;
    let timeLine = `${formatTime(e.start)}`;
    const durMin = Math.round(((e.end || e.start) - e.start) / 60000);

    if (e.type === 'feeding') {
      title = `喂养 · ${e.feedingType || '母乳'}${e.feedingMethod ? ' (' + e.feedingMethod + ')' : ''}`;
      timeLine = `${formatTime(e.start)}${durMin > 0 ? ' - ' + formatTime(e.end) + ' (' + durMin + '分)' : ''}${e.amount ? ' · ' + e.amount + ' ml' : ''}`;
    } else if (e.type === 'sleep') {
      title = `睡眠 · ${e.isNap ? '白天小睡' : '夜间睡眠'}`;
      timeLine = `${formatTime(e.start)} - ${formatTime(e.end || e.start)} (${Math.floor(durMin / 60)}h ${durMin % 60}m)`;
    } else if (e.type === 'diaper') {
      const parts = [];
      if (e.wet) parts.push('嘘嘘');
      if (e.solid) parts.push(`便便${e.stoolColor ? '·' + e.stoolColor : ''}`);
      if (!e.wet && !e.solid) parts.push('干净/仅更换');
      title = `换尿布 · ${parts.join(' + ')}`;
      timeLine = `${formatTime(e.start)}${e.diaperAmount ? ' · 分量 ' + e.diaperAmount : ''}`;
    } else if (e.type === 'pumping') {
      title = `吸奶${e.amount ? ' · ' + e.amount + ' ml' : ''}`;
      timeLine = `${formatTime(e.start)}${durMin > 0 ? ' · ' + durMin + ' 分钟' : ''}`;
    } else if (e.type === 'tummy') {
      title = `趴卧练习${e.milestone ? ' · ' + e.milestone : ''}`;
      timeLine = `${formatTime(e.start)}${durMin > 0 ? ' · ' + durMin + ' 分钟' : ''}`;
    } else if (e.type === 'medication') {
      title = `用药 · ${e.medName} (${e.dosage} ${e.medUnit})`;
      timeLine = `${formatTime(e.start)}${e.intervalHours ? ' · 间隔提醒 ' + e.intervalHours + 'h' : ''}`;
    } else if (e.type === 'temperature') {
      title = `体温 · ${e.value} °C`;
    } else if (e.type === 'weight') {
      title = `体重 · ${e.value} kg`;
    } else if (e.type === 'height') {
      title = `身高 · ${e.value} cm`;
    } else if (e.type === 'head') {
      title = `头围 · ${e.value} cm`;
    } else if (e.type === 'bmi') {
      title = `BMI · ${e.value}`;
    } else if (e.type === 'note') {
      title = '日常备注';
    }

    return { cat, title, timeLine };
  }

  function renderTimeline() {
    const container = document.getElementById('timelineContainer');
    const query = (document.getElementById('timelineSearchInput').value || '').trim().toLowerCase();
    const healthTypes = new Set(['temperature', 'weight', 'height', 'head', 'bmi']);

    let list = getChildEntries().filter(e => {
      if (activeCategoryFilter === 'growth') {
        if (!healthTypes.has(e.type)) return false;
      } else if (activeCategoryFilter !== 'all' && e.type !== activeCategoryFilter) {
        return false;
      }
      if (activeTagFilter !== 'all' && !(e.tags || []).includes(activeTagFilter)) {
        return false;
      }
      if (query) {
        const tagNames = (e.tags || []).map(tid => (state.tags.find(t => t.id === tid) || {}).name || '').join(' ');
        const hay = `${e.notes || ''} ${e.milestone || ''} ${e.medName || ''} ${e.feedingType || ''} ${e.feedingMethod || ''} ${tagNames}`.toLowerCase();
        if (!hay.includes(query)) return false;
      }
      return true;
    });

    if (list.length === 0) {
      container.innerHTML = `<div class="nursery-card p-6 text-center text-xs" style="color: var(--text-muted);">暂无符合条件的记录</div>`;
      return;
    }

    let html = '';
    let lastHeader = '';
    list.slice(0, 60).forEach(e => {
      const header = formatDateHeader(e.start);
      if (header !== lastHeader) {
        html += `<div class="text-xs font-bold pt-2 pb-1 px-1" style="color: var(--text-secondary);">${header}</div>`;
        lastHeader = header;
      }
      const { cat, title, timeLine } = getEntryDisplay(e);
      const stoolObj = e.stoolColor ? STOOL_COLORS.find(s => s.name === e.stoolColor) : null;
      const entryTags = (e.tags || []).map(tid => state.tags.find(t => t.id === tid)).filter(Boolean);

      html += `
      <div class="nursery-card p-3 flex items-start gap-2.5">
        <div class="w-10 h-10 rounded-2xl flex items-center justify-center shrink-0 mt-0.5" style="background-color: ${cat.bg}; color: ${cat.fg};">
          <i data-lucide="${cat.icon}" class="w-5 h-5"></i>
        </div>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1.5 flex-wrap">
            <span class="text-sm font-bold truncate">${title}</span>
            ${stoolObj ? `<span class="w-3 h-3 rounded-full inline-block border" style="background-color: ${stoolObj.hex}; border-color: var(--border-card);" title="${stoolObj.name}"></span>` : ''}
          </div>
          <div class="text-xs mt-0.5" style="color: var(--text-secondary);">${timeLine}</div>
          ${e.notes ? `<div class="text-xs mt-1 leading-relaxed" style="color: var(--text-muted);">${e.notes}</div>` : ''}
          ${e.photoDataUrl ? `<img src="${e.photoDataUrl}" class="mt-2 w-28 h-20 object-cover rounded-xl border" style="border-color: var(--border-card);" />` : ''}
          ${entryTags.length ? `<div class="flex flex-wrap gap-1 mt-1.5">${entryTags.map(t => `<span class="text-[10.5px] font-semibold px-2 py-0.5 rounded-full" style="background-color: ${t.color}22; color: ${t.color};"># ${t.name}</span>`).join('')}</div>` : ''}
        </div>
        <div class="flex items-center gap-1 shrink-0">
          <button onclick="App.openLogSheet('${e.type}', '${e.id}')" class="w-8 h-8 rounded-xl flex items-center justify-center" style="color: var(--text-muted);" title="编辑">
            <i data-lucide="pencil" class="w-3.5 h-3.5"></i>
          </button>
          <button onclick="App.deleteEntry('${e.id}')" class="w-8 h-8 rounded-xl flex items-center justify-center" style="background-color: var(--danger-bg); color: var(--danger-fg);" title="删除">
            <i data-lucide="trash-2" class="w-3.5 h-3.5"></i>
          </button>
        </div>
      </div>`;
    });

    container.innerHTML = html;
    if (window.lucide) lucide.createIcons();
  }

  function renderRecentPreview() {
    const container = document.getElementById('recentPreviewContainer');
    if (!container) return;
    const recent = getChildEntries().slice(0, 3);
    if (recent.length === 0) {
      container.innerHTML = `<div class="nursery-card p-5 text-center text-xs" style="color: var(--text-muted);">暂无照护记录，点击上方磁贴或底部「+」开始记录</div>`;
      return;
    }
    container.innerHTML = recent.map(e => {
      const { cat, title, timeLine } = getEntryDisplay(e);
      return `
        <div onclick="App.openLogSheet('${e.type}', '${e.id}')" class="nursery-card p-3 flex items-center gap-2.5 cursor-pointer active:scale-[0.99]">
          <div class="w-9 h-9 rounded-xl flex items-center justify-center shrink-0" style="background-color: ${cat.bg}; color: ${cat.fg};">
            <i data-lucide="${cat.icon}" class="w-4 h-4"></i>
          </div>
          <div class="flex-1 min-w-0">
            <div class="text-xs font-bold truncate">${title}</div>
            <div class="text-[11px] truncate" style="color: var(--text-secondary);">${timeLine}</div>
          </div>
          <i data-lucide="chevron-right" class="w-4 h-4 shrink-0" style="color: var(--text-muted);"></i>
        </div>
      `;
    }).join('');
    if (window.lucide) lucide.createIcons();
  }

  function renderGrowthPage() {
    const entries = getChildEntries();
    const latestOf = (type) => entries.find(e => e.type === type);
    const lw = latestOf('weight');
    const lh = latestOf('height');
    const lhc = latestOf('head');
    const lt = latestOf('temperature');
    const lmed = latestOf('medication');

    let bmiVal = '—';
    if (lw && lh && lh.value > 0) {
      bmiVal = (lw.value / Math.pow(lh.value / 100, 2)).toFixed(1);
    }

    const cards = [
      { label: '最新体重', val: lw ? `${lw.value} kg` : '—', color: 'var(--cat-weight-fg)' },
      { label: '最新身高', val: lh ? `${lh.value} cm` : '—', color: 'var(--cat-height-fg)' },
      { label: '最新头围', val: lhc ? `${lhc.value} cm` : '—', color: 'var(--cat-head-fg)' },
      { label: 'BMI / 体温', val: `${bmiVal} · ${lt ? lt.value + '°C' : '—'}`, color: 'var(--cat-temp-fg)' },
    ];
    const snapEl = document.getElementById('growthSnapshotGrid');
    if (snapEl) {
      snapEl.innerHTML = cards.map(c => `
        <div class="rounded-2xl p-2.5 border" style="background-color: var(--bg-card); border-color: var(--border-card);">
          <div class="text-[11px]" style="color: var(--text-secondary);">${c.label}</div>
          <div class="text-sm font-bold mt-0.5 truncate" style="color: ${c.color};">${c.val}</div>
        </div>
      `).join('') + (lmed ? `
        <div class="col-span-2 rounded-2xl p-2.5 border flex items-center justify-between" style="background-color: var(--bg-card); border-color: var(--border-card);">
          <span class="text-[11px]" style="color: var(--text-secondary);">最近用药：${lmed.medName}</span>
          <span class="text-xs font-bold" style="color: var(--cat-meds-fg);">${lmed.dosage} ${lmed.medUnit}</span>
        </div>
      ` : '');
    }
    renderWHOChart(entries);
  }

  function openQuickMenuSheet() {
    const care = CATEGORIES.filter(c => c.group === 'care');
    const health = CATEGORIES.filter(c => c.group === 'health');
    const renderGroup = (list) => list.map(cat => `
      <button onclick="App.closeSheet(); setTimeout(()=>App.openLogSheet('${cat.id}'), 220)" class="tile-btn py-3 px-2 flex flex-col items-center gap-1.5">
        <div class="w-10 h-10 rounded-2xl flex items-center justify-center" style="background-color: ${cat.bg}; color: ${cat.fg};">
          <i data-lucide="${cat.icon}" class="w-5 h-5"></i>
        </div>
        <span class="text-xs font-bold">${cat.label}</span>
      </button>
    `).join('');

    openSheet(`
      <div class="flex items-center justify-between mb-3">
        <div>
          <h3 class="text-base font-bold">快速记一笔</h3>
          <p class="text-[11px]" style="color: var(--text-secondary);">选择任意照护或成长健康项目</p>
        </div>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <div class="text-xs font-bold mb-2" style="color: var(--text-secondary);">日常照护</div>
      <div class="grid grid-cols-3 gap-2 mb-4">${renderGroup(care)}</div>
      <div class="text-xs font-bold mb-2" style="color: var(--text-secondary);">成长与健康</div>
      <div class="grid grid-cols-3 gap-2">${renderGroup(health)}</div>
    `);
  }

  // ======================== STATS & WHO GROWTH ========================
  function renderStatsView() {
    const ranges = [
      { days: 7, label: '近 7 天' },
      { days: 14, label: '近 14 天' },
      { days: 30, label: '近 30 天' },
      { days: 90, label: '近 90 天' },
      { days: 9999, label: '全部时间' },
    ];
    document.getElementById('statsRangeChips').innerHTML = ranges.map(r => `
      <button onclick="App.setStatsRange(${r.days})" class="px-3 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap ${statsRangeDays === r.days ? 'chip-active' : 'chip-base'}">${r.label}</button>
    `).join('');

    const entries = getChildEntries();
    const cutoff = statsRangeDays >= 9999 ? 0 : (Date.now() - statsRangeDays * 86400000);
    const scoped = entries.filter(e => e.start >= cutoff);
    const activeDays = Math.max(1, statsRangeDays >= 9999 ? 30 : Math.min(statsRangeDays, 30));

    const feeds = scoped.filter(e => e.type === 'feeding');
    const totalMl = feeds.reduce((s, e) => s + (Number(e.amount) || 0), 0);
    const sleeps = scoped.filter(e => e.type === 'sleep');
    const totalSleepHrs = sleeps.reduce((s, e) => s + Math.max(0, ((e.end || e.start) - e.start) / 3600000), 0);
    const diapers = scoped.filter(e => e.type === 'diaper');

    // Average intervals
    let avgFeedInt = '—';
    if (feeds.length >= 2) {
      const span = feeds[0].start - feeds[feeds.length - 1].start;
      const mins = Math.round(span / (feeds.length - 1) / 60000);
      avgFeedInt = `${Math.floor(mins / 60)}h ${mins % 60}m`;
    }

    // Weight growth rate
    const weights = entries.filter(e => e.type === 'weight').sort((a, b) => a.start - b.start);
    let growthRateStr = '—';
    if (weights.length >= 2) {
      const w0 = weights[0], w1 = weights[weights.length - 1];
      const weeks = Math.max(1, (w1.start - w0.start) / (7 * 86400000));
      const gPerWeek = Math.round(((w1.value - w0.value) * 1000) / weeks);
      growthRateStr = `+${gPerWeek} 克/周`;
    }

    const kpis = [
      { label: '日均奶量', val: `${Math.round(totalMl / activeDays)} ml`, color: 'var(--cat-feeding-fg)' },
      { label: '日均喂养', val: `${(feeds.length / activeDays).toFixed(1)} 次`, color: 'var(--text-primary)' },
      { label: '日均睡眠', val: `${(totalSleepHrs / activeDays).toFixed(1)} 小时`, color: 'var(--cat-sleep-fg)' },
      { label: '日均尿布', val: `${(diapers.length / activeDays).toFixed(1)} 次`, color: 'var(--cat-diaper-fg)' },
      { label: '平均喂养间隔', val: avgFeedInt, color: 'var(--text-primary)' },
      { label: '体重增长速率', val: growthRateStr, color: 'var(--cat-weight-fg)' },
    ];
    document.getElementById('statsKpiGrid').innerHTML = kpis.map(k => `
      <div class="rounded-2xl p-2.5 border" style="background-color: var(--bg-card); border-color: var(--border-card);">
        <div class="text-[11px]" style="color: var(--text-secondary);">${k.label}</div>
        <div class="text-base font-bold mt-0.5" style="color: ${k.color};">${k.val}</div>
      </div>
    `).join('');

    const leftCnt = feeds.filter(e => e.feedingMethod === '左侧亲喂').length;
    const rightCnt = feeds.filter(e => e.feedingMethod === '右侧亲喂').length;
    const bothCnt = feeds.filter(e => e.feedingMethod === '双侧亲喂').length;
    document.getElementById('statsBreastRatioBar').innerHTML = `
      <span style="color: var(--text-secondary);">亲喂左右侧分布</span>
      <span class="font-bold">左: ${leftCnt} 次 · 右: ${rightCnt} 次 · 双侧: ${bothCnt} 次</span>
    `;

    renderFeedingBarChart(feeds);
    renderSleepPattern(sleeps);
    renderDiaperBreakdown(diapers);
  }

  function renderFeedingBarChart(feeds) {
    const days = Math.min(7, statsRangeDays);
    const bars = [];
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(Date.now() - i * 86400000);
      d.setHours(0, 0, 0, 0);
      const t0 = d.getTime(), t1 = t0 + 86400000;
      const ml = feeds.filter(f => f.start >= t0 && f.start < t1).reduce((s, f) => s + (Number(f.amount) || 0), 0);
      bars.push({ label: `${d.getMonth() + 1}/${d.getDate()}`, ml });
    }
    const maxMl = Math.max(400, ...bars.map(b => b.ml));
    const total = feeds.reduce((s, f) => s + (Number(f.amount) || 0), 0);
    document.getElementById('statsFeedSubtitle').textContent = `累计 ${total} ml · 共 ${feeds.length} 次喂养`;
    document.getElementById('statsFeedBadge').textContent = `最高 ${maxMl} ml`;

    document.getElementById('statsFeedChart').innerHTML = `
      <div class="flex items-end justify-between gap-2 h-36 pt-4 pb-1 px-1">
        ${bars.map(b => {
          const pct = Math.max(6, Math.round((b.ml / maxMl) * 100));
          return `<div class="flex-1 flex flex-col items-center gap-1 h-full justify-end">
            <span class="text-[10px] font-bold" style="color: var(--text-secondary);">${b.ml || ''}</span>
            <div class="w-full rounded-t-xl transition-all" style="height: ${pct}%; background-color: var(--cat-feeding-fg); opacity: ${b.ml > 0 ? 0.9 : 0.2};"></div>
            <span class="text-[10px]" style="color: var(--text-muted);">${b.label}</span>
          </div>`;
        }).join('')}
      </div>
    `;
  }

  function renderSleepPattern(sleeps) {
    const totalH = sleeps.reduce((s, e) => s + Math.max(0, ((e.end || e.start) - e.start) / 3600000), 0);
    document.getElementById('statsSleepSubtitle').textContent = `累计 ${totalH.toFixed(1)} 小时 · 共 ${sleeps.length} 段睡眠`;

    let rowsHtml = '';
    for (let i = 6; i >= 0; i--) {
      const d = new Date(Date.now() - i * 86400000);
      d.setHours(0, 0, 0, 0);
      const t0 = d.getTime(), t1 = t0 + 86400000;
      const daySleeps = sleeps.filter(s => (s.end || s.start) > t0 && s.start < t1);
      const segs = daySleeps.map(s => {
        const s0 = Math.max(t0, s.start), s1 = Math.min(t1, s.end || s.start + 1800000);
        const left = ((s0 - t0) / 86400000) * 100;
        const width = Math.max(2, ((s1 - s0) / 86400000) * 100);
        return `<div class="absolute top-0 bottom-0 rounded-full" style="left: ${left.toFixed(1)}%; width: ${width.toFixed(1)}%; background-color: ${s.isNap ? '#7FA3ED' : '#3D5A99'};"></div>`;
      }).join('');
      rowsHtml += `
        <div class="flex items-center gap-2">
          <span class="text-[10.5px] w-9 shrink-0" style="color: var(--text-muted);">${d.getMonth() + 1}/${d.getDate()}</span>
          <div class="flex-1 h-3.5 rounded-full relative overflow-hidden" style="background-color: var(--bg-chip);">${segs}</div>
        </div>
      `;
    }
    rowsHtml += `<div class="flex justify-between pl-11 text-[10px]" style="color: var(--text-muted);"><span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>24:00</span></div>`;
    document.getElementById('statsSleepPatternChart').innerHTML = rowsHtml;
  }

  function renderDiaperBreakdown(diapers) {
    const wet = diapers.filter(d => d.wet).length;
    const solid = diapers.filter(d => d.solid).length;
    document.getElementById('statsDiaperSubtitle').textContent = `嘘嘘: ${wet} 次 · 便便: ${solid} 次 · 合计: ${diapers.length} 次`;

    const colorCounts = {};
    diapers.filter(d => d.solid && d.stoolColor).forEach(d => {
      colorCounts[d.stoolColor] = (colorCounts[d.stoolColor] || 0) + 1;
    });
    const keys = Object.keys(colorCounts);
    if (keys.length === 0) {
      document.getElementById('statsDiaperBreakdown').innerHTML = `<div class="text-xs" style="color: var(--text-muted);">当前时间范围暂无便便颜色记录</div>`;
      return;
    }
    const maxC = Math.max(...Object.values(colorCounts));
    document.getElementById('statsDiaperBreakdown').innerHTML = keys.map(k => {
      const sw = STOOL_COLORS.find(s => s.name === k) || { hex: '#D99B26' };
      const pct = Math.round((colorCounts[k] / maxC) * 100);
      return `<div class="flex items-center gap-2.5 text-xs">
        <span class="w-3.5 h-3.5 rounded-full shrink-0 border" style="background-color: ${sw.hex}; border-color: var(--border-card);"></span>
        <span class="w-10 shrink-0 font-medium">${k}</span>
        <div class="flex-1 h-2.5 rounded-full overflow-hidden" style="background-color: var(--bg-chip);">
          <div class="h-full rounded-full" style="width: ${pct}%; background-color: ${sw.hex};"></div>
        </div>
        <span class="w-8 text-right font-bold">${colorCounts[k]}次</span>
      </div>`;
    }).join('');
  }

  function renderWHOChart(entries) {
    const child = getActiveChild();
    const metrics = [
      { id: 'weight', label: '体重', unit: 'kg' },
      { id: 'height', label: '身高', unit: 'cm' },
      { id: 'head', label: '头围', unit: 'cm' },
      { id: 'bmi', label: 'BMI', unit: '' },
      { id: 'temperature', label: '体温', unit: '°C' },
    ];
    document.getElementById('whoMetricChips').innerHTML = metrics.map(m => `
      <button onclick="App.setWhoMetric('${m.id}')" class="px-3 py-1 rounded-full text-xs font-semibold whitespace-nowrap ${whoMetric === m.id ? 'chip-active' : 'chip-base'}">${m.label}</button>
    `).join('');

    const mMeta = metrics.find(m => m.id === whoMetric);
    const pts = entries.filter(e => e.type === whoMetric).sort((a, b) => a.start - b.start);
    const badge = document.getElementById('whoPercentileBadge');
    const summaryEl = document.getElementById('whoLatestSummary');

    if (pts.length === 0) {
      badge.classList.add('hidden');
      summaryEl.textContent = `暂无${mMeta.label}测量记录，请在「记录」页点击对应磁贴添加。`;
      document.getElementById('whoChartContainer').innerHTML = `<div class="h-36 rounded-2xl flex items-center justify-center text-xs" style="background-color: var(--bg-chip); color: var(--text-muted);">暂无曲线数据</div>`;
      return;
    }

    const latest = pts[pts.length - 1];
    const prev = pts.length > 1 ? pts[pts.length - 2] : null;
    const delta = prev ? (latest.value - prev.value).toFixed(2) : null;
    summaryEl.textContent = `最新：${latest.value} ${mMeta.unit}${delta !== null ? `（较上次 ${Number(delta) >= 0 ? '+' : ''}${delta} ${mMeta.unit}）` : ''}`;

    const ageM = getAgeMonths(child, true);
    const evalRes = (whoMetric !== 'temperature' && window.WHOStandards)
      ? window.WHOStandards.evaluate(whoMetric, child?.sex, ageM, latest.value)
      : null;

    if (evalRes) {
      badge.classList.remove('hidden');
      badge.textContent = `WHO P${evalRes.pct} (Z ${evalRes.z >= 0 ? '+' : ''}${evalRes.z.toFixed(2)})${child?.dueDate ? ' · 矫正月龄' : ''}`;
    } else {
      badge.classList.add('hidden');
    }

    // Build SVG Chart
    const W = 360, H = 175, padL = 32, padR = 14, padT = 14, padB = 22;
    const vals = pts.map(p => p.value);
    let minV = Math.min(...vals), maxV = Math.max(...vals);

    // Include WHO P3..P97 range in Y bounds if applicable
    let bandPoly = '';
    let p50Line = '';
    if (whoMetric !== 'temperature' && window.WHOStandards) {
      const sex = child?.sex || 'boy';
      const lmsNow = window.WHOStandards.getLMS(whoMetric, sex, ageM);
      const lmsStart = window.WHOStandards.getLMS(whoMetric, sex, Math.max(0, ageM - 2));
      if (lmsNow && lmsStart) {
        const p3_0 = window.WHOStandards.valueFromZ(...lmsStart, -1.88);
        const p97_0 = window.WHOStandards.valueFromZ(...lmsStart, 1.88);
        const p3_1 = window.WHOStandards.valueFromZ(...lmsNow, -1.88);
        const p97_1 = window.WHOStandards.valueFromZ(...lmsNow, 1.88);
        minV = Math.min(minV, p3_0, p3_1);
        maxV = Math.max(maxV, p97_0, p97_1);
      }
    }
    const spanV = Math.max(0.5, maxV - minV);
    minV -= spanV * 0.12;
    maxV += spanV * 0.12;

    const mapY = v => padT + (1 - (v - minV) / (maxV - minV)) * (H - padT - padB);
    const mapX = idx => pts.length === 1 ? (padL + (W - padL - padR) / 2) : (padL + (idx / (pts.length - 1)) * (W - padL - padR));

    if (whoMetric !== 'temperature' && window.WHOStandards) {
      const sex = child?.sex || 'boy';
      const m0 = Math.max(0, ageM - 2), m1 = ageM;
      const l0 = window.WHOStandards.getLMS(whoMetric, sex, m0);
      const l1 = window.WHOStandards.getLMS(whoMetric, sex, m1);
      if (l0 && l1) {
        const x0 = padL, x1 = W - padR;
        const yP97_0 = mapY(window.WHOStandards.valueFromZ(...l0, 1.88));
        const yP97_1 = mapY(window.WHOStandards.valueFromZ(...l1, 1.88));
        const yP3_0 = mapY(window.WHOStandards.valueFromZ(...l0, -1.88));
        const yP3_1 = mapY(window.WHOStandards.valueFromZ(...l1, -1.88));
        const yP50_0 = mapY(window.WHOStandards.valueFromZ(...l0, 0));
        const yP50_1 = mapY(window.WHOStandards.valueFromZ(...l1, 0));
        bandPoly = `<polygon points="${x0},${yP97_0.toFixed(1)} ${x1},${yP97_1.toFixed(1)} ${x1},${yP3_1.toFixed(1)} ${x0},${yP3_0.toFixed(1)}" fill="#81B29A" opacity="0.14" />`;
        p50Line = `<line x1="${x0}" y1="${yP50_0.toFixed(1)}" x2="${x1}" y2="${yP50_1.toFixed(1)}" stroke="#81B29A" stroke-width="1.2" stroke-dasharray="4 3" />
                   <text x="${x1 - 24}" y="${(yP50_1 - 4).toFixed(1)}" font-size="9" fill="#3D9970">WHO P50</text>`;
      }
    }

    const polyPts = pts.map((p, i) => `${mapX(i).toFixed(1)},${mapY(p.value).toFixed(1)}`).join(' ');
    const dots = pts.map((p, i) => {
      const cx = mapX(i).toFixed(1), cy = mapY(p.value).toFixed(1);
      const d = new Date(p.start);
      return `
        <circle cx="${cx}" cy="${cy}" r="4" fill="#3D9970" stroke="#FFFFFF" stroke-width="1.5" />
        <text x="${cx}" y="${H - 6}" text-anchor="middle" font-size="9" fill="var(--text-muted)">${d.getMonth() + 1}/${d.getDate()}</text>
      `;
    }).join('');

    document.getElementById('whoChartContainer').innerHTML = `
      <svg viewBox="0 0 ${W} ${H}" class="w-full h-44 overflow-visible">
        <text x="4" y="${padT + 8}" font-size="9" fill="var(--text-muted)">${maxV.toFixed(1)}</text>
        <text x="4" y="${H - padB}" font-size="9" fill="var(--text-muted)">${minV.toFixed(1)}</text>
        ${bandPoly}
        ${p50Line}
        ${pts.length > 1 ? `<polyline fill="none" stroke="#3D9970" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" points="${polyPts}" />` : ''}
        ${dots}
      </svg>
    `;
  }

  // ======================== BOTTOM SHEETS (LOG / PROFILE / BACKUP / SETTINGS) ========================
  function openSheet(html) {
    const overlay = document.getElementById('sheetOverlay');
    const panel = document.getElementById('sheetPanel');
    panel.innerHTML = `<div class="w-10 h-1 rounded-full mx-auto mb-4" style="background-color: var(--border-card);"></div>` + html;
    overlay.classList.remove('hidden');
    overlay.classList.add('flex');
    requestAnimationFrame(() => overlay.classList.add('sheet-open'));
    if (window.lucide) lucide.createIcons();
  }

  function closeSheet() {
    const overlay = document.getElementById('sheetOverlay');
    overlay.classList.remove('sheet-open');
    setTimeout(() => {
      overlay.classList.add('hidden');
      overlay.classList.remove('flex');
    }, 240);
  }

  let sheetDraft = {};

  function openLogSheet(type, editId = null) {
    const child = getActiveChild();
    if (!child) {
      openBabyProfileModal(false);
      return;
    }
    const existing = editId ? state.entries.find(e => e.id === editId) : null;
    const cat = CATEGORIES.find(c => c.id === type);
    const now = Date.now();
    const existingTimer = state.timers.find(t => t.childId === child.id && t.type === type);

    sheetDraft = existing ? JSON.parse(JSON.stringify(existing)) : {
      id: null,
      childId: child.id,
      type,
      mode: existingTimer ? 'timer' : (cat.hasTimer ? 'timer' : 'manual'),
      start: existingTimer ? existingTimer.start : now - 15 * 60000,
      end: now,
      amount: type === 'feeding' ? 120 : (type === 'pumping' ? 100 : 0),
      feedingType: '配方奶',
      feedingMethod: '奶瓶',
      wet: true,
      solid: false,
      stoolColor: '黄色',
      diaperAmount: 1,
      isNap: new Date().getHours() >= state.settings.napStartHour && new Date().getHours() < state.settings.napEndHour,
      milestone: '',
      medName: '维生素 D3',
      dosage: 1,
      medUnit: '滴',
      intervalHours: 24,
      value: type === 'temperature' ? 36.6 : (type === 'weight' ? 6.5 : (type === 'height' ? 61.5 : (type === 'head' ? 40.5 : 17.2))),
      notes: '',
      tags: [],
      photoDataUrl: null,
    };

    // Auto BMI calculation hint
    let autoBmiHint = '';
    if (type === 'bmi' && !existing) {
      const entries = getChildEntries();
      const lw = entries.find(e => e.type === 'weight');
      const lh = entries.find(e => e.type === 'height');
      if (lw && lh && lh.value > 0) {
        const calc = (lw.value / Math.pow(lh.value / 100, 2)).toFixed(1);
        sheetDraft.value = Number(calc);
        autoBmiHint = `已根据最新体重(${lw.value}kg)与身高(${lh.value}cm)自动算得：${calc}`;
      }
    }

    renderLogSheetContent(cat, existingTimer, autoBmiHint);
  }

  function renderLogSheetContent(cat, existingTimer, autoBmiHint = '') {
    const d = sheetDraft;
    let bodyHtml = `
      <div class="flex items-center justify-between mb-3">
        <div class="flex items-center gap-2.5">
          <div class="w-10 h-10 rounded-2xl flex items-center justify-center" style="background-color: ${cat.bg}; color: ${cat.fg};">
            <i data-lucide="${cat.icon}" class="w-5 h-5"></i>
          </div>
          <div>
            <h3 class="text-base font-bold">${d.id ? '编辑' : '记录'}${cat.label}</h3>
            <p class="text-[11px]" style="color: var(--text-secondary);">本地即时保存 · 单手快捷操作</p>
          </div>
        </div>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center" style="background-color: var(--bg-chip); color: var(--text-secondary);">
          <i data-lucide="x" class="w-4 h-4"></i>
        </button>
      </div>
    `;

    // Timer / Manual Mode Section for timer-capable categories
    if (cat.hasTimer && !d.id) {
      bodyHtml += `
        <div class="rounded-2xl p-3 mb-3 border" style="background-color: var(--bg-card-elevated); border-color: var(--border-card);">
          <div class="flex items-center justify-between gap-2">
            <div class="flex p-0.5 rounded-full" style="background-color: var(--bg-chip);">
              <button onclick="App.setDraftField('mode','timer')" class="px-3 py-1 rounded-full text-xs font-semibold ${d.mode === 'timer' ? 'chip-active' : ''}">实时计时</button>
              <button onclick="App.setDraftField('mode','manual')" class="px-3 py-1 rounded-full text-xs font-semibold ${d.mode === 'manual' ? 'chip-active' : ''}">手动补录</button>
            </div>
            ${d.mode === 'timer' ? `
              <span class="px-3 py-1 rounded-full text-xs font-bold font-mono" style="background-color: var(--bg-card); color: var(--brand-primary);">
                ${existingTimer ? formatHMS(Date.now() - existingTimer.start) : '点击下方启动'}
              </span>
            ` : `
              <div class="flex items-center gap-1">
                <button onclick="App.presetDraftMinus(15)" class="px-2 py-1 rounded-full text-[11px] font-semibold chip-base">-15分</button>
                <button onclick="App.presetDraftMinus(30)" class="px-2 py-1 rounded-full text-[11px] font-semibold chip-base">-30分</button>
                <button onclick="App.presetDraftMinus(60)" class="px-2 py-1 rounded-full text-[11px] font-semibold chip-base">-1小时</button>
              </div>
            `}
          </div>
          ${d.mode === 'timer' && !existingTimer ? `
            <button onclick="App.startCategoryTimer('${cat.id}', '${cat.label}')" class="w-full mt-2.5 h-10 rounded-xl font-bold text-xs flex items-center justify-center gap-1.5 text-white" style="background-color: var(--brand-secondary);">
              <i data-lucide="play" class="w-4 h-4"></i> 启动「${cat.label}」实时计时器
            </button>
          ` : ''}
        </div>
      `;
    }

    // Specific controls per type
    if (d.type === 'feeding') {
      bodyHtml += `
        <div class="rounded-2xl p-3 mb-3 border" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <div class="flex items-center justify-between mb-2">
            <button onclick="App.stepDraftAmount(-10)" class="w-11 h-11 rounded-full border flex items-center justify-center font-bold text-lg" style="background-color: var(--bg-card); border-color: var(--border-card);">-</button>
            <div class="text-center">
              <div class="text-[11px]" style="color: var(--text-secondary);">喂养量</div>
              <div class="text-2xl font-bold" style="color: var(--cat-feeding-fg);">${d.amount} <span class="text-xs font-normal">ml</span></div>
            </div>
            <button onclick="App.stepDraftAmount(10)" class="w-11 h-11 rounded-full flex items-center justify-center font-bold text-lg text-white" style="background-color: var(--brand-primary);">+</button>
          </div>
          <div class="grid grid-cols-5 gap-1.5">
            ${[60, 90, 120, 150].map(v => `<button onclick="App.setDraftField('amount', ${v})" class="py-1.5 rounded-full text-xs font-semibold ${d.amount === v ? 'chip-active' : 'chip-base'}">${v}</button>`).join('')}
            <button onclick="App.stepDraftAmount(30)" class="py-1.5 rounded-full text-xs font-semibold chip-base">+30</button>
          </div>
        </div>
        <div class="mb-3">
          <div class="text-xs font-bold mb-1.5" style="color: var(--text-secondary);">类型</div>
          <div class="grid grid-cols-4 gap-1.5">
            ${FEEDING_TYPES.map(ft => `<button onclick="App.setDraftField('feedingType','${ft}')" class="py-2 rounded-xl text-xs font-semibold ${d.feedingType === ft ? 'chip-active' : 'chip-base'}">${ft}</button>`).join('')}
          </div>
        </div>
        <div class="mb-3">
          <div class="text-xs font-bold mb-1.5" style="color: var(--text-secondary);">喂养方式</div>
          <div class="grid grid-cols-3 gap-1.5">
            ${FEEDING_METHODS.map(fm => `<button onclick="App.setDraftField('feedingMethod','${fm}')" class="py-2 rounded-xl text-xs font-semibold ${d.feedingMethod === fm ? 'chip-active' : 'chip-base'}">${fm}</button>`).join('')}
          </div>
        </div>
      `;
    } else if (d.type === 'diaper') {
      bodyHtml += `
        <div class="grid grid-cols-3 gap-2 mb-3">
          <button onclick="App.toggleDraftBool('wet')" class="py-3 rounded-2xl border text-xs font-bold flex flex-col items-center gap-1 ${d.wet ? 'chip-active' : 'chip-base'}">
            <i data-lucide="droplets" class="w-5 h-5"></i><span>嘘嘘 (湿)</span>
          </button>
          <button onclick="App.toggleDraftBool('solid')" class="py-3 rounded-2xl border text-xs font-bold flex flex-col items-center gap-1 ${d.solid ? 'chip-active' : 'chip-base'}">
            <i data-lucide="circle-dot" class="w-5 h-5"></i><span>便便</span>
          </button>
          <button onclick="App.setCleanDiaper()" class="py-3 rounded-2xl border text-xs font-bold flex flex-col items-center gap-1 ${!d.wet && !d.solid ? 'chip-active' : 'chip-base'}">
            <i data-lucide="sparkles" class="w-5 h-5"></i><span>干净更换</span>
          </button>
        </div>
        ${d.solid ? `
          <div class="rounded-2xl p-3 mb-3 border" style="background-color: var(--bg-surface); border-color: var(--border-card);">
            <div class="text-xs font-bold mb-2" style="color: var(--text-secondary);">便便颜色（8种标准色卡）</div>
            <div class="grid grid-cols-4 gap-2.5">
              ${STOOL_COLORS.map(sc => `
                <button onclick="App.setDraftField('stoolColor','${sc.name}')" class="flex flex-col items-center gap-1">
                  <span class="w-8 h-8 rounded-full border swatch-ring ${d.stoolColor === sc.name ? 'selected' : ''}" style="background-color: ${sc.hex}; border-color: var(--border-card);"></span>
                  <span class="text-[10.5px]" style="color: var(--text-secondary);">${sc.name}</span>
                </button>
              `).join('')}
            </div>
          </div>
        ` : ''}
      `;
    } else if (d.type === 'sleep') {
      bodyHtml += `
        <div class="flex items-center justify-between p-3 rounded-2xl border mb-3" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <div>
            <div class="text-xs font-bold">白天小睡</div>
            <div class="text-[11px]" style="color: var(--text-muted);">已根据日间窗口自动识别，可随时切换</div>
          </div>
          <button onclick="App.toggleDraftBool('isNap')" class="px-4 py-1.5 rounded-full text-xs font-bold ${d.isNap ? 'chip-active' : 'chip-base'}">${d.isNap ? '是 · 白天小睡' : '否 · 夜间睡眠'}</button>
        </div>
      `;
    } else if (d.type === 'pumping') {
      bodyHtml += `
        <div class="rounded-2xl p-3 mb-3 border flex items-center justify-between" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <button onclick="App.stepDraftAmount(-10)" class="w-11 h-11 rounded-full border font-bold text-lg" style="background-color: var(--bg-card); border-color: var(--border-card);">-</button>
          <div class="text-center">
            <div class="text-[11px]" style="color: var(--text-secondary);">吸奶量</div>
            <div class="text-2xl font-bold" style="color: var(--cat-pumping-fg);">${d.amount} <span class="text-xs">ml</span></div>
          </div>
          <button onclick="App.stepDraftAmount(10)" class="w-11 h-11 rounded-full font-bold text-lg text-white" style="background-color: var(--brand-primary);">+</button>
        </div>
      `;
    } else if (d.type === 'tummy') {
      bodyHtml += `
        <div class="mb-3">
          <div class="text-xs font-bold mb-1.5" style="color: var(--text-secondary);">发育里程碑</div>
          <input type="text" value="${d.milestone || ''}" oninput="sheetDraft.milestone = this.value" placeholder="如：抬头 45°、自主翻身" class="w-full h-10 px-3 rounded-xl border text-xs outline-none" style="background-color: var(--bg-surface); border-color: var(--border-card); color: var(--text-primary);" />
        </div>
      `;
    } else if (d.type === 'medication') {
      const commonMeds = ['维生素 D3', '维生素 AD', '布洛芬混悬液', '益生菌'];
      bodyHtml += `
        <div class="mb-3">
          <div class="text-xs font-bold mb-1.5" style="color: var(--text-secondary);">药品 / 补剂名称</div>
          <input type="text" value="${d.medName || ''}" oninput="sheetDraft.medName = this.value" placeholder="输入药品名称" class="w-full h-10 px-3 rounded-xl border text-xs outline-none mb-2" style="background-color: var(--bg-surface); border-color: var(--border-card); color: var(--text-primary);" />
          <div class="flex gap-1.5 overflow-x-auto no-scrollbar">
            ${commonMeds.map(cm => `<button onclick="App.setDraftField('medName','${cm}')" class="px-2.5 py-1 rounded-full text-[11px] font-semibold whitespace-nowrap ${d.medName === cm ? 'chip-active' : 'chip-base'}">${cm}</button>`).join('')}
          </div>
        </div>
        <div class="rounded-2xl p-3 mb-3 border" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <div class="flex items-center justify-between mb-2">
            <button onclick="App.stepDraftDosage(-0.5)" class="w-10 h-10 rounded-full border font-bold" style="background-color: var(--bg-card); border-color: var(--border-card);">-</button>
            <div class="text-center">
              <div class="text-[11px]" style="color: var(--text-secondary);">剂量</div>
              <div class="text-xl font-bold" style="color: var(--cat-meds-fg);">${d.dosage} <span class="text-xs">${d.medUnit}</span></div>
            </div>
            <button onclick="App.stepDraftDosage(0.5)" class="w-10 h-10 rounded-full font-bold text-white" style="background-color: var(--brand-primary);">+</button>
          </div>
          <div class="grid grid-cols-4 gap-1.5">
            ${MED_UNITS.map(u => `<button onclick="App.setDraftField('medUnit','${u}')" class="py-1.5 rounded-xl text-[11px] font-semibold ${d.medUnit === u ? 'chip-active' : 'chip-base'}">${u}</button>`).join('')}
          </div>
        </div>
        <div class="mb-3">
          <div class="text-xs font-bold mb-1.5" style="color: var(--text-secondary);">下次服药间隔提醒</div>
          <div class="grid grid-cols-6 gap-1.5">
            ${MED_INTERVALS.map(iv => `<button onclick="App.setDraftField('intervalHours',${iv.hours})" class="py-1.5 rounded-xl text-[11px] font-semibold ${d.intervalHours === iv.hours ? 'chip-active' : 'chip-base'}">${iv.label}</button>`).join('')}
          </div>
        </div>
      `;
    } else if (['temperature', 'weight', 'height', 'head', 'bmi'].includes(d.type)) {
      const unitMap = { temperature: '°C', weight: 'kg', height: 'cm', head: 'cm', bmi: '' };
      const stepMap = { temperature: 0.1, weight: 0.05, height: 0.5, head: 0.2, bmi: 0.1 };
      const st = stepMap[d.type];
      bodyHtml += `
        ${autoBmiHint ? `<div class="mb-2.5 px-3 py-2 rounded-xl text-xs font-semibold" style="background-color: var(--cat-bmi-bg); color: var(--cat-bmi-fg);">${autoBmiHint}</div>` : ''}
        <div class="rounded-2xl p-3 mb-3 border flex items-center justify-between" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <button onclick="App.stepDraftValue(${-st})" class="w-11 h-11 rounded-full border font-bold text-lg" style="background-color: var(--bg-card); border-color: var(--border-card);">-</button>
          <div class="text-center">
            <div class="text-[11px]" style="color: var(--text-secondary);">${cat.label}</div>
            <div class="text-2xl font-bold" style="color: ${cat.fg};">${d.value} <span class="text-xs font-normal">${unitMap[d.type]}</span></div>
          </div>
          <button onclick="App.stepDraftValue(${st})" class="w-11 h-11 rounded-full font-bold text-lg text-white" style="background-color: var(--brand-primary);">+</button>
        </div>
      `;
    }

    // Photo Attachment for Note (or any entry)
    if (d.type === 'note') {
      bodyHtml += `
        <div class="mb-3">
          <label class="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold cursor-pointer chip-base">
            <i data-lucide="image" class="w-4 h-4"></i> 添加本地照片附件
            <input type="file" accept="image/*" class="hidden" onchange="App.handleNotePhoto(event)" />
          </label>
          ${d.photoDataUrl ? `<img src="${d.photoDataUrl}" class="mt-2 w-32 h-24 object-cover rounded-xl border" style="border-color: var(--border-card);" />` : ''}
        </div>
      `;
    }

    // Tags selector + Notes + Save Button
    bodyHtml += `
      <div class="mb-3">
        <div class="text-xs font-bold mb-1.5" style="color: var(--text-secondary);">关联标签</div>
        <div class="flex flex-wrap gap-1.5">
          ${state.tags.map(t => {
            const on = (d.tags || []).includes(t.id);
            return `<button onclick="App.toggleDraftTag('${t.id}')" class="px-2.5 py-1 rounded-full text-xs font-semibold ${on ? 'chip-active' : 'chip-base'}"># ${t.name}</button>`;
          }).join('')}
        </div>
      </div>
      <textarea rows="2" oninput="sheetDraft.notes = this.value" placeholder="添加备注（选填）…" class="w-full p-3 rounded-2xl border text-xs outline-none mb-3" style="background-color: var(--bg-surface); border-color: var(--border-card); color: var(--text-primary);">${d.notes || ''}</textarea>
      <button onclick="App.saveLogDraft()" class="w-full h-12 rounded-2xl font-bold text-sm text-white flex items-center justify-center gap-2 shadow-md" style="background-color: var(--brand-primary);">
        <i data-lucide="check" class="w-4 h-4"></i> 保存记录
      </button>
    `;

    openSheet(bodyHtml);
  }

  function openBabyProfileModal(isEdit) {
    const existing = isEdit ? getActiveChild() : null;
    const d = existing ? { ...existing } : {
      id: null, firstName: '', lastName: '', birthDate: new Date().toISOString().slice(0, 10), birthTime: '08:00', dueDate: '', sex: 'girl', avatarEmoji: '👶🏻'
    };
    sheetDraft = d;

    const emojis = ['👶🏻', '🧸', '🍼', '🐣', '🌸', '🦁'];
    openSheet(`
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-base font-bold">${isEdit ? '编辑宝宝档案' : '新建宝宝档案'}</h3>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <div class="flex items-center gap-2 mb-3">
        ${emojis.map(em => `<button onclick="sheetDraft.avatarEmoji='${em}'; this.parentElement.querySelectorAll('button').forEach(b=>b.classList.remove('chip-active')); this.classList.add('chip-active');" class="w-10 h-10 rounded-2xl text-lg ${d.avatarEmoji === em ? 'chip-active' : 'chip-base'}">${em}</button>`).join('')}
      </div>
      <div class="space-y-2.5 mb-4">
        <input type="text" value="${d.firstName}" oninput="sheetDraft.firstName=this.value" placeholder="名字 / 小名（必填，如：糯米）" class="w-full h-11 px-3 rounded-xl border text-xs outline-none" style="background-color: var(--bg-surface); border-color: var(--border-card);" />
        <div class="grid grid-cols-3 gap-2">
          ${[['boy','男宝'],['girl','女宝'],['','未指定']].map(([val, lbl]) => `
            <button onclick="sheetDraft.sex='${val}'; this.parentElement.querySelectorAll('button').forEach(b=>b.className='py-2 rounded-xl text-xs font-semibold chip-base'); this.className='py-2 rounded-xl text-xs font-semibold chip-active';" class="py-2 rounded-xl text-xs font-semibold ${d.sex === val ? 'chip-active' : 'chip-base'}">${lbl}</button>
          `).join('')}
        </div>
        <div class="grid grid-cols-2 gap-2">
          <div>
            <label class="text-[11px]" style="color: var(--text-secondary);">出生日期</label>
            <input type="date" value="${d.birthDate}" oninput="sheetDraft.birthDate=this.value" class="w-full h-10 px-2.5 rounded-xl border text-xs mt-1" style="background-color: var(--bg-surface); border-color: var(--border-card);" />
          </div>
          <div>
            <label class="text-[11px]" style="color: var(--text-secondary);">预产期（早产矫正选填）</label>
            <input type="date" value="${d.dueDate || ''}" oninput="sheetDraft.dueDate=this.value" class="w-full h-10 px-2.5 rounded-xl border text-xs mt-1" style="background-color: var(--bg-surface); border-color: var(--border-card);" />
          </div>
        </div>
      </div>
      <button onclick="App.saveBabyProfile()" class="w-full h-12 rounded-2xl font-bold text-sm text-white" style="background-color: var(--brand-primary);">保存宝宝档案</button>
    `);
  }

  function openBackupSheet() {
    openSheet(`
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-base font-bold">本地备份、导出与恢复</h3>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <p class="text-xs mb-4 leading-relaxed" style="color: var(--text-secondary);">所有数据均保存在当前设备本地。支持导出完整 JSON 数据快照或 CSV 电子表格，导入时支持智能合并去重。</p>
      <div class="space-y-2.5">
        <button onclick="App.exportJson()" class="w-full h-11 rounded-2xl font-bold text-xs text-white flex items-center justify-center gap-2" style="background-color: var(--brand-primary);">
          <i data-lucide="download" class="w-4 h-4"></i> 导出完整数据快照 (.json)
        </button>
        <button onclick="App.exportCsv()" class="w-full h-11 rounded-2xl font-bold text-xs border flex items-center justify-center gap-2" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <i data-lucide="file-spreadsheet" class="w-4 h-4"></i> 导出电子表格 (.csv)
        </button>
        <label class="w-full h-11 rounded-2xl font-bold text-xs border flex items-center justify-center gap-2 cursor-pointer" style="background-color: var(--bg-surface); border-color: var(--border-card);">
          <i data-lucide="upload" class="w-4 h-4"></i> 从 JSON 备份文件恢复（智能合并）
          <input type="file" accept=".json,application/json" class="hidden" onchange="App.importJson(event)" />
        </label>
        <button onclick="App.resetDemoData()" class="w-full h-10 rounded-2xl font-semibold text-xs mt-2" style="background-color: var(--danger-bg); color: var(--danger-fg);">
          重置为默认示例数据
        </button>
      </div>
    `);
  }

  function openSettingsSheet() {
    const s = state.settings;
    openSheet(`
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-base font-bold">照护规则与外观设置</h3>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <div class="space-y-3">
        <div class="flex items-center justify-between p-3 rounded-2xl border" style="border-color: var(--border-card);">
          <div>
            <div class="text-xs font-bold">夜间护眼深色模式 (Night Nursery)</div>
            <div class="text-[11px]" style="color: var(--text-muted);">低蓝光暖黑配色，适合半夜起夜喂奶</div>
          </div>
          <button onclick="App.toggleDarkMode(); App.openSettingsSheet();" class="px-3 py-1.5 rounded-full text-xs font-bold ${s.darkMode ? 'chip-active' : 'chip-base'}">${s.darkMode ? '已开启' : '已关闭'}</button>
        </div>
        <div class="flex items-center justify-between p-3 rounded-2xl border" style="border-color: var(--border-card);">
          <div>
            <div class="text-xs font-bold">白天小睡自动识别时段</div>
            <div class="text-[11px]" style="color: var(--text-muted);">当前设定：0${s.napStartHour}:00 - ${s.napEndHour}:00</div>
          </div>
          <span class="text-xs font-bold" style="color: var(--brand-primary);">已启用</span>
        </div>
        <div class="flex items-center justify-between p-3 rounded-2xl border" style="border-color: var(--border-card);">
          <div>
            <div class="text-xs font-bold">服药间隔到点提醒</div>
            <div class="text-[11px]" style="color: var(--text-muted);">在今日概览卡片实时显示下次可服药倒计时</div>
          </div>
          <span class="text-xs font-bold" style="color: var(--brand-secondary);">已开启</span>
        </div>
        <button onclick="App.clearAllData()" class="w-full h-11 rounded-2xl font-bold text-xs mt-2" style="background-color: var(--danger-bg); color: var(--danger-fg);">
          清空全部本地数据
        </button>
      </div>
    `);
  }

  function openTagManagerSheet() {
    openSheet(`
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-base font-bold">按标签筛选与管理</h3>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <div class="flex flex-wrap gap-2 mb-4">
        <button onclick="App.setTagFilter('all')" class="px-3 py-1.5 rounded-full text-xs font-semibold ${activeTagFilter === 'all' ? 'chip-active' : 'chip-base'}">全部标签</button>
        ${state.tags.map(t => `<button onclick="App.setTagFilter('${t.id}')" class="px-3 py-1.5 rounded-full text-xs font-semibold ${activeTagFilter === t.id ? 'chip-active' : 'chip-base'}"># ${t.name}</button>`).join('')}
      </div>
      <div class="flex gap-2">
        <input id="newTagInput" type="text" placeholder="新建标签（如：添加辅食、发烧观察）" class="flex-1 h-10 px-3 rounded-xl border text-xs outline-none" style="background-color: var(--bg-surface); border-color: var(--border-card);" />
        <button onclick="App.addNewTag()" class="px-4 h-10 rounded-xl text-xs font-bold text-white" style="background-color: var(--brand-primary);">添加</button>
      </div>
    `);
  }

  function openAddQuickTimerSheet() {
    openSheet(`
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-base font-bold">新建快捷计时器</h3>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <input id="quickTimerNameInput" type="text" placeholder="计时名称（如：上午小睡、洗澡、晒太阳）" class="w-full h-11 px-3 rounded-xl border text-xs outline-none mb-3" style="background-color: var(--bg-surface); border-color: var(--border-card);" />
      <div class="grid grid-cols-2 gap-2">
        ${[['feeding','喂养'],['sleep','睡眠'],['tummy','趴卧'],['pumping','吸奶']].map(([t, l]) => `
          <button onclick="App.startCustomTimer('${t}','${l}')" class="h-11 rounded-2xl text-xs font-bold chip-base">启动 · ${l}</button>
        `).join('')}
      </div>
    `);
  }

  function openTimerActionSheet(timerId) {
    const t = state.timers.find(x => x.id === timerId);
    if (!t) return;
    openSheet(`
      <div class="flex items-center justify-between mb-3">
        <div>
          <h3 class="text-base font-bold">正在计时：${t.title}</h3>
          <p class="text-xs font-mono mt-0.5" style="color: var(--brand-primary);">已运行 ${formatHMS(Date.now() - t.start)}</p>
        </div>
        <button onclick="App.closeSheet()" class="w-8 h-8 rounded-full flex items-center justify-center chip-base"><i data-lucide="x" class="w-4 h-4"></i></button>
      </div>
      <div class="grid grid-cols-3 gap-2 mb-3">
        <button onclick="App.adjustTimer('${t.id}', -5)" class="h-10 rounded-xl text-xs font-semibold chip-base">提前 5 分钟</button>
        <button onclick="App.adjustTimer('${t.id}', -15)" class="h-10 rounded-xl text-xs font-semibold chip-base">提前 15 分钟</button>
        <button onclick="App.resetTimerNow('${t.id}')" class="h-10 rounded-xl text-xs font-semibold chip-base">重新开始</button>
      </div>
      <div class="space-y-2">
        <button onclick="App.finishTimerToLog('${t.id}')" class="w-full h-11 rounded-2xl text-xs font-bold text-white" style="background-color: var(--brand-primary);">结束并保存为记录</button>
        <button onclick="App.discardTimer('${t.id}')" class="w-full h-10 rounded-2xl text-xs font-semibold" style="background-color: var(--danger-bg); color: var(--danger-fg);">停止并丢弃此计时器</button>
      </div>
    `);
  }

  // ======================== GLOBAL API ========================
  window.App = {
    init() {
      applyTheme();
      this.switchPage('today');
      this.refreshAll();
      if (timerTickHandle) clearInterval(timerTickHandle);
      timerTickHandle = setInterval(() => {
        const now = Date.now();
        document.querySelectorAll('[data-timer-id]').forEach(el => {
          const t = state.timers.find(x => x.id === el.dataset.timerId);
          if (t) el.textContent = formatHMS(now - t.start);
        });
      }, 1000);
    },

    refreshAll() {
      renderProfileHeader();
      renderSummary();
      renderTimersStrip();
      renderActionGrids();
      renderRecentPreview();
      renderTimelineFilters();
      renderTimeline();
      if (currentPage === 'growth') renderGrowthPage();
      if (currentPage === 'stats') renderStatsView();
    },

    refreshSummary(toast = false) {
      renderSummary();
      if (toast) showToast('今日概览已更新');
    },

    switchPage(page) {
      currentPage = page;
      const pages = {
        today: { el: 'pageToday', btn: 'navBtnToday', sub: '今日照护概览' },
        timeline: { el: 'pageTimeline', btn: 'navBtnTimeline', sub: '完整历史时间线' },
        growth: { el: 'pageGrowth', btn: 'navBtnGrowth', sub: '成长发育与 WHO 曲线' },
        stats: { el: 'pageStats', btn: 'navBtnStats', sub: '日常照护统计报表' },
      };
      Object.entries(pages).forEach(([k, cfg]) => {
        const pageEl = document.getElementById(cfg.el);
        const btnEl = document.getElementById(cfg.btn);
        if (k === page) {
          pageEl.classList.remove('hidden');
          btnEl.style.color = 'var(--brand-primary)';
          document.getElementById('headerPageSubtitle').textContent = cfg.sub;
        } else {
          pageEl.classList.add('hidden');
          btnEl.style.color = 'var(--text-muted)';
        }
      });
      if (page === 'growth') renderGrowthPage();
      if (page === 'stats') renderStatsView();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },

    toggleDarkMode() {
      state.settings.darkMode = !state.settings.darkMode;
      saveState();
      applyTheme();
      showToast(state.settings.darkMode ? '已开启夜间护眼模式' : '已切换为温暖日间模式');
    },

    selectChild(id) {
      state.activeChildId = id;
      saveState();
      this.refreshAll();
    },

    setCategoryFilter(f) {
      activeCategoryFilter = f;
      renderTimelineFilters();
      renderTimeline();
    },

    setTagFilter(tid) {
      activeTagFilter = tid;
      closeSheet();
      renderTimelineFilters();
      renderTimeline();
    },

    setStatsRange(days) {
      statsRangeDays = days;
      renderStatsView();
    },

    setWhoMetric(m) {
      whoMetric = m;
      renderWHOChart(getChildEntries());
    },

    openLogSheet,
    openQuickMenuSheet,
    openBabyProfileModal,
    openBackupSheet,
    openSettingsSheet,
    openTagManagerSheet,
    openAddQuickTimerSheet,
    openTimerActionSheet,
    closeSheet,

    setDraftField(key, val) {
      sheetDraft[key] = val;
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      const timer = state.timers.find(t => t.childId === sheetDraft.childId && t.type === sheetDraft.type);
      renderLogSheetContent(cat, timer);
    },

    toggleDraftBool(key) {
      sheetDraft[key] = !sheetDraft[key];
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      renderLogSheetContent(cat, null);
    },

    setCleanDiaper() {
      sheetDraft.wet = false;
      sheetDraft.solid = false;
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      renderLogSheetContent(cat, null);
    },

    stepDraftAmount(delta) {
      sheetDraft.amount = Math.max(0, (Number(sheetDraft.amount) || 0) + delta);
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      renderLogSheetContent(cat, null);
    },

    stepDraftDosage(delta) {
      sheetDraft.dosage = Math.max(0.5, Number(((Number(sheetDraft.dosage) || 1) + delta).toFixed(1)));
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      renderLogSheetContent(cat, null);
    },

    stepDraftValue(delta) {
      sheetDraft.value = Math.max(0, Number(((Number(sheetDraft.value) || 0) + delta).toFixed(2)));
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      renderLogSheetContent(cat, null);
    },

    presetDraftMinus(mins) {
      const now = Date.now();
      sheetDraft.start = now - mins * 60000;
      sheetDraft.end = now;
      showToast(`已设为 ${mins} 分钟前开始`);
    },

    toggleDraftTag(tid) {
      const arr = sheetDraft.tags || [];
      const idx = arr.indexOf(tid);
      if (idx >= 0) arr.splice(idx, 1);
      else arr.push(tid);
      sheetDraft.tags = arr;
      const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
      renderLogSheetContent(cat, null);
    },

    handleNotePhoto(ev) {
      const file = ev.target.files && ev.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        sheetDraft.photoDataUrl = reader.result;
        const cat = CATEGORIES.find(c => c.id === sheetDraft.type);
        renderLogSheetContent(cat, null);
      };
      reader.readAsDataURL(file);
    },

    saveLogDraft() {
      const d = sheetDraft;
      if (d.type === 'medication' && (!d.medName || !d.medName.trim())) {
        showToast('请输入药品名称');
        return;
      }
      if (d.id) {
        const idx = state.entries.findIndex(e => e.id === d.id);
        if (idx >= 0) state.entries[idx] = { ...d };
      } else {
        // If there was a running timer for this category, stop & clear it
        const tmIdx = state.timers.findIndex(t => t.childId === d.childId && t.type === d.type);
        if (tmIdx >= 0 && d.mode === 'timer') {
          d.start = state.timers[tmIdx].start;
          d.end = Date.now();
          state.timers.splice(tmIdx, 1);
        }
        d.id = uid();
        state.entries.unshift({ ...d });
      }
      saveState();
      closeSheet();
      this.refreshAll();
      showToast('已保存到本地记录');
    },

    deleteEntry(id) {
      state.entries = state.entries.filter(e => e.id !== id);
      saveState();
      this.refreshAll();
      showToast('记录已删除');
    },

    startCategoryTimer(type, label) {
      const child = getActiveChild();
      if (!child) return;
      state.timers.push({ id: uid(), childId: child.id, type, title: label, start: Date.now() });
      saveState();
      closeSheet();
      this.refreshAll();
      showToast(`已启动「${label}」实时计时`);
    },

    startCustomTimer(type, fallbackLabel) {
      const child = getActiveChild();
      if (!child) return;
      const custom = (document.getElementById('quickTimerNameInput')?.value || '').trim();
      state.timers.push({ id: uid(), childId: child.id, type, title: custom || fallbackLabel, start: Date.now() });
      saveState();
      closeSheet();
      this.refreshAll();
      showToast('快捷计时器已开始');
    },

    adjustTimer(id, deltaMins) {
      const t = state.timers.find(x => x.id === id);
      if (!t) return;
      t.start += deltaMins * 60000;
      saveState();
      renderTimersStrip();
      openTimerActionSheet(id);
    },

    resetTimerNow(id) {
      const t = state.timers.find(x => x.id === id);
      if (!t) return;
      t.start = Date.now();
      saveState();
      renderTimersStrip();
      openTimerActionSheet(id);
    },

    finishTimerToLog(id) {
      const t = state.timers.find(x => x.id === id);
      if (!t) return;
      closeSheet();
      setTimeout(() => openLogSheet(t.type), 260);
    },

    discardTimer(id) {
      state.timers = state.timers.filter(x => x.id !== id);
      saveState();
      closeSheet();
      this.refreshAll();
      showToast('计时器已丢弃');
    },

    saveBabyProfile() {
      const d = sheetDraft;
      if (!d.firstName || !d.firstName.trim()) {
        showToast('请输入宝宝名字');
        return;
      }
      if (d.id) {
        const idx = state.children.findIndex(c => c.id === d.id);
        if (idx >= 0) state.children[idx] = { ...d };
      } else {
        d.id = uid();
        state.children.push({ ...d });
        state.activeChildId = d.id;
      }
      saveState();
      closeSheet();
      this.refreshAll();
      showToast('宝宝档案已保存');
    },

    addNewTag() {
      const name = (document.getElementById('newTagInput')?.value || '').trim();
      if (!name) return;
      const palette = ['#E07A5F', '#469E70', '#5B7FD1', '#D99B26', '#A05EB8', '#D84B78'];
      state.tags.push({ id: uid(), name, color: palette[state.tags.length % palette.length] });
      saveState();
      openTagManagerSheet();
      showToast('标签已创建');
    },

    exportJson() {
      const filename = `babybuddy-backup-${new Date().toISOString().slice(0, 10)}.json`;
      const content = JSON.stringify(state, null, 2);
      if (window.HarmonyBridge && typeof window.HarmonyBridge.saveTextFile === 'function') {
        try {
          window.HarmonyBridge.saveTextFile(filename, content, 'application/json');
          showToast('正在打开系统保存面板…');
          return;
        } catch (_) {}
      }
      const blob = new Blob([content], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
      showToast('已导出 JSON 备份');
    },

    exportCsv() {
      const header = ['id', 'childId', 'type', 'start', 'end', 'amount', 'feedingType', 'feedingMethod', 'wet', 'solid', 'stoolColor', 'medName', 'dosage', 'value', 'notes'];
      const rows = state.entries.map(e => header.map(k => `"${String(e[k] ?? '').replace(/"/g, '""')}"`).join(','));
      const csv = '\uFEFF' + [header.join(','), ...rows].join('\n');
      const filename = `babybuddy-records-${new Date().toISOString().slice(0, 10)}.csv`;
      if (window.HarmonyBridge && typeof window.HarmonyBridge.saveTextFile === 'function') {
        try {
          window.HarmonyBridge.saveTextFile(filename, csv, 'text/csv');
          showToast('正在打开系统保存面板…');
          return;
        } catch (_) {}
      }
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
      showToast('已导出 CSV 表格');
    },

    importJson(ev) {
      const file = ev.target.files && ev.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        try {
          const data = JSON.parse(reader.result);
          if (!Array.isArray(data.entries)) throw new Error('无效备份');
          const existingIds = new Set(state.entries.map(e => e.id));
          let added = 0;
          data.entries.forEach(e => {
            if (!existingIds.has(e.id)) { state.entries.push(e); added++; }
          });
          if (Array.isArray(data.children)) {
            const cIds = new Set(state.children.map(c => c.id));
            data.children.forEach(c => { if (!cIds.has(c.id)) state.children.push(c); });
          }
          saveState();
          closeSheet();
          this.refreshAll();
          showToast(`成功合并导入 ${added} 条记录`);
        } catch (err) {
          showToast('备份文件格式无效');
        }
      };
      reader.readAsText(file);
    },

    resetDemoData() {
      state = createSeedState();
      saveState();
      applyTheme();
      closeSheet();
      this.refreshAll();
      showToast('已恢复默认示例数据');
    },

    clearAllData() {
      state.children = [];
      state.activeChildId = null;
      state.entries = [];
      state.timers = [];
      saveState();
      closeSheet();
      this.refreshAll();
      showToast('已清空全部本地数据');
    },

    handleNativeBack() {
      const overlay = document.getElementById('sheetOverlay');
      if (overlay && !overlay.classList.contains('hidden')) {
        if (document.getElementById('poseLabCanvas') && window.PoseWatchLab && typeof window.PoseWatchLab.closeConsole === 'function') {
          window.PoseWatchLab.closeConsole();
        } else {
          closeSheet();
        }
        return 'handled';
      }
      if (activePage !== 'today') {
        this.switchPage('today');
        return 'handled';
      }
      return 'pass';
    },

    applyNativeSafeArea(topVp, bottomVp) {
      const root = document.documentElement;
      if (typeof topVp === 'number' && !Number.isNaN(topVp)) {
        root.style.setProperty('--harmony-safe-top', `${Math.max(0, Math.round(topVp))}px`);
      }
      if (typeof bottomVp === 'number' && !Number.isNaN(bottomVp)) {
        root.style.setProperty('--harmony-safe-bottom', `${Math.max(0, Math.round(bottomVp))}px`);
      }
    }
  };

  document.addEventListener('DOMContentLoaded', () => App.init());
})();
