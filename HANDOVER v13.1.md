# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v13.1

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v13.1 = 修复 v13.0 装机后报告的 10 项 Bug + 6 项优化 + 主动隐患防线。
> **全量测试：keyboard 56 + note 10 + 复合压力 8 = 74 项 0 失败；Java 括号配平 OK；build.yml YAML 合法；全部 B64 解码校验一致。**

## 文件地图（同 v13.0，勿忘）
- `WubiIME/app/src/main/assets/keyboard.html` — 键盘全部 UI+逻辑（真身在仓库，不进 B64）
- `WubiIME/app/src/main/assets/apps/note.html` / `pwgen.html` — 独立应用
- `.github/workflows/build.yml` — CI；Java/Manifest/工程骨架真身以 B64 内联其中：
  `WUBIWEBVIEWIME_JAVA_B64`、`APPACTIVITY_JAVA_B64`、`ANDROIDMANIFEST_B64`、
  `SRC_B64`(工程骨架 tar.gz：build.gradle、proguard-rules.pro、ClipboardManager.java、DictEngine.java 等)
- `dict-extra/` — 词库：`pinyin_extra.txt`(口语短语,自动简拼)、`modern_words.txt`(五笔)、`prepare_dicts.py`
- **改 Java/Manifest/骨架 → 必须重编码回填对应 B64 并解码校验**（本轮已同步：WubiWebViewIME、AppActivity、SRC_B64）

## v13.1 Bug 修复（对应报告 1–10）

1. **前置只生效一次 + 上屏后候选栏残留 + 显示联动崩溃**（三根因）
   - `userAddTop` 由 push 改 **unshift**：最新前置的词立即置顶（原先排到置顶组末尾，前置第2位的词位置不变="看着没生效"）。单码置顶组上限 12。
   - `commitCand` **先清 t9code 再 commit**：commit 内部触发 showCands([])→showBtns，t9code 残值会把候选栏卡屏（九宫用过后回主键盘上屏即复现）。
   - `showBtns`/`syncCodeBar`/`activeCodeKey` 统一收紧：**t9code 仅九宫布局(pyGrid)激活时才算数**。
   - `updateCode` 渲染链加自愈 try/catch：任何查询/渲染异常→强制复位干净态，下一键从零恢复，显示崩溃不再级联。
2. **删除确认弹窗全覆盖**：Shift+点击删词、剪贴列表删除、单条详情删除均走 `kbConfirm('确认删除此内容？')`，确认即删即刷。
3. **触控条滑丢**：JS 累积夹紧±24+单帧最多2步；**Java `moveCursor(0/1)` 发键前边界内查**——已到文首/末绝不再发 DPAD（文末多发会把焦点推到宿主下个控件="冲出消失"元凶）。
4. **删除键无声**：主键盘/数字键盘删除键补 playClick（含长按连删每拍）。九宫删除走 mk 通道本有声。
5. **表情分类间距**：`.cat-chip.emo` padding 6→11px、min-width 30→44px，对齐符号分类。
6. **记事本屏宽**：三重保险——html/body overflow-x:hidden+max-width:100vw；块文本 word-break:break-all+overflow-wrap:anywhere（长数字串/URL必断）；**html.native 类**由 JS 检测 AndroidPW 注入强制全屏铺满（与媒体查询双通道，媒体条件放宽至 max-height:900px）。
7. **记事本 Alt+方向卡死**（Java 根治）：宿主=自家包名(`hostIsSelf`)时选中操作走 **Shift+DPAD 同步 sendKey**——Chromium contenteditable 原生按视觉行精准扩选，零排队；原路径 getExtractedText 在 WebView 是慢速跨进程调用，长按连发 50ms/拍灌爆主线程=卡死根因。另加 `mcBusy` 忙则丢帧：moveCursor 队列永不堆积（普通输入框同样受益）。
8. **导出落地**：AppActivity 新增 JS 桥 `saveFile(name,base64,mime)`——API29+ 走 MediaStore.Downloads（免权限）写系统「下载」目录；note.html `saveBlob` 优先走桥（WebView 里 `<a download>` 对 blob: 静默无效、share 不可用="显示已打包却没文件"根因），成功 toast「已保存到 下载/文件名」。
9. **"拾"打不出 + 单字调序**：真根因在 Java——`queryPinyin` 固定截断 15 条。放开：拼音/五笔 600、T9 600、简拼 60；展开池取 600（分片渲染）。单字放开 Ctrl 菜单（可前置调序），菜单对单字隐藏删除按钮；Shift 删除仍拦单字。
10. **展开池覆盖不到空格键下缘**：`.pager-overlay` 改 **position:fixed 铺满输入法窗口视口**；顶边用 `getBoundingClientRect` 取候选/按钮栏真实屏幕坐标（与文档流 offsetTop 解耦）。

## v13.1 优化

1. 九宫末行(计算器/空格/回车)=44px 与主键盘空格同高；前3行 50→52 均分回收（`.t9row .key` / `.t9last .key`）。
2. 九宫功能层副字加大加粗（`.t9fnsub .ks` 14px/600）与"全选"同级观感；音效键改主键盘同款喇叭/静音 SVG。
3. Alt 层 Tab 跳段唯一保留 1 键位（Num 键面灰置去 Tab 字样）；Tab 下一项 5→3 键位。
4. 质感：单条展开门底色改键盘同款渐变+顶缘高光（弃深纯色）；编码栏改亮玻璃渐变+高光边；候选栏加顶缘高光细线；暗主题各有适配。车厢联轴器恢复连接（无车轮无铁轨）。
5. **词库**：topN 12万→**20万**（雾凇词组近全量：智能/束缚/童话/天使级词全进）；`pinyin_extra.txt` 扩至 ~250 条短语（+500 词典条目含简拼）——含用户点名全部：山高水远/今晚有没有空/我跟你说/并不束缚你/很智能/超智能/我不记得了/童话里/我愿变成童话里/你爱的那个天使/你在哪呢。
6. **安防**（SRC_B64 内三件套）：
   - `build.gradle` release 开 **R8 minifyEnabled+shrinkResources+proguard-android-optimize**；
   - `proguard-rules.pro` 由"全量 keep(裸奔)"改**最小保留面**：仅 keep @JavascriptInterface 方法 + Manifest 组件入口，引擎/剪贴板/主题全部混淆压缩；
   - `ClipboardManager.java` 条目 **AES-GCM 加密**（Android Keystore 别名 `ankey_clip_key`，密钥不出安全硬件；旧明文读入自动一次性迁移为密文）。adb/root 拉走 prefs 只见密文。
   - 边界坦白：HTML 资产加壳对 root/apktool 无实际意义（未做假安全）；实际防线=R8 混淆 + 笔记 .enc AES-GCM + 剪贴板 Keystore 加密 + FLAG_SECURE + 剪贴隔离。

## v13.1 主动隐患防线
- `commitCand` 防重入：150ms 内同词连点只上屏一次（触摸抖动/幽灵双击双上屏）。
- `safeSetItem` 存储满额降级：QuotaExceeded 时裁半保最新重试（saveClips 已接入）。
- addClip 单条 100KB 截断：超长文本不撑爆 localStorage。
- 展开池 600 条**分片渲染**（首批120秒开，每帧续120，token 作废机制防重开串台）。
- 长按连发三重保险、moveCursor 忙则丢帧（见 Bug1/3/7）。

## 架构不变量（勿破坏）
- str_replace 局部改；每改必 node --check → Node 全量测试 0 失败再输出。
- Java 括号配平检查；改 Java/骨架必回填 B64 并解码校验。
- `signingConfigs.fixed` 绝不触碰（本轮 gradle 改动已保）。
- 用户词库键统一 `activeCodeKey()`（code / 九宫 t9code）。
- R8 已开启：新增 JS 桥方法必须带 @JavascriptInterface 注解（proguard 靠它保方法名）；新增 Manifest 组件需在 proguard-rules.pro 补 keep。

## 本轮上传清单
**必须上传（已改动）：**
- `.github/workflows/build.yml`（WubiWebViewIME/AppActivity B64 + SRC_B64 安防三件套 + topN 20万）
- `WubiIME/app/src/main/assets/keyboard.html`
- `WubiIME/app/src/main/assets/apps/note.html`
- `dict-extra/pinyin_extra.txt`

**不必上传（本轮未改）：** `pwgen.html`、`README.md`、`dict-extra/prepare_dicts.py`、`dict-extra/modern_words.txt`、旧 HANDOVER（可删）。
