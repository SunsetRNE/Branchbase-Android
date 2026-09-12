/*
 * 沉浸式翻译 · 04 工具面板（点悬浮球展开的「翻译工具选项菜单」）
 * ────────────────────────────────────────────────────────────────
 * 悬浮球单击后，球让位给这块面板（球隐藏，面板自带关闭按钮）：
 *
 *   · 关闭的三种方式：面板右上角 ×、点击面板之外的任意位置（页面全局捕获点击并吞掉，
 *     不会顺手点开下面的链接）、正文滚出屏幕时由 03-fab.js 一起收起；
 *   · 数据（比悬浮球上的数字精确）：本页已译 / 候选段数、候选字符数、进度条、
 *     翻译服务、目标语言、当前状态；
 *   · 快捷设置：翻译开关 / 显示方式 / 译文样式 / 目标语言 / 自动翻译 / 本地缓存 /
 *     保护代码与链接 —— 全部即时生效，并回写原生设置（见 01-core.js 的 pref()），
 *     所以和「设置 → 沉浸式翻译」永远是同一份配置；
 *   · 操作：重试（失败时）、翻译当前视口、翻译全文、清空本页译文、重置悬浮球位置。
 *
 * ## 两条布局硬约束
 *
 * 1. **完整显示**：面板高度上限 = 可见带高度 - 16px，超出部分在面板**内部**滚动
 *    （`.bb-tr-panel-body`），不会把内容裁掉，也不会顶出屏幕；
 * 2. **不覆盖导航**：定位被钳制在可见带 [top, bottom] 内 —— 这条带子由原生侧给出，
 *    已经扣掉了顶部栏与底部导航条（见 03-fab.js 与 ReadmeWebView 的视口推送）。
 */
(function () {
  'use strict';

  var IT = window.__bbIT;
  if (!IT) return;

  var state = IT.state;

  var PANEL_WIDTH = 272;   // 面板目标宽度（窄屏会自动收窄）
  var GAP = 10;            // 面板与悬浮球之间的间距
  var EDGE = 8;            // 与可见带边缘的最小间距

  var panel = null;
  var body = null;
  var open = false;
  var openedAt = 0;

  /** 重数候选段数（实现在 05-boot.js；脚本缺失时静默跳过，不影响翻译本身）。 */
  function countCandidates() {
    if (IT.refreshCandidates) IT.refreshCandidates();
  }

  /* ───────────── 骨架 ───────────── */

  function ensure() {
    if (panel) return panel;
    panel = document.createElement('div');
    panel.id = 'bb-tr-panel';
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', '翻译工具选项菜单');
    panel.style.display = 'none';

    var seg = function (group, rows) {
      var out = '<div class="bb-tr-seg">';
      for (var i = 0; i < rows.length; i++) {
        out += '<button type="button" class="bb-tr-seg-btn" data-seg="' + group +
          '" data-val="' + rows[i][0] + '">' + rows[i][1] + '</button>';
      }
      return out + '</div>';
    };
    var sw = function (key, label) {
      return '<div class="bb-tr-row"><span class="bb-tr-row-label">' + label + '</span>' +
        '<button type="button" class="bb-tr-switch" data-sw="' + key + '"><i></i></button></div>';
    };
    var action = function (act, label, cls) {
      return '<button type="button" class="bb-tr-act' + (cls ? ' ' + cls : '') +
        '" data-act="' + act + '">' + label + '</button>';
    };

    panel.innerHTML = [
      '<div class="bb-tr-panel-head">',
      '  <span class="bb-tr-panel-title">沉浸式翻译</span>',
      '  <span class="bb-tr-panel-status" id="bb-tr-p-status">空闲</span>',
      '  <button type="button" class="bb-tr-panel-close" data-act="close" aria-label="关闭" title="关闭">×</button>',
      '</div>',
      '<div class="bb-tr-panel-body" id="bb-tr-p-body">',

      '  <div class="bb-tr-block">',
      '    <div class="bb-tr-block-title">本页进度</div>',
      '    <div class="bb-tr-prog"><i id="bb-tr-p-prog"></i></div>',
      '    <div class="bb-tr-kv"><span id="bb-tr-p-progtxt">已译 0 / 候选 0 段</span>' +
        '<span id="bb-tr-p-chars">0 字符</span></div>',
      '    <div class="bb-tr-kv"><span id="bb-tr-p-service">翻译服务</span>' +
        '<span id="bb-tr-p-target">目标语言</span></div>',
      '  </div>',

      '  <div class="bb-tr-block">',
      '    <div class="bb-tr-block-title">快捷设置</div>',
      sw('on', '本页翻译'),
      '    <div class="bb-tr-row"><span class="bb-tr-row-label">显示方式</span>' +
        seg('dual', [['1', '原文+译文'], ['0', '仅译文']]) + '</div>',
      '    <div class="bb-tr-row"><span class="bb-tr-row-label">译文样式</span>' +
        seg('style', [['card', '卡片'], ['underline', '下划线'], ['plain', '淡灰']]) + '</div>',
      '    <div class="bb-tr-row"><span class="bb-tr-row-label">目标语言</span>' +
        seg('target', [['zh-CN', '中文'], ['en', 'English']]) + '</div>',
      sw('enabled', '自动翻译正文'),
      sw('persist', '本地缓存'),
      sw('protect', '保护代码与链接'),
      '  </div>',

      '  <div class="bb-tr-block">',
      '    <div class="bb-tr-block-title">操作</div>',
      '    <div class="bb-tr-actions">',
      '      <button type="button" class="bb-tr-act bb-tr-act-danger" id="bb-tr-p-retry" data-act="retry">重试</button>',
      action('scan-visible', '翻译当前视口'),
      action('scan-all', '翻译全文'),
      action('clear', '清空本页译文'),
      action('reset-pos', '重置悬浮球'),
      '    </div>',
      '    <div class="bb-tr-hint">更多设置：App「设置 → 沉浸式翻译」</div>',
      '  </div>',

      '</div>',
    ].join('\n');

    body = panel.querySelector('#bb-tr-p-body');
    panel.addEventListener('click', onPanelClick);
    document.body.appendChild(panel);
    return panel;
  }

  /* ───────────── 打开 / 关闭 / 定位 ───────────── */

  function isOpen() { return open; }

  function toggle() { if (open) close(); else show(); }

  function show() {
    ensure();
    open = true;
    openedAt = Date.now();
    countCandidates();
    IT.ui.setBallVisible(false);   // 球让位：点球 = 变成面板
    panel.style.display = '';
    refresh();                     // 填数据 + 按可见带贴边（内部会 position()）
    // 打开后立刻接管「点空白处收起」；capture 阶段先于页面自身的链接点击
    document.addEventListener('click', onDocClick, true);
  }

  function close() {
    if (!panel) return;
    open = false;
    panel.style.display = 'none';
    IT.ui.setBallVisible(true);    // 面板收起 → 变回悬浮球
  }

  function onDocClick(e) {
    if (!open) return;
    var t = e.target;
    // 悬浮球自己的点击由 03-fab.js 负责切换，这里别抢（否则会「开了立刻关」）
    if (t && t.closest && t.closest('#bb-tr-fab')) return;
    if (panel && panel.contains(t)) return;
    if (Date.now() - openedAt < 120) return;   // 刚打开时的同一次手势
    // 点面板之外 = 收起面板，并且**不让这一下穿透到正文**（否则会误开链接）
    if (e.cancelable) e.preventDefault();
    e.stopPropagation();
    close();
  }

  function viewWidth() {
    return document.documentElement ? document.documentElement.clientWidth : window.innerWidth;
  }

  /** 把面板摆进可见带：优先贴在球的上方，放不下就翻到下方，始终不出界。 */
  function position() {
    var v = state.view;
    var w = viewWidth();
    var width = Math.min(PANEL_WIDTH, Math.max(180, w - EDGE * 2));
    panel.style.width = width + 'px';

    // 高度上限 = 可见带高度 - 边距；面板内部滚动，所以内容不会被裁
    var bandH = v.ready ? (v.bottom - v.top) : (window.innerHeight || 600);
    panel.style.maxHeight = Math.max(160, bandH - EDGE * 2) + 'px';

    if (!v.ready) {
      // 几何未知（兜底模式）：贴在屏幕右下角
      panel.style.left = 'auto';
      panel.style.right = '14px';
      panel.style.top = 'auto';
      panel.style.bottom = '72px';
      return;
    }

    var fabEl = document.getElementById('bb-tr-fab');
    var fabTop = fabEl ? (parseFloat(fabEl.style.top) || 0) : 0;
    var fabLeft = fabEl ? (parseFloat(fabEl.style.left) || 0) : 0;
    var fabSize = 44;
    var h = panel.offsetHeight || 0;

    var top = fabTop - GAP - h;
    if (top < v.top + EDGE) top = fabTop + fabSize + GAP;         // 上方放不下 → 翻到下方
    if (top + h > v.bottom - EDGE) top = v.bottom - EDGE - h;     // 还是超了就贴下沿
    if (top < v.top + EDGE) top = v.top + EDGE;
    var left = fabLeft + fabSize - width;
    if (left < EDGE) left = EDGE;
    if (left + width > w - EDGE) left = w - EDGE - width;
    panel.style.left = Math.round(left) + 'px';
    panel.style.top = Math.round(top) + 'px';
    panel.style.right = 'auto';
    panel.style.bottom = 'auto';
  }

  /* ───────────── 交互 ───────────── */

  function onPanelClick(e) {
    var t = e.target;
    var act = t.closest ? t.closest('[data-act]') : null;
    if (act) { doAction(act.getAttribute('data-act')); return; }
    var sg = t.closest ? t.closest('[data-seg]') : null;
    if (sg) { setSegment(sg.getAttribute('data-seg'), sg.getAttribute('data-val')); return; }
    var sw = t.closest ? t.closest('[data-sw]') : null;
    if (sw) toggleSwitch(sw.getAttribute('data-sw'));
  }

  function doAction(act) {
    if (act === 'close') { close(); return; }
    if (act === 'retry') {
      state.failed = false;
      state.engine = 'ok';
      IT.retry();
      IT.ui.showTip('正在重试…');
      return;
    }
    if (act === 'scan-visible') {
      IT.enqueue(IT.dom.scan(true));
      countCandidates();
      IT.ui.showTip('正在翻译当前视口…');
      return;
    }
    if (act === 'scan-all') {
      IT.enqueue(IT.dom.scan(false));
      countCandidates();
      IT.ui.showTip('正在翻译全文…');
      return;
    }
    if (act === 'clear') {
      IT.dom.clear();
      countCandidates();
      IT.ui.refresh();
      IT.ui.showTip('已清空本页译文');
      return;
    }
    if (act === 'reset-pos') {
      IT.ui.resetPosition();
      IT.ui.showTip('悬浮球已回到默认位置');
    }
  }

  function setSegment(group, value) {
    if (group === 'dual') {
      IT.setDual(value === '1');
      return;
    }
    if (group === 'style') {
      IT.setStyle(value);
      return;
    }
    if (group === 'target') {
      IT.setTarget(value);
    }
  }

  function toggleSwitch(key) {
    if (key === 'on') {
      if (state.on) IT.off(); else IT.on();
      return;
    }
    var next = !state.settings[key];
    IT.pref(key, next ? '1' : '0');
    if (key === 'enabled') IT.ui.showTip(next ? '已开启自动翻译：下次进正文页自动开始' : '已关闭自动翻译');
    if (key === 'persist') IT.ui.showTip(next ? '译文将落盘缓存' : '不再读写磁盘缓存');
    if (key === 'protect') IT.ui.showTip(next ? '已开启占位符保护' : '已关闭占位符保护（URL 等可能被改写）');
    refresh();
  }

  /* ───────────── 渲染 ───────────── */

  var STATUS_TEXT = {
    idle: '空闲',
    translating: '翻译中…',
    done: '已完成',
    failed: '翻译失败',
    paused: '已暂停',
    quota: '额度用尽',
    auth: 'Key 无效'
  };
  var STATUS_CLASS = {
    idle: '', done: 'bb-tr-st-ok', translating: 'bb-tr-st-busy',
    failed: 'bb-tr-st-bad', paused: 'bb-tr-st-bad', quota: 'bb-tr-st-warn', auth: 'bb-tr-st-bad'
  };
  var STYLE_TEXT = { card: '卡片', underline: '下划线', plain: '淡灰' };
  var TARGET_TEXT = { 'zh-CN': '中文', en: 'English' };

  function setText(id, text) {
    var el = panel.querySelector('#' + id);
    if (el) el.textContent = text;
  }

  function markSegments(group, value) {
    var btns = panel.querySelectorAll('[data-seg="' + group + '"]');
    for (var i = 0; i < btns.length; i++) {
      btns[i].className = 'bb-tr-seg-btn' +
        (btns[i].getAttribute('data-val') === String(value) ? ' bb-tr-seg-on' : '');
    }
  }

  function markSwitch(key, on) {
    var el = panel.querySelector('[data-sw="' + key + '"]');
    if (el) el.className = 'bb-tr-switch' + (on ? ' bb-tr-switch-on' : '');
  }

  function refresh() {
    if (!open || !panel) return;
    var s = state.status || 'idle';
    var status = STATUS_TEXT[s] || '空闲';
    var statusEl = panel.querySelector('#bb-tr-p-status');
    if (statusEl) {
      statusEl.textContent = status;
      statusEl.className = 'bb-tr-panel-status ' + (STATUS_CLASS[s] || '');
    }

    var c = state.candidates || { count: 0, chars: 0 };
    var done = state.count || 0;
    var total = Math.max(c.count, done);
    var pct = total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0;
    var bar = panel.querySelector('#bb-tr-p-prog');
    if (bar) bar.style.width = pct + '%';
    setText('bb-tr-p-progtxt', '已译 ' + done + ' / 候选 ' + total + ' 段');
    setText('bb-tr-p-chars', c.chars.toLocaleString() + ' 字符 · ' + pct + '%');
    setText('bb-tr-p-service', '服务：' + (IT.cfg.provider === 'deepseek' ? 'DeepSeek' : 'MyMemory'));
    setText('bb-tr-p-target', '目标：' + (TARGET_TEXT[state.to] || state.to) +
      ' · ' + (STYLE_TEXT[state.settings.style] || '卡片'));

    markSegments('dual', state.dual ? '1' : '0');
    markSegments('style', state.settings.style);
    markSegments('target', state.to);
    markSwitch('on', state.on);
    markSwitch('enabled', !!state.settings.enabled);
    markSwitch('persist', !!state.settings.persist);
    markSwitch('protect', !!state.settings.protect);

    var retry = panel.querySelector('#bb-tr-p-retry');
    if (retry) retry.style.display = (s === 'failed' || s === 'paused' || s === 'quota' || s === 'auth') ? '' : 'none';

    // 已译 / 候选变化会改变内容高度（比如重试按钮出现），重新贴一次边
    position();
  }

  /* 视口变化时面板跟着走（正文滚动、屏幕旋转） */
  IT.onViewport(function () {
    if (open) position();
  });

  IT.panel = {
    show: show,
    close: close,
    toggle: toggle,
    isOpen: isOpen,
    refresh: refresh
  };
})();
