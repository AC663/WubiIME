# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v12.1

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v12 完成 14 项整改；v12.1 在其上彻底修复全部已知隐患 + 防截屏光环 + 胶囊重排 + 词库体系入仓。

## 文件地图
- `WubiIME/app/src/main/assets/keyboard.html` — 键盘全部 UI+逻辑（唯一大文件，~3100 行）
- `WubiIME/app/src/main/assets/apps/note.html` / `pwgen.html` — 内嵌应用
- `.github/workflows/build.yml` — CI：解码 java → 下载词库/emoji 字体 → gradle 打 APK
- `dict-extra/modern_words.txt` — 现代词清单（随仓库维护，想加词直接编辑它）
- `dict-extra/prepare_dicts.py` — 词库准备脚本（五笔自动成码补词 + 拼音去重）
- `scraps/native/WubiWebViewIME.java` — 原生服务源码【真身】：CI 会把它(经 build.yml 内 WUBIWEBVIEWIME_JAVA_B64)覆盖进包，改原生只改这里+重新生成 b64

## v12.1 隐患修复（本轮，全部完成）
- **剪贴隔离开关**：新增胶囊「剪贴隔离」(默认开)。开=复制只进私有剪贴板并清系统剪贴板(隐私优先)；关=系统剪贴板保留，跨App直接粘贴不受影响。native: `setClipIsolation/isClipIsolation`，captureAndClearSystemClip 只在隔离开时清板。
- **Android 10+ 漏抓剪贴**：onStartInputView 延迟 300ms 主动补抓一次(此时本 IME 聚焦可读)，键盘隐藏期间复制的内容弹出即补录。
- **localStorage 易失**：新增持久层——PERSIST_KEYS 12 项(造词/排序/面板高/剪贴历史/开关态…)全量镜像到 `AndroidPW.saveAppData('kbprefs')`(加密+断电安全)，开机自动回灌；所有存档点(9处)后接 `mirrorPrefs()`(400ms 合并)。系统清 WebView 数据不再丢。
- **长拼音卡顿**：updateCode 对 ≥7 码拼音串加 50ms 合并防抖，连击只查最后一次。
- **z 反查缺码**：反查结果的五笔码内嵌表查不到时，改由 `deriveWubiCode` 据单字全码按86规则现推，不再留空。
- **开机状态同步**：防截屏以原生 pref 为准(`Android.isSecure`)，开机镜像进 localStorage 并点亮/熄灭光环，JS 与 native 永不分叉。

## v12.1 新特性
- **防截屏光环**：激活时触控条(`#tp`)加 `.sec`——白金夹环+主题色描边+外圈柔光 2.6s 呼吸(`@keyframes secRing`，色随 `--ac/--acl` 自动跟主题)；关闭即恢复默认阴影。
- **胶囊重排**：`CLIP_APPS=[防截屏(toggle,居首) | 密码生成器 | 笔记本 | 剪贴隔离(toggle)]`。剪贴板 tab 已撤（展开区本身就是剪贴板）；新应用往 app 类型后接即可。
- **数字键盘双用键重设**：[- +]/[. ×] 保留一键两区(省格子、同计算器惯例)，但去掉右半深色块硬切，改中缝 30% 高发丝分隔线+各自独立按压高亮。
- **词库体系入仓**（build.yml + dict-extra/）：
  · 主源全部加 jsdelivr 镜像兖底，单源挂不影响构建；
  · 拼音：pinyin_simp 单字 + 雾凇 base + 雾凇 ext(新词)，合并后按(词,音)去重，topN 6万(含双字/三字/四字成语)；
  · 五笔：rime-wubi 官方 + modern_words.txt 现代词(自动成86码,freq 50万置顶) + THUOCL 清华词库(成语/地名/财经/食物/医学各取高频前8000,freq 12万)；已存词跳过、生僻字跳过，不会产错码不会冲突。

## v12 改动清单（对应巧巧 14 项，已全部完成）
1. 五笔四码多候选不再抢上屏：唯一候选才自动上屏；驻留中键入第5键=顶屏。
2. 造词：词字段 EN 直输(字母/数字/大小写)；自定义码支持字母+数字 6 位。
3. 分类切换动画(方向滑入+标签弹性+自动滚入)。
4. 符号校对：序号 8 组 130+；网址 4 列等宽胶囊 48 项；跨类去重。
5. emoji COLRv1 字体(真机粗糙=WebView<98，升级 Android System WebView)。
6. 面板高 -1/5(panelH3/emjH2)；假名 10 列五十音图；旗帜 93 项三区。
7. 切数字键盘不再关面板。
8. 拖杆釉面胶囊；剪贴列表/详情玻璃卡片。
9+12. 剪贴球→[应用]按钮；编码栏胶囊轨道；防截屏提示改顶部悬浮气泡(z-index 400)。
10. 笔记本保真粘贴：按行拆块+pre-wrap，`#`/`---` 自动映射；导出同步。
11. 应用全屏链路(overlay+`Android.setAppFullscreen`)已完整，数据仍在输入法内。
13. 词库升级见上。❗词库/字体都在 CI 构建时生成→**需重新跑 Actions 才进 APK**。

## 仍需真机验证（浏览器无法测）
四码驻留/顶屏、造词 EN、应用全屏进出、防截屏开关+光环、剪贴隔离开/关两态跨App粘贴、清 WebView 数据后设置回灵、emoji 渲染。

## 约定
- 面板高度存储键 `panelH3`/`emjH2`（改默认值时升级键名）。
- 符号分类：`{n,name,list,fixed?,cols?}`；fixed=禁排序固定排版。
- `CLIP_APPS` 条目：`{id,label,type:'app'|'toggle'}`；app→`openApp(id)`→`apps/{id}.html`。
- 新增持久键必须同步进 `PERSIST_KEYS` 并在存档点补 `mirrorPrefs()`。
- 改 `scraps/native/WubiWebViewIME.java` 后，需重新 base64 全文并替换 build.yml 的 `WUBIWEBVIEWIME_JAVA_B64`（本轮已同步）。
