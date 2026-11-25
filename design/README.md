# 原型草图（`/design/`）

> **2026-11-24 起入库**（此前按 `docs/README.md` 的政策是「永久不入库」）。
> 目录内容**逐步补全** —— 历史原型还没有迁进来，新建的原型请直接建在这里。

## 放什么

- 每个原型一个目录：`design/<原型名>/`（HTML / CSS / JS，可直接在 DSH 侧边栏的「原型预览」打开）。
- 同一原型的**说明与落实方案**（现状测绘 / 取舍论证 / 落地清单）仍然抽一份进
  [`docs/specs/prototypes/`](../docs/specs/prototypes/) —— 分工不变：**草图看观感、文档下结论**。

## 规则

1. **原型不是现状**：落地之后代码才是真源，原型只作历史参考，不要拿它当验收依据；
2. **别在两处各写一份正文**：说明写在 `docs/specs/prototypes/<原型名>.md`，草图目录里不再重复；
3. 新建原型后在下面这张表补一行，方便查找。

## 现有原型

| 原型 | 说明文档 | 状态 |
|---|---|---|
| [`home-todo-empty/index.html`](home-todo-empty/index.html) | [`docs/specs/prototypes/home-todo-empty.md`](../docs/specs/prototypes/home-todo-empty.md) | 首页「待处理」：无框分组 + 可折叠（含空态/三态）：**已落地（1.4.31）** |
| （待补） | | 历史原型尚未迁入 |
