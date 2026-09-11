/*
 * 沉浸式翻译（原文 + 译文对照）—— README / Issue / PR / Release 正文页面脚本。
 *
 * 行为对齐「沉浸式翻译」的网页体验，但全部在本地 HTML 里跑：
 *   1. 右下角一枚浮动按钮：点一下开始翻译当前页面，再点切换「显示译文 / 只看原文」；
 *   2. **视口优先**：只翻当前看得到的段落，滚动到的再翻（避免一次几百个请求把额度烧光）；
 *   3. **原文不动**：译文插入在原段落之后（`.bb-tr`），原文完整保留，可随时隐藏；
 *   4. 状态记在 localStorage，同一台设备再次打开默认沿用上次的选择。
 *
 * 与原生侧的分工：
 *   原生 Kotlin 通过 `BBTranslate.request(id, json)` 收到一批待译文本，
 *   串行翻译完再回调 `window.__bbTranslated(id, 译文数组)`；本脚本只负责
 *   「找段落 / 发起请求 / 插入结果 / 开关显示」，不关心用哪家翻译服务。
 */
(function () {
  if (window.__bbImmersiveLoaded) return;
  window.__bbImmersiveLoaded = true;

  // 原生侧注入的设置（window.__bbTranslate）；缺省 = 手动模式（点按钮才翻）
  var CFG = window.__bbTranslate || {};
  var ON_KEY = 'bb_translate_on';
  var BATCH = 3;          // 一次最多几段（服务端对并发不友好，原生侧还会再串行化）
  var MAX_LEN = 1200;     // 单段超过这个长度直接跳过：贴日志的段落翻出来也没人看

  var state = {
    on: false,
    auto: CFG.enabled === true,       // 设置里「自动翻译正文」
    to: CFG.to || 'zh-CN',            // 目标语言（中英两向）
    dual: CFG.dual !== false,         // true = 原文+译文对照；false = 仅译文
    seq: 0,
    callbacks: {},
    queue: [],
    inflight: false,
    seen: (typeof WeakSet === 'function') ? new WeakSet() : null,
    button: null,
    count: 0
  };

  /* ───────────── 与原生侧的异步桥 ───────────── */

  window.__bbTranslated = function (id, toLang, json) {
    var cb = state.callbacks[id];
    if (!cb) return;
    delete state.callbacks[id];
    var list = [];
    try { list = JSON.parse(json) || []; } catch (e) { list = []; }
    cb(list);
  };

  function requestTranslate(texts, cb) {
    if (!window.BBTranslate || !texts.length) { cb([]); return; }
    var id = 'r' + (++state.seq);
    state.callbacks[id] = cb;
    try {
      window.BBTranslate.request(id, state.to, JSON.stringify(texts));
    } catch (e) {
      delete state.callbacks[id];
      cb([]);
    }
  }

  /* ───────────── 段落收集 ───────────── */

  // 只取「块级正文」：跳过代码块 / 表格里的代码 / 已经插入的译文
  var SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, td, dd';

  function isCode(el) {
    return !!(el.closest && el.closest('pre, code, .highlight, .bb-tr, .markdown-heading .anchor'));
  }

  function collect(el) {
    if (!el || isCode(el) || (state.seen && state.seen.has(el))) return null;
    var text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    if (text.length < 2 || text.length > MAX_LEN) return null;
    // 按目标语言挑「需要翻的段落」：
    //   目标中文 → 取英文段落（含 3 个以上连续拉丁字母、且多以非中文构成）
    //   目标英文 → 取中文段落（含至少 4 个汉字）
    // 免得把已经是目标语言的段落再翻一遍（既费额度，译文也会更差）。
    var toZh = state.to.indexOf('zh') === 0;
    if (toZh) {
      if (!/[A-Za-z]{3}/.test(text)) return null;
      if (text.replace(/[^\u4e00-\u9fff]/g, '').length > text.length * 0.5) return null;
    } else {
      var han = text.match(/[\u4e00-\u9fff]/g);
      if (!han || han.length < 4) return null;
    }
    if (state.seen) state.seen.add(el);
    return { el: el, text: text };
  }

  /* ───────────── 队列与批量请求 ───────────── */

  function enqueue(items) {
    for (var i = 0; i < items.length; i++) state.queue.push(items[i]);
    pump();
  }

  function pump() {
    if (state.inflight || !state.on || !state.queue.length) return;
    var batch = state.queue.splice(0, BATCH);
    state.inflight = true;
    requestTranslate(batch.map(function (x) { return x.text; }), function (results) {
      state.inflight = false;
      for (var i = 0; i < batch.length; i++) {
        var tr = results[i];
        if (tr) { insert(batch[i].el, tr); state.count++; }
      }
      updateButton();
      setTimeout(pump, 120);   // 批次之间留一点间隔，配合原生侧的串行闸门
    });
  }

  function insert(el, translated) {
    var next = el.nextElementSibling;
    if (next && next.classList && next.classList.contains('bb-tr')) {
      next.textContent = translated;
      return;
    }
    var div = document.createElement('div');
    div.className = 'bb-tr';
    div.textContent = translated;
    // 标记源元素：仅译文模式下由 CSS 隐藏它（切回对照只需去掉 body 上的类）
    el.classList.add('bb-tr-src');
    // 列表项里的译文要缩进到同一层级，避免看起来像另一个列表项
    if (el.tagName === 'LI') div.style.marginLeft = '1.2em';
    if (el.parentNode) el.parentNode.insertBefore(div, el.nextSibling);
  }

  /* ───────────── 视口优先 ───────────── */

  var observer = null;
  function observeAll() {
    var nodes = document.querySelectorAll(SELECTOR);
    if (!observer) {
      observer = new IntersectionObserver(function (entries) {
        var hits = [];
        for (var i = 0; i < entries.length; i++) {
          if (!entries[i].isIntersecting) continue;
          observer.unobserve(entries[i].target);
          var item = collect(entries[i].target);
          if (item) hits.push(item);
        }
        if (hits.length) enqueue(hits);
      }, { rootMargin: '200px 0px' });
    }
    for (var i = 0; i < nodes.length; i++) {
      if (isCode(nodes[i])) continue;
      if (state.seen && state.seen.has(nodes[i])) continue;
      observer.observe(nodes[i]);
    }
  }

  /* ───────────── 浮动按钮与开关 ───────────── */

  function ensureButton() {
    if (state.button) return state.button;
    var btn = document.createElement('div');
    btn.id = 'bb-tr-btn';
    btn.setAttribute('role', 'button');
    btn.addEventListener('click', function () { state.on ? off() : on(); });
    document.body.appendChild(btn);
    state.button = btn;
    updateButton();
    return btn;
  }

  function updateButton() {
    if (!state.button) return;
    var label = !state.on ? '译' : (state.count ? '译 ' + state.count : '译');
    state.button.textContent = label;
    state.button.title = state.on ? '隐藏译文（保留原文）' : '沉浸式翻译：原文 + 中文对照';
    state.button.style.background = state.on ? '#0969da' : '#ffffff';
    state.button.style.color = state.on ? '#ffffff' : '#0969da';
  }

  function on() {
    state.on = true;
    document.body.classList.add('bb-tr-on');
    if (!state.dual) document.body.classList.add('bb-tr-only');
    try { localStorage.setItem(ON_KEY, '1'); } catch (e) {}
    updateButton();
    observeAll();
  }

  function off() {
    state.on = false;
    document.body.classList.remove('bb-tr-on');
    document.body.classList.remove('bb-tr-only');
    try { localStorage.setItem(ON_KEY, '0'); } catch (e) {}
    updateButton();
  }

  /* ───────────── 启动 ───────────── */

  function boot() {
    ensureButton();
    var saved = null;
    try { saved = localStorage.getItem(ON_KEY); } catch (e) {}
    // 优先级：设置里的「自动翻译」 > 上次页内按钮的选择。
    // 设置是用户在设置页的明确意图，不该被上次的临时开关覆盖。
    if (state.auto || saved === '1') on();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
