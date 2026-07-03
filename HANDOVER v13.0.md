# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v13.0

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v13.0 = 记事本/输入法深度兼容修复 + 九宫功能层 100% 复刻主键盘 + 造词/前置/剪贴即时化 + 拼音口语词库 + 一批 UI 优化。
> **本轮所有改动均经 Node 全量测试通过：keyboard 39 项 + note 10 项 + 复合压力 8 项，共 57 项 0 失败。**

## 文件地图
- `WubiIME/app/src/main/assets/keyboard.html` — 键盘全部 UI+逻辑（唯一大文件）
- `WubiIME/app/src/main/assets/apps/note.html` / `pwgen.html` — 独立应用（AppActivity 独立窗口 或 键盘内 overlay 双跑）
- `.github/workflows/build.yml` — CI：checkout 仓库 → 解 SRC_B64/各 Java B64 → 下词库/字体 → gradle 打 APK
- `dict-extra/` — 词库脚本与词表（想加词直接编辑，提交即下次构建生效）
  - `modern_words.txt` 五笔现代词（一行多词，自动按 86 规则成码）
  - `pinyin_extra.txt` 拼音口语短语（`词<TAB>带空格拼音<TAB>频率`，自动含简拼、去重）
  - `prepare_dicts.py` 词库构建脚本（wubi_supplement / pinyin_extra / pinyin_dedup 三命令）

### 关键：Java 真身与 B64 的关系
Java/Manifest **没有独立仓库文件**，真身以 base64 内联在 `build.yml` 的 `*_B64` 变量里，CI 解码写盘覆盖：
- `WUBIWEBVIEWIME_JAVA_B64` → 原生 IME 服务（含 `cursorAtBoundary` 触控条边界判定）
- `APPACTIVITY_JAVA_B64` → 独立应用全屏窗口（含去 ActionBar）
- `ANDROIDMANIFEST_B64` → Manifest（含 AppActivity 的 NoActionBar 主题）
- `DICTENGINE_JAVA_B64` / `SETTINGSACTIVITY_B64` / `THEMEMANAGER_B64` 等未在本轮改动
- **改任何 Java/Manifest 后：全文 base64 → 替换 build.yml 对应 `*_B64`（本轮 3 个已同步）。**
- keyboard.html / note.html / pwgen.html **不在任何 B64 里**，直接从仓库 checkout 取用，改仓库文件即生效。

---

## v13.0 真机 Bug 修复（对应用户报告 1–7）

1. **造词(Ctrl+F)光标不显示、不能在反显码上改** → 词/码活动字段末尾渲染主题色闪烁光标（`.wm-caret` CSS 动画）；点「码」字段时把当前自动反显码接管为可编辑底稿（`wmSetField('code')` 里 `_wmCustomCode=wmDerivedCode()`），退格/续键直接改，不再从零输。

2. **Ctrl+候选「前置」显示生效实际没生效；删除加确认弹窗** → 前置改**双键落库** `userAddTopBoth`（既存当前键入前缀码，也存候选自身完整码 `c.c`），少打/多打一两码都能置顶；删除改 `kbConfirm` 弹窗（「确认删除该词？」，点确认才删）。

3. **复制/剪切内容不即时进剪贴板条区** → 新增 `syncPrivClips()` 把 Java 私有剪贴板（系统剪贴监听路径捕获的那条）合并进 JS `clips`（面板数据源）。触发点：Ctrl+C/X 后 `scheduleClipSync()` 160ms+650ms 双拍拉取、打开剪贴面板前拉取、`onIMEFocus` 键盘获焦拉取。彻底消灭"何时出现抓不清规律"。

4. **九宫 Ctrl/Alt 组合键功效与主键盘不一致** → 九宫功能层重写为 **100% 复刻主键盘**（`fnMap` 全部走同一 `handleCtrl`/`handleAlt` 通道）：
   - Ctrl 方向 2↑文首 4←行首 6→行尾 8↓文末（= 主键盘 Ctrl+J/B/M/N），主字箭头+副字文案完全一致
   - Alt 方向 2↑选上行 4←左扩 6→右扩 8↓选下行（真扩选，可长按连续），新增 1=Tab下一段(长按连跳)、5=Tab下一项
   - 剪切/复制/粘贴键副字改**剪刀/复制/剪贴板 SVG 图标**；九宫删除键换主键盘/数字键盘同款 SVG
   - Alt+Tab 下一段支持长按连续（Num 键 `noRepeat` 仅 Ctrl 层生效）
   - Ctrl+候选前置/删除、Shift 删词在九宫同样生效（按数字串 `t9code` 落库，`t9Cands` 套用 `applyUserDict`）

5. **[记事本] 里 Ctrl/Alt+方向键连发导致崩溃/无响应；触控条光标操控不了** → 两处根治：
   - **长按连发失控（崩溃根因）**：键盘重建把按住的键摘出 DOM → touchend 丢失 → 连发 `setInterval` 永不停止直到 ANR。三重保险：interval 每拍查 `div.isConnected` 自停；全局连发登记表 `_kbRepeatCancels` + document 级 touchend/touchcancel/失焦兜底全清；`handleCtrl` 不再无差别 `buildKb()`（仅瞬时 Ctrl 才重建，功能层/锁定层调用时 ctrl=false 不重建）。
   - **触控条边界锁死（Java `cursorAtBoundary`）**：WebView 富文本里 `getTextBeforeCursor` 返回 null 被当成"已到边界"→触控条一进记事本就被锁死。改为先用 `ExtractedText` 选区绝对偏移判定，null 视为未知**绝不锁边界**。

6. **记事本拖动块自流体重排 + 去掉大标题栏** →
   - 块拖动新增边缘自动滚动 + 滚动坐标补偿（`scrollTop0`/`scDelta`），长文里可把分隔线精确拖到倒数第 2 行，被排挤内容自流体联动重排（原有逻辑保留）。
   - 黑色大标题栏〝拾砚〞其实是 AppActivity 默认 ActionBar → `requestWindowFeature(FEATURE_NO_TITLE)`+`getActionBar().hide()` 代码移除 + Manifest 套 `NoActionBar` 主题双保险。页内导航压薄，按钮统一〝⌨ 返回键盘〞。

7. **记事本[贴]/粘贴不上屏、且把输入法搅成无响应** → 粘贴改**状态直写**：弃 `focus()`+`execCommand`（真机弹层关闭/窗口切换窗口期静默失败且搅崩输入法），直接改块数据 → 重渲染 → 复位光标（按 `_caret.off` 字符偏移），与输入法零纠缠、百发百中。多行仍走 `insertBlocksVerbatim` 保真分块。

---

## v13.0 优化（对应用户优化清单）

1. **按钮栏所有 .sball 按钮点击=气泡音** `playPop()`（与分类胶囊同款）：捕获阶段监听 btnBar，真实触摸走 touchstart，幽灵合成事件不响。
2. **九宫删除键图标**统一为主键盘/数字键盘同款 SVG（不再是 `⌫` 字符）。
3. **候选池展开改在键盘区内覆盖**（主流输入法样式）：`.pager-overlay` 由 `position:fixed` 吊窗口底改 `position:absolute` 挂 `#ime`，`openPager` 动态设 `top=键盘区 offsetTop`（编码/候选栏保持可见）。根治"隔在键盘下方根本不显示"。
4. **触控条箭头默认灰色**（`#a2a2a2`/暗主题 `#8b8b8b`），仅**剪贴隔离激活**时联动主题色（`.trackpad.iso`，在 `applyIsoChip`/`toggleClipIsolation`/开机同步三处联动）。
5. **[应用]胶囊去车轮/铁轨/联轴器**（用户定案：太丑），`_dressCar` 空转、铁轨背景移除；**防截屏状态点移到文案左侧**（象征左车头）。
6. **剪贴板单条点击展开至覆盖编码栏之下整个键盘区**：`showClipDetail` 挂 `#ime`，`top=code-bar 底边`，正文区可上下滚动充分呈现。
7. **[符号][表情][应用]分类左右滑动动画装机根治**（已返修两轮）：弃 WAAPI/双rAF+transition（与大块 innerHTML 重排同帧竞争被合成器丢弃），改**纯 CSS animation 类** `.panel-swap-l/r`（移类→强制回流→加类，样式系统自驱，与 JS 帧时序完全解耦）。

---

## v13.0 词库

- **拼音口语短语** `dict-extra/pinyin_extra.txt`：今天太晚/你爱咋咋地吧/晚点去公司/爱咋咋地/咋样/靠谱/离谱… 约 100 条常用短语，自动生成全拼+简拼(~声母)条目，与主词库按 (码,词) 去重保高频。CI 在 `convert_rime.py pinyin` 后调 `prepare_dicts.py pinyin_extra` 追加。
- **五笔口语词** 追加进 `modern_words.txt`（太晚/晚点/咋样/靠谱/给力/走起…），CI 按 86 规则自动成码。
- **编码栏调序不清退**：`showBtns` 在有编码（`code` 或 `t9code`）时保持候选栏可见、不退按钮栏，Ctrl+单击候选调序才有落点。

---

## 架构不变量（勿破坏）
- 改 keyboard.html 用 str_replace 局部改，不整文件重写。
- 每次改动后：`node --check` 提取的 JS → Node 全量功能测试（DOM shim + Android bridge fake）→ 确认 0 失败再输出。
- Java 括号配平检查（无 javac 时）：去字符串/注释后 `{}()[]` 计数相等。
- `signingConfigs.fixed` 绝不触碰。
- MAGIC 字节唯一、nonce 不裸 hex 等妙隐规则与本项目无关，勿混淆。
- 造词/前置/删除的用户词库键：全键盘取 `code`、九宫取 `t9code`，统一经 `activeCodeKey()`。

## 本轮上传清单
**必须上传（已改动）：**
- `.github/workflows/build.yml`（含 3 个更新的 Java/Manifest B64 + pinyin_extra 构建步骤）
- `WubiIME/app/src/main/assets/keyboard.html`
- `WubiIME/app/src/main/assets/apps/note.html`
- `dict-extra/prepare_dicts.py`
- `dict-extra/pinyin_extra.txt`（新增）
- `dict-extra/modern_words.txt`

**不必上传（未改动）：**
- `WubiIME/app/src/main/assets/apps/pwgen.html`（本轮零改动）
- `README.md`（本轮零改动）
- 旧 `HANDOVER v12.2.md`（可删可留，本文件 v13.0 已完整覆盖）
