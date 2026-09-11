/*
 * 沉浸式翻译 · 03 UI（浮动按钮 / 状态展示 / 显示模式）
 * ────────────────────────────────────────────────
 * 页面里唯一可见的控件是右下角一枚圆形按钮，交互刻意压到最少：
 *   · 单击：开启翻译（把看得见的段落先翻出来）/ 再点关闭（译文全部隐藏，原文完好）；
 *   · 长按：在「原文 + 译文对照」与「仅显示译文」之间切换（设置页里也有同样的开关）；
 *   · 失败状态下单击：重试（清掉原生侧的熔断状态并重新扫描视口）。
 *
 * 按钮同时是**状态指示灯**，因为「点了没反应」是最糟的失败方式：
 *   译 → 空闲 / 译 … → 翻译中 / 译 12 → 已翻 12 段 / 译 ↻ → 失败可重试 / 译 ! → 额度用尽
 */
(function () {
  'use strict';

  var IT = window.__bbIT;
  if (!IT) return;

  var state = IT.state;
  var LONG_PRESS_MS = 600;

  var button = null;
  var tip = null;
  var tipTimer = null;
  var longPressed = false;

  function ensureButton() {
    if (button) return button;
    button = document.createElement('div');
    button.id = 'bb-tr-btn';
    button.setAttribute('role', 'button');
    bindPress(button, onTap, onLongPress);
    document.body.appendChild(button);
    return button;
  }

  function bindPress(el, tap, hold) {
    var timer = null;
    var longFired = false;
    var moved = false;

    function clear() {
      if (timer) { clearTimeout(timer); timer = null; }
    }

    el.addEventListener('touchstart', function () {
      longFired = false;
      moved = false;
      clear();
      timer = setTimeout(function () { timer = null; longFired = true; hold(); }, LONG_PRESS_MS);
    }, { passive: true });
    el.addEventListener('touchmove', function () { moved = true; clear(); }, { passive: true });
    el.addEventListener('touchend', function (e) {
      clear();
      if (longFired) { e.preventDefault(); return; }   // 长按后吞掉这次点击
      if (moved) return;
      e.preventDefault();
      tap();
    });
    el.addEventListener('touchcancel', clear, { passive: true });
    // 桌面/无触摸事件时兜底（WebView 里一般走 touch 分支）
    el.addEventListener('click', function () {
      if (longPressed) { longPressed = false; return; }
      tap();
    });
  }

  function deriveStatus() {
    if (state.engine === 'auth') return 'auth';
    if (state.engine === 'quota') return 'quota';
    if (state.engine === 'paused') return 'paused';
    if (state.failed) return 'failed';
    if (state.busy || state.inflight) return 'translating';
    return state.count > 0 ? 'done' : 'idle';
  }

  function refresh() {
    // 按钮就是状态的显示载体：还没建出来就先建（否则「翻译中 / 失败」永远没有出口）
    if (!document.body) return;
    ensureButton();
    var s = deriveStatus();
    state.status = s;

    var label = '译';
    var bg = '#ffffff';
    var fg = '#0969da';

    if (!state.on) {
      label = '译';
    } else if (s === 'translating') {
      label = '译 …';
      bg = '#0969da';
      fg = '#ffffff';
    } else if (s === 'auth') {
      label = '译 !';
      bg = '#cf222e';
      fg = '#ffffff';
    } else if (s === 'quota') {
      label = '译 !';
      bg = '#bf8700';
      fg = '#ffffff';
    } else if (s === 'failed' || s === 'paused') {
      label = '译 ↻';
      bg = '#cf222e';
      fg = '#ffffff';
    } else {
      label = state.count ? '译 ' + state.count : '译 …';
      bg = '#0969da';
      fg = '#ffffff';
    }

    button.textContent = label;
    button.title = buttonTitle(s);
    button.style.background = bg;
    button.style.color = fg;
    button.classList.toggle('bb-tr-btn-on', state.on);
  }

  function buttonTitle(s) {
    if (!state.on) return '沉浸式翻译：原文 + 译文对照（长按切换显示方式）';
    if (s === 'auth') return 'API Key 无效或未配置：请到「设置 → 沉浸式翻译」检查后点此重试';
    if (s === 'quota') return '翻译额度已用尽，点按重试';
    if (s === 'failed' || s === 'paused') return '翻译失败，点按重试';
    return '隐藏译文（保留原文）· 长按切换「对照 / 仅译文」';
  }

  function ensureTip() {
    if (tip) return tip;
    tip = document.createElement('div');
    tip.id = 'bb-tr-tip';
    document.body.appendChild(tip);
    return tip;
  }

  function showTip(text, ms) {
    ensureTip();
    tip.textContent = text;
    tip.classList.add('bb-tr-tip-show');
    if (tipTimer) clearTimeout(tipTimer);
    tipTimer = setTimeout(function () {
      tip.classList.remove('bb-tr-tip-show');
      tipTimer = null;
    }, ms || 3600);
  }

  function onTap() {
    var s = deriveStatus();
    // 失败状态下单击 = 重试（此时用户想要的是「再试一次」，而不是关掉功能）
    if (state.on && (s === 'auth' || s === 'quota' || s === 'failed' || s === 'paused')) {
      state.failed = false;
      IT.retry();
      showTip(s === 'auth'
        ? '已重试：若仍是「译 !」，请到「设置 → 沉浸式翻译」检查 API Key'
        : '正在重试…', s === 'auth' ? 5200 : 3600);
      return;
    }
    if (state.on) IT.off(); else IT.on();
  }

  function onLongPress() {
    longPressed = true;
    IT.toggleDual();
  }

  function fail(kind) {
    state.failed = kind === 'failed';
    if (kind === 'auth') {
      state.engine = 'auth';
      showTip('API Key 无效或未配置：请到「设置 → 沉浸式翻译」检查后点按钮重试', 6000);
    } else if (kind === 'quota') {
      state.engine = 'quota';
      showTip('翻译额度已用尽，点按钮可重试', 5000);
    } else if (kind === 'paused') {
      state.engine = 'paused';
      showTip('连续翻译失败，已暂停；点按钮可重试', 5000);
    } else {
      showTip('翻译失败，点按钮可重试', 4000);
    }
    refresh();
  }

  IT.ui = {
    refresh: refresh,
    fail: fail,
    showTip: showTip
  };
})();
