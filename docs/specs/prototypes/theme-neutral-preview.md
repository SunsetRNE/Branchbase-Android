<!-- 入库副本：正文与 design/theme-neutral-preview/README.md 一致，未做删改，仅加本段头。 -->
> **来源**：`design/theme-neutral-preview/README.md`（原型本体 `index.html` 在 `/design/theme-neutral-preview/` 下，按仓库约定**不入库**；DSH 侧边栏「原型预览」面板可直接打开）
> **为什么只把文档抽进来**：原型 HTML 是一次性草图，跟具体实现绑死、很快过期；
> 而这份文档里的**现状测绘、取舍论证与落地清单**是跨时间有效的设计结论，代码注释会引用它。
> **维护**：原型再改时请把这份副本一并更新，别让两边分叉；文中提到的文件名相对原始目录。

# 中性色「去灰」对比原型 · 说明

> **快照说明（2026-09 补）**：本文是**设计当时的记录** —— 文中的「现状测绘」、「`文件:行号`」、
> 「依据：`xxx.kt`（N 行）」都不会随代码演进更新（只有少数几处加了「2026-09 追记」）。
> 判断当前行为请以代码与 `docs/specs/` 下的规格为准；落地清单里未落地的项只表示「当时计划过」。

> 目录：`design/theme-neutral-preview/`（`/design/` 已在 `.gitignore` 中：原型草图不入库）
> 本文档的**入库副本**：`docs/specs/prototypes/theme-neutral-preview.md`（改完这里请同步那份）
> 目的：在**动 `ThemePalette.kt` 之前**，先看清「把灰色部分换成纯黑/纯白」在明暗两套下分别是什么观感。

## 一、怎么打开

1. **DSH Web 侧边栏「原型预览」面板** → 选 `design/theme-neutral-preview/index.html`（面板按「设备预设 → 手机 390×844」最贴近真机）；
2. 或本地静态服务：`python3 -m http.server 8099 --directory design/theme-neutral-preview`，浏览器开 `http://127.0.0.1:8099/index.html`。

工具条「并排 / 只看浅色 / 只看深色」只影响版面，不影响任何取值。

## 二、页面在比什么

三列内容**完全相同**（顶栏 + 搜索框 + 带描边的列表卡片 + 带描边的输入框 + chip + 三级文字层次），
只有四组 CSS 变量不同。它们逐一对应 `app/src/main/java/com/branchbase/ui/theme/ThemePalette.kt`：

| 原型变量 | 色板角色 | 浅色（现状） | 深色（现状） |
|---|---|---|---|
| `--icon` | `iconPrimary` | `#525560` | `#B1BAC4` |
| `--icon2` | `iconSecondary` | `#9194A1` | `#8B949E` |
| `--border` | `border` | `#BFC1C9` | `#30363D` |
| `--subtle` | `neutralFill` | `#F7F7F9` | `#161B22` |

> **2026-09 追记**：四行里只有 `iconPrimary` 变了 —— 已按方案 A 落地为 `#000000`（浅）/ `#FFFFFF`（深）
> （`ui/theme/ThemePalette.kt`；改动记于 VERSION-NOTES 的 1.0.31 条目）。其余三行与设计当时一致。

**`--accent`（蓝）/ `--success`（绿）等品牌与状态色在三列之间完全一致** —— 这是刻意的，
用来确认「蓝绿不换」的基准没被顺手带偏。

## 三、三个方案的确切取值

### 现状（对照基准）

```
icon   #525560 → #B1BAC4
border #BFC1C9 → #30363D
radius 16dp · 灰底 #F7F7F9 / #161B22
```

### 方案 A · 克制版（推荐）

```
icon   #525560 → #000000      #B1BAC4 → #FFFFFF
border #BFC1C9 → #8C959F      #30363D → #6E7681
radius 16dp · 灰底不变 · iconSecondary 不变 · 文字三级不变
```

描边落在「非文本对比度 3:1」一线（浅色实测 3.04、深色 4.12）：线立住了，但没有变成线框。
图标提到纯黑/纯白对图形不构成阅读负担，只是观感更利落。

### 方案 B · 风格化版

```
icon   #000000 → #FFFFFF（纯黑/纯白）
border #000000 → #FFFFFF（纯黑/纯白）
radius 16dp → 9dp · 灰底 #F7F7F9 → #F0F0F2、#161B22 → #1A1A1A（更实、去蓝调）
```

**这是整体视觉改版，不是换几个色值。** 高对比硬边必须成套：描边拉纯黑/纯白的同时，
灰底填充阶、圆角、阴影都得跟着收紧，否则硬线和软面会互相打架（比现状更斑驳）。
真要走 B，工作量约为 A 的 4–5 倍。

## 四、页面上的读数怎么理解

每个面板底部有三行实测 WCAG 对比度（底色为 `#FFFFFF` / `#0D1117`）：

- **描边 / 画布** —— 描边是装饰性分隔，**不是越高越好**。判词按档位给：`< 2.5` 克制 / `< 8` 偏响 / `≥ 8` 线框化。
  参照：GitHub 官方 Primer 浅色 `border.default #D0D7DE` 只有 1.45 —— 项目现在的 `#BFC1C9`(1.80) 已经比官方更重。
- **图标 / 画布** —— 门槛取非文本对比度 3:1。
- **说明文字 / 画布** —— 门槛取 WCAG AA 4.5:1。三列同值，用来确认「文字层次没被顺手改掉」。

## 五、结论与后续

- **图标**：改成黑/白值得做，波及 `Primer.IconPrimary`/`IconSecondary` 79 处（2026-09 同法复算约 **85** 处），风险低；
- **正文**：`textPrimary` 已经是 `#050505` / `#E6EDF3`，本来就不需要动。真正偏灰的是
  `textSecondary`(#41434E) 与 `textTertiary`(#6A6D7C)，它们是**层次本身**，全塌成纯黑会让长列表发糊；
- **描边**：一刀切纯黑/纯白（方案 B）会让浅色退回 wireframe、深色出现一屏「发光矩形」，
  建议走方案 A 的 3:1 档；`Primer.Border` 波及 70 处（2026-09 同法复算约 **69** 处）。

定了口径后改 `ThemePalette.kt` 的 `LightPrimerPalette` / `DarkPrimerPalette` 即可，
所有 `Primer.XXX` 调用点一行都不用动（这是该色板架构的设计前提）。
改完必须跑 `app/src/test/java/com/branchbase/ui/theme/ThemeContrastTest.kt`。
