#!/usr/bin/env node
/**
 * 原型的脚本级验收（不需要浏览器）：用最小 DOM 桩跑 index.html 里的真实脚本，
 * 模拟点工具条，断言四种状态 / 两个版本 / 折叠都能真正切过去。
 *
 * 为什么要有它：工具栏的 id 与脚本里的 getElementById 一旦对不上，控件会**静默无反应**
 * （浏览器不报错）—— 2026-11-25 就踩过一次（`seg-state` 绑成了 `seg-data`，
 * 「加载中 / 失败」点不动）。这个脚本把这类错变成退出码非 0。
 *
 *   node design/home-todo-empty/smoke.js
 */
"use strict";

const fs = require("fs");
const path = require("path");

const file = path.join(__dirname, "index.html");
const src = fs.readFileSync(file, "utf8");
const m = src.match(/<script>([\s\S]*?)<\/script>/);
if (!m) {
  console.error("找不到 <script> 块");
  process.exit(1);
}
const script = m[1];

let lastNameWritten = [];

class El {
  constructor(tag = "div") {
    this.tagName = tag;
    this.style = {};
    this.dataset = {};
    this.checked = false;
    this.disabled = false;
    this._classes = new Set();
    this.classList = {
      toggle: (c, on) => (on ? this._classes.add(c) : this._classes.delete(c)),
      add: (c) => this._classes.add(c),
      remove: (c) => this._classes.delete(c),
    };
    this._html = "";
    this._handlers = {};
  }
  set innerHTML(v) {
    this._html = String(v);
    // 模拟解析：内联 HTML 里出现的 id 也要能被 getElementById 找到（如 todo-slot）
    for (const hit of String(v).matchAll(/id="([^"]+)"/g)) byId.set(hit[1], new El());
  }
  get innerHTML() {
    return this._html;
  }
  set outerHTML(v) {
    lastNameWritten.push(String(v));
  }
  get outerHTML() {
    return "";
  }
  appendChild(c) {
    return c;
  }
  remove() {}
  querySelectorAll() {
    if (!this._buttons) this._buttons = [];
    return this._buttons;
  }
  addEventListener(type, fn) {
    (this._handlers[type] = this._handlers[type] || []).push(fn);
  }
  setAttribute() {}
  getBoundingClientRect() {
    return { height: 44, width: 390 };
  }
  get firstElementChild() {
    return null;
  }
  click(target) {
    (this._handlers.click || []).forEach((fn) => fn({ target }));
  }
}

const byId = new Map();
// 静态 HTML 里的 id 先建好；**未知 id 返回 null**（和真 DOM 一致，避免假成功）
for (const hit of src.matchAll(/id="([^"]+)"/g)) byId.set(hit[1], new El());

const doc = {
  documentElement: new El("html"),
  body: new El("body"),
  createElement: (t) => new El(t),
  getElementById: (id) => byId.get(id) || null,
};

const warnings = [];
const realWarn = console.warn;
console.warn = (...a) => warnings.push(a.join(" "));

const win = {};
new Function("document", "window", script)(doc, win);

function click(id, value) {
  const box = byId.get(id);
  if (!box) throw new Error("找不到控件：" + id);
  box.click({ closest: () => ({ dataset: { v: value }, disabled: false }) });
  return lastNameWritten[lastNameWritten.length - 1] || "";
}

let failures = 0;
function expectNot(label, html, needles) {
  const hit = needles.filter((n) => html.includes(n));
  if (hit.length) {
    failures++;
    console.log("FAIL  " + label + "（不该出现 " + hit.join(" / ") + "）");
  } else {
    console.log("ok    " + label);
  }
}
function expect(label, html, needles) {
  const miss = needles.filter((n) => !html.includes(n));
  if (miss.length) {
    failures++;
    console.log("FAIL  " + label + "（缺 " + miss.join(" / ") + "）");
    console.log("      " + html.slice(0, 200));
  } else {
    console.log("ok    " + label);
  }
}

// 四种状态必须先切得过去（本轮修的就是这个）
expect("状态 · 有内容", click("seg-state", "content"), ["未读通知", "1 条"]);
expect("状态 · 空", click("seg-state", "empty"), ["都处理完了"]);
expect("状态 · 加载中", click("seg-state", "loading"), ["读取中", "sk"]);
expect("状态 · 失败", click("seg-state", "fail"), ["暂时取不到待办", "重试"]);

// 版本与折叠
expect("版本 · 现状", click("seg-version", "current"), ["没有待办"]);
expect("版本 · 新设计", click("seg-version", "new"), ["fold"]);
expect("折叠", click("seg-collapse", "collapsed"), ["is-collapsed", "fold-body"]);
expect("展开", click("seg-collapse", "expanded"), ["fold-body"]);
expectNot("展开态不带折叠类", lastNameWritten[lastNameWritten.length - 1] || "", ["is-collapsed"]);

// 折叠体常驻 DOM 是动画的前提（重建节点会让过渡失效）
const collapsedHtml = click("seg-collapse", "collapsed");
if (!collapsedHtml.includes("fold-body")) {
  failures++;
  console.log("FAIL  折叠时展开体被从 DOM 里删掉了（动画会失效）");
} else {
  console.log("ok    折叠时展开体仍在 DOM（高度 0）");
}
if (!win.__protoState || win.__protoState.collapsed !== true) {
  failures++;
  console.log("FAIL  状态未同步到 window.__protoState");
} else {
  console.log("ok    状态同步（window.__protoState）");
}

// 回弹动画：展开曲线必须有过冲（cubic-bezier 的 y > 1），否则就是「生硬」那版
const bounce = "cubic-bezier(.2, 1.45, .34, 1)";
if (src.split(bounce).length - 1 < 2) {
  failures++;
  console.log("FAIL  展开动画缺少过冲曲线（箭头与展开体都该用它）");
} else {
  console.log("ok    展开用过冲曲线（箭头 + 展开体）");
}
if (!/body\.animate\(/.test(script)) {
  failures++;
  console.log("FAIL  展开/收起没有走 Web Animations");
} else {
  console.log("ok    展开/收起走 Web Animations");
}

// 行型：单行 44 / 两行 48（先切到有内容，失败态没有行）
click("seg-state", "content");
const oneLine = click("seg-row", "one");
expect("行型 · 单行", oneLine, ["row one", 'class="count"']);
const twoLine = click("seg-row", "two");
expectNot("行型 · 两行", twoLine, ["row one"]);

// 点折叠头：必须走动画（不是重建 DOM），且展开用带过冲的曲线
function fakeFold(collapsed) {
  const classes = new Set(collapsed ? ["is-collapsed"] : []);
  const body = {
    animateCalls: [],
    animate(frames, opts) {
      this.animateCalls.push({ frames, opts });
      return { cancel() {} };
    },
    getAnimations() { return []; },
    querySelector() { return { offsetHeight: 160 }; },
    scrollHeight: 160,
  };
  const fold = {
    classList: {
      contains: (c) => classes.has(c),
      toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)),
      add: (c) => classes.add(c),
    },
    querySelector: (sel) => (sel === ".fold-body" ? body : null),
  };
  return { fold, body, classes };
}

const screen = byId.get("screen");
const expandCase = fakeFold(true);
screen.click({ closest: (sel) => (sel === ".fold-head" ? { parentElement: expandCase.fold } : null) });
if (expandCase.classes.has("is-collapsed") || win.__protoState.collapsed !== false) {
  failures++;
  console.log("FAIL  点折叠头没展开");
} else if (!expandCase.body.animateCalls[0] || !String(expandCase.body.animateCalls[0].opts.easing).includes("1.45")) {
  failures++;
  console.log("FAIL  展开没有用过冲曲线");
} else {
  console.log("ok    点折叠头：展开 + 过冲曲线");
}

const collapseCase = fakeFold(false);
screen.click({ closest: (sel) => (sel === ".fold-head" ? { parentElement: collapseCase.fold } : null) });
if (!collapseCase.classes.has("is-collapsed") || win.__protoState.collapsed !== true) {
  failures++;
  console.log("FAIL  点折叠头没收起");
} else if (!collapseCase.body.animateCalls.length) {
  failures++;
  console.log("FAIL  收起没有动画");
} else {
  console.log("ok    点折叠头：收起 + 动画");
}

// 折叠头 44dp 是拍板值，别在改样式时被顺手改回去
if (!/\.fold-head\s*\{[^}]*min-height:\s*44px/.test(src)) {
  failures++;
  console.log("FAIL  折叠头不是 44px");
} else {
  console.log("ok    折叠头 44px");
}

// 控件找不到时必须出声（不允许静默 return）
expect("空态摘要与说明栏一致", click("seg-state", "empty"), ["都处理完了"]);

if (warnings.length) {
  failures++;
  console.log("FAIL  脚本告警：" + warnings.join(" | "));
}

console.warn = realWarn;
console.log(failures === 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
process.exit(failures === 0 ? 0 : 1);
