/*
 * 沉浸式翻译 · 03 悬浮球（可移动 / 状态灯 / 视口吸附）
 * ─────────────────────────────────────────────────
 * **只在翻译模式下出现**：页面里唯一的控件是这枚圆形悬浮球（关掉本页翻译就整层收起，
 * 页面上不留东西；开启入口在设置页的「自动翻译正文」总开关）。交互只有两种：
 *   · 单击：展开「翻译工具选项菜单」（见 04-panel.js）——球本身**不是**开关；
 *   · 拖动：球跟着手指走，松手后停在原地（横向按比例、纵向按「距可见带下沿的距离」
 *     记进 localStorage），拖动过程中不展开面板。
 *
 * 两条硬约束（都是踩过的坑）：
 *
 * 1. **不能用 `position: fixed`**。正文 WebView 的高度等于整篇内容的高度（App 侧按内容
 *    撑开，滚动由外层原生列表负责），fixed 钉住的是**整篇文章**的右下角 —— 长 README 里
 *    悬浮球会落到文末，用户根本看不见。因此这里改用绝对定位，位置由原生侧推来的
 *    「可见带」（`window.__bbIT.viewport(top, bottom)`，文档坐标）算出来；
 *    几何还没到（老原生 / 推送失败）时退回 fixed，至少保证按钮能点到。
 * 2. **面板必须活在可见带里**。球与面板的定位都钳制在 [top, bottom] 内，
 *    所以既不会盖住顶部栏，也不会压到底部导航条；带子消失（正文滚出屏幕）时整层隐藏。
 *
 * 球同时是状态指示灯（「点了没反应」是最糟的失败方式）：
 *   译 → 空闲 / 译 … → 翻译中 / 译 12 → 已翻 12 段 / 译 ↻ → 失败可重试 / 译 ! → 额度用尽 / Key 无效
 */
(function () {
  'use strict';

  var IT = window.__bbIT;
  if (!IT) return;

  var state = IT.state;

  var FAB_SIZE = 44;      // 球直径（触控目标 ≥ 44px，别为了好看改小）
  var MARGIN = 8;         // 与可见带边缘的最小间距
  var TAP_SLOP = 6;       // 位移小于这个值仍算「单击」，超过算拖动
  var POS_KEY = 'bb_translate_fab_pos';

  var fab = null;
  var glyph = null;
  var badge = null;
  var tip = null;
  var tipTimer = null;

  // 球的位置。两种定位模式：
  //   fixed    —— 几何未知时的兜底：right/bottom 固定（与旧版行为一致）
  //   viewport —— 几何已知：left/top 绝对定位，随可见带移动
  var mode = 'fixed';
  var dragging = false;
  // 横向按可见宽度的比例（0~1，球心），纵向按「球下沿距可见带下沿的距离」
  var pos = { xf: 1, yb: 18 };

  /* ───────────── 位置持久化 ───────────── */

  function loadPos() {
    try {
      var raw = localStorage.getItem(POS_KEY);
      if (!raw) return;
      var p = JSON.parse(raw);
      if (p && typeof p.xf === 'number' && typeof p.yb === 'number') {
        pos.xf = Math.min(1, Math.max(0, p.xf));
        pos.yb = Math.min(4000, Math.max(0, p.yb));
      }
    } catch (e) { /* 存储不可用就用默认位置 */ }
  }

  function savePos() {
    try { localStorage.setItem(POS_KEY, JSON.stringify(pos)); } catch (e) {}
  }

  /** 面板上的「重置悬浮球位置」：回到右下角默认位。 */
  function resetPosition() {
    pos.xf = 1;
    pos.yb = 18;
    savePos();
    layout();
  }

  /* ───────────── DOM ───────────── */

  function ensureFab() {
    if (fab) return fab;
    fab = document.createElement('div');
    fab.id = 'bb-tr-fab';
    fab.setAttribute('role', 'button');
    fab.setAttribute('aria-label', '沉浸式翻译工具');
    glyph = document.createElement('span');
    glyph.className = 'bb-tr-fab-glyph';
    badge = document.createElement('span');
    badge.className = 'bb-tr-fab-badge';
    fab.appendChild(glyph);
    fab.appendChild(badge);
    bindDrag(fab);
    document.body.appendChild(fab);
    return fab;
  }

  function ensureTip() {
    if (tip) return tip;
    tip = document.createElement('div');
    tip.id = 'bb-tr-tip';
    document.body.appendChild(tip);
    return tip;
  }

  /* ───────────── 几何 ───────────── */

  function viewWidth() {
    return document.documentElement ? document.documentElement.clientWidth : window.innerWidth;
  }

  /**
   * 悬浮球只在**翻译模式**下出现（关闭翻译 = 球立刻收起）。
   *
   * 这是刻意的产品取舍：开启入口收进「设置 → 沉浸式翻译 → 自动翻译正文」（总开关），
   * 页面上就只剩「翻译进行时」的一枚球 —— 不用翻译的页面/时候，屏幕干干净净。
   * 关掉后想再开：去设置里打开总开关（或在设置里关掉再打开一次，让页内开关状态复位）。
   */
  function wanted() {
    return state.on === true;
  }

  function layout() {
    if (!fab) return;
    var v = state.view;
    var w = viewWidth();

    // 正文整体滚出屏幕：连球一起收起来（页面上没有它的位置了）
    if (v.ready && (v.bottom - v.top) < 60) {
      hide();
      return;
    }
    if (!wanted()) {
      hide();
      return;
    }
    // 面板展开时球让位（「点球 = 变成面板」）；收起时由面板调 setBallVisible(true) 让它回来
    if (IT.panel && IT.panel.isOpen()) {
      fab.style.display = 'none';
      return;
    }
    fab.style.display = '';

    if (v.ready && mode !== 'viewport') {
      mode = 'viewport';
      fab.style.position = 'absolute';
      fab.style.right = 'auto';
      fab.style.bottom = 'auto';
    } else if (!v.ready && mode !== 'fixed') {
      mode = 'fixed';
      fab.style.position = 'fixed';
      fab.style.left = 'auto';
      fab.style.top = 'auto';
      fab.style.right = '14px';
      fab.style.bottom = '16px';
    }
    if (mode === 'fixed') return;   // 兜底模式：位置交给 CSS，不再计算

    var bandW = w - MARGIN * 2;
    if (bandW < FAB_SIZE) bandW = FAB_SIZE;
    var left = MARGIN + (bandW - FAB_SIZE) * clamp01(pos.xf);
    var top = v.bottom - pos.yb - FAB_SIZE;
    if (top < v.top + 4) top = v.top + 4;
    if (top > v.bottom - FAB_SIZE - 4) top = v.bottom - FAB_SIZE - 4;
    fab.style.left = Math.round(left) + 'px';
    fab.style.top = Math.round(top) + 'px';
  }

  function hide() {
    if (fab) fab.style.display = 'none';
    if (IT.panel && IT.panel.isOpen()) IT.panel.close();
  }

  /** 面板展开/收起时切换球的可见性（收起时走一遍 layout()，该隐藏的仍会隐藏）。 */
  function setBallVisible(visible) {
    if (!fab) return;
    if (visible) layout();
    else fab.style.display = 'none';
  }

  function clamp01(x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

  /* ───────────── 拖拽 / 单击 ───────────── */

  function bindDrag(el) {
    var startX = 0, startY = 0, startLeft = 0, startTop = 0;
    var moved = false, tracking = false;
    // touch 分支已经处理过这一次点击，后面的合成 click 必须吞掉 ——
    // 否则「touchend 打开面板 + click 再关掉」会变成点了没反应
    var swallowClick = false;
    // 触摸设备在 touchend 之后还会补发一整套鼠标事件（mousedown/mouseup/click）：
    // 一旦走过触摸分支，后面的鼠标事件一律忽略，否则一次点按会被处理两遍（开了又关）
    var sawTouch = false;

    function point(e) {
      var t = e.touches && e.touches[0] ? e.touches[0] : e;
      return { x: t.clientX, y: t.clientY };
    }

    function onDown(e) {
      if (e.type === 'touchstart') sawTouch = true;
      else if (sawTouch) return;
      if (mode !== 'viewport') return;      // 兜底模式不允许拖动（没有可参照的带子）
      var p = point(e);
      startX = p.x; startY = p.y;
      startLeft = parseFloat(el.style.left) || 0;
      startTop = parseFloat(el.style.top) || 0;
      moved = false;
      tracking = true;
    }

    function onMove(e) {
      if (!tracking) return;
      var p = point(e);
      var dx = p.x - startX, dy = p.y - startY;
      if (!dx && !dy) return;
      if (!moved && Math.abs(dx) + Math.abs(dy) < TAP_SLOP) return;
      moved = true;
      dragging = true;
      var v = state.view;
      var w = viewWidth();
      var size = FAB_SIZE;
      var left = startLeft + dx;
      var top = startTop + dy;
      // 拖动过程中就已经钳制在可见带里：松手不需要再纠正，也不会「拖出去再弹回来」
      left = Math.max(MARGIN, Math.min(left, w - MARGIN - size));
      top = Math.max(v.top + 4, Math.min(top, v.bottom - size - 4));
      el.style.left = Math.round(left) + 'px';
      el.style.top = Math.round(top) + 'px';
      // 触摸拖动要吞掉默认行为，否则手指一动就变成滚页面
      if (e.cancelable) e.preventDefault();
    }

    function onUp() {
      if (!tracking) return;
      tracking = false;
      swallowClick = true;
      setTimeout(function () { swallowClick = false; }, 400);
      if (!moved) {
        dragging = false;
        if (IT.panel) IT.panel.toggle();
        return;
      }
      var v = state.view;
      var w = viewWidth();
      var bandW = w - MARGIN * 2;
      var left = parseFloat(el.style.left) || 0;
      var top = parseFloat(el.style.top) || 0;
      pos.xf = bandW > FAB_SIZE ? (left - MARGIN) / (bandW - FAB_SIZE) : 1;
      pos.yb = Math.max(0, v.bottom - (top + FAB_SIZE));
      savePos();
      dragging = false;
    }

    el.addEventListener('touchstart', onDown, { passive: true });
    el.addEventListener('touchmove', onMove, { passive: false });
    el.addEventListener('touchend', onUp);
    el.addEventListener('touchcancel', function () {
      tracking = false;
      dragging = false;
      swallowClick = true;
      setTimeout(function () { swallowClick = false; }, 400);
    });
    // 桌面 / 无触摸事件时兜底（WebView 里一般走 touch 分支）
    el.addEventListener('mousedown', onDown);
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    el.addEventListener('click', function (e) {
      if (sawTouch || swallowClick || dragging) { e.preventDefault(); e.stopPropagation(); return; }
      if (IT.panel) IT.panel.toggle();
    });
  }

  /* ───────────── 状态灯 ───────────── */

  function deriveStatus() {
    if (state.engine === 'auth') return 'auth';
    if (state.engine === 'quota') return 'quota';
    if (state.engine === 'paused') return 'paused';
    if (state.failed) return 'failed';
    if (state.busy || state.inflight) return 'translating';
    return state.count > 0 ? 'done' : 'idle';
  }

  function refresh() {
    if (!document.body) return;
    ensureFab();
    var s = deriveStatus();
    state.status = s;

    var label = '译';
    var cls = '';
    if (state.on) {
      if (s === 'translating') { label = '…'; cls = 'bb-tr-fab-busy'; }
      else if (s === 'auth' || s === 'quota' || s === 'failed' || s === 'paused') {
        label = s === 'quota' ? '!' : '↻';
        cls = s === 'quota' ? 'bb-tr-fab-warn' : 'bb-tr-fab-bad';
      } else if (state.count > 0) { label = '译'; cls = 'bb-tr-fab-busy'; }
      else { label = '…'; cls = 'bb-tr-fab-busy'; }
    }
    glyph.textContent = label;
    fab.className = cls;

    // 徽标：已译段数（>0 才显示）；失败态给一个感叹号，点开面板能看到原因
    var badgeText = '';
    if (s === 'auth') badgeText = '!';
    else if (s === 'quota') badgeText = '!';
    else if (s === 'failed' || s === 'paused') badgeText = '↻';
    else if (state.count > 0) badgeText = String(state.count);
    badge.textContent = badgeText;
    badge.style.display = badgeText ? '' : 'none';

    fab.title = fabTitle(s);
    layout();
    if (IT.panel) IT.panel.refresh();
  }

  function fabTitle(s) {
    if (!state.on) return '沉浸式翻译：点开工具菜单（可拖动）';
    if (s === 'auth') return 'API Key 无效或未配置：点开菜单处理';
    if (s === 'quota') return '翻译额度已用尽：点开菜单重试';
    if (s === 'failed' || s === 'paused') return '翻译失败：点开菜单重试';
    return '点开翻译工具菜单（可拖动）';
  }

  function showTip(text, ms) {
    ensureTip();
    tip.textContent = text;
    tip.classList.add('bb-tr-tip-show');
    positionTip();
    if (tipTimer) clearTimeout(tipTimer);
    tipTimer = setTimeout(function () {
      tip.classList.remove('bb-tr-tip-show');
      tipTimer = null;
    }, ms || 3200);
  }

  /**
   * 提示气泡的落点。
   *
   * - 常态：贴着悬浮球（球在右半边就贴右侧，避免出界）；
   * - 面板展开时：挪到**可见带顶部** —— 面板贴在球附近，提示压在面板上就会
   *   「挡住面板内容」，而面板又永远盖不住提示（层级见 translate.css）。
   */
  function positionTip() {
    if (!tip) return;
    var v = state.view;
    if (mode !== 'viewport' || !v.ready) return;
    tip.style.position = 'absolute';
    var w = viewWidth();
    tip.style.maxWidth = Math.max(160, w - 32) + 'px';
    if (IT.panel && IT.panel.isOpen()) {
      tip.style.left = MARGIN + 'px';
      tip.style.right = 'auto';
      tip.style.top = Math.round(v.top + 8) + 'px';
      return;
    }
    var left = parseFloat(fab.style.left) || 0;
    var top = parseFloat(fab.style.top) || 0;
    var w = viewWidth();
    tip.style.left = 'auto';
    tip.style.right = 'auto';
    // 默认贴球的左下方；球靠左时改成贴右，避免气泡出界
    if (left > w / 2) tip.style.right = Math.round(w - (left + FAB_SIZE)) + 'px';
    else tip.style.left = Math.round(left) + 'px';
    var above = top - 40;
    if (above < v.top + 4) {
      tip.style.top = Math.round(Math.min(v.bottom - 40, top + FAB_SIZE + 6)) + 'px';
    } else {
      tip.style.top = Math.round(above) + 'px';
    }
  }

  function fail(kind) {
    state.failed = kind === 'failed';
    if (kind === 'auth') {
      state.engine = 'auth';
      showTip('API Key 无效或未配置：点开悬浮球到「翻译工具」里重试，或去设置检查 Key', 6000);
    } else if (kind === 'quota') {
      state.engine = 'quota';
      showTip('翻译额度已用尽：点开悬浮球可重试', 5000);
    } else if (kind === 'paused') {
      state.engine = 'paused';
      showTip('连续翻译失败，已暂停：点开悬浮球可重试', 5000);
    } else {
      showTip('翻译失败：点开悬浮球可重试', 4000);
    }
    refresh();
  }

  /* ───────────── 对外 ───────────── */

  loadPos();
  IT.onViewport(function (_top, _bottom) { layout(); });

  IT.ui = {
    refresh: refresh,
    fail: fail,
    showTip: showTip,
    resetPosition: resetPosition,
    layout: layout,
    hide: hide,
    setBallVisible: setBallVisible,
    isDragging: function () { return dragging; }
  };
})();
