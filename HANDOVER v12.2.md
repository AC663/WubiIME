# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v12.2

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v12.2 = 真机 5 Bug 修复 + 13 项优化 + 独立应用改独立全屏窗口(AppActivity) + 词库扩容12万/整句切分修复。

## 文件地图
- `WubiIME/app/src/main/assets/keyboard.html` — 键盘全部 UI+逻辑（唯一大文件，~3350 行）
- `WubiIME/app/src/main/assets/apps/note.html` / `pwgen.html` — 独立应用（页面零改动即可双跑：AppActivity 独立窗口 或 键盘内 overlay 回退）
- `.github/workflows/build.yml` — CI：解码 java/manifest → 下载词库/emoji 字体 → gradle 打 APK
- `dict-extra/` — 词库脚本与现代词清单（想加词直接编辑 modern_words.txt）
- `scraps/native/WubiWebViewIME.java` — 原生服务【真身】→ CI 经 `WUBIWEBVIEWIME_JAVA_B64` 覆盖进包
- `scraps/native/DictEngine.java` — 词库引擎【真身】(v12.2 新入仓) → `DICTENGINE_JAVA_B64` 覆盖
- `scraps/native/AppActivity.java` — 独立应用全屏窗口【真身】(v12.2 新增) → `APPACTIVITY_JAVA_B64`
- `scraps/native/AndroidManifest.xml` — Manifest【真身】(v12.2 起 CI 覆盖) → `ANDROIDMANIFEST_B64`
- ❗改以上任何 scraps/native 文件后：全文 base64 → 替换 build.yml 对应 `*_B64`（本轮已同步）。

## v12.2 真机 Bug 修复
1. **独立应用弹出被阻止** → 弃"IME 窗口内撑全屏"，新增 `AppActivity`（普通全屏 Activity，`Android.openAppWindow(name)` 启动，失败自动回退键盘内 overlay=`openAppOverlay`）。AndroidPW 桥同名同格式（同 Keystore 别名 `ankey_appdata_key`、同 `{app}.enc`、同私有剪贴板 prefs），apps/*.html 零改动。笔记本里聚焦输入框=系统正常唤起本输入法。防截屏开关对该窗口同样生效。Manifest 注册 `exported=false + excludeFromRecents`。
2. **候选点击并发误触按钮回弹** → 幽灵事件拦截从只拦 `click` 扩为捕获阶段拦 `click/mousedown/mouseup` 三件套（按钮由 mousedown 触发是回弹根因），且候选 touchend 上屏时 `preventDefault()` 掐死合成鼠标事件源头。真实触摸有 touchstart 前置标记，不受影响。
3. **切回 App 白屏/无响应** → 三层根治：既有恢复重绘保留；新增 `onRenderProcessGone`→`rebuildWebView()`（渲染进程被杀立即重建）；新增白屏看门狗（onStartInputView ping JS，800ms 无回声=页面僵死→销毁重建）。重建后主题/模式/开关由 localStorage+持久层回灌（新增 `kbTheme`/`kbMode` 持久键，DOMContentLoaded 恢复），以最后一次状态呈现。
4. **[. X]/[- +] 左右单击不准** → `bindDualZone()`：按下坐标判半区、按下即出字（不再长按切换语义）；长按该半区=70ms 连续输出；滑走取消。
5. **分类动画真机生硬** → 动画改 Web Animations API 优先（真机 WebView 不依赖 CSS transition 时序），无 WAAPI 回退双 rAF；横向滚动弃 `scrollTo({behavior:'smooth'})`（部分 WebView 不支持→生硬跳切），改 rAF 自驱 `smoothScrollX`（easeOutCubic 260ms）。

## v12.2 优化
1. 编码栏分类被选中=Q弹气泡音 `playPop()`（sine 420→980Hz 上扬）；展开区符号/表情点选上屏=键盘声 `playClick()`。
2. 防截屏光环改**内沿圈际**（`box-shadow: inset` 三层：白金夹环+主题描边+内圈柔光呼吸），不再外包围。
3. 面板高度拖杆加长：`width:min(48%,190px)`（对标 iPhone 屏下触控条）。
4. 展开区与键盘区边界分明：`--panel` 各主题加深一档 + `.panel` 顶部 1.5px 分界线 + 内阴影（暗主题金色发丝线）。
5. emoji：字号 22→23 + antialiased（精致度上限取决于系统 WebView 版本 ≥98 用 COLRv1 字体）。
6. 符号/表情长按拖拽排序阈值 280ms→**500ms**（对标 iPhone 图标长按），滑动不再误触发拖动。
7. 九宫 Num 灯亮时 [1] 键保持原样（主字+副字结构不变，不再变小）。
8. Ctrl 层 [F] 键显示"造词"（editMap 加 F）且 `handleCtrl('f')`→`openWordMaker()` 双端（模拟器+Android override）接通——此前 Ctrl+F 是空操作。
9. **九宫功能层**：行3 变 `[Ctrl] 7 8 9 [Alt]`（点亮激活/再点退出/互斥）。Ctrl：2↑4←6→8↓、1全选 3音效 7剪切 9复制 5粘贴 0撤销、Num=造词；Alt：方向同 Ctrl、Num=Tab(下一段, moveCursor(12))。方向键可长按连发；功能层中 `t9press` 直接 return（防编码残留）；无动作键灰置。
10. **拼音词库跃升**：a) `DictEngine.querySentence`/`t9Sentence` DP 单段上限 6→14（`SENT_MAXSEG`，"晚点wandian"7码这类长词此前永远进不了整句，是"我晚点/我晚点过去"打不出的根因）；`canStart` 同步。b) 雾凇词组 topN 6万→**12万**，CI 阈值 PY≥10万。（搜狗细胞词库为专有格式且需逐库转换，本轮用雾凇 base+ext 12万高频等效覆盖日常短语；仍缺词再议）
11. **Shift(或Caps)+点击候选=删词**：多字词条 `userHideWord` 即删（toast"已删除"）；单字硬编码拦截提示"单字不可删除"。Ctrl+候选的前置/删除菜单保留。
12. **编码最高视野优先级**：`syncCodeBar` 同时监听全键盘 `code` 与九宫 `t9code`——面板开着时一旦有编码输入，分类条立即让位显示编码+候选；编码清空才回到分类条。
13. **[应用]轨道列车**：`.cat-strip.train` 底部铁轨+枕木背景；防截屏=车头（前圆弧）、剪贴隔离=车尾、中间"密码生成器/笔记本"=车厢（玻璃车身+双车轮搭轨+车厢间联轴器 `_dressCar()`）；横滑=整列在轨上滑动。开关激活时车头/车尾亮主题色。

## 主动修复（过程中发现）
- 主题/输入模式不持久：`kbTheme`/`kbMode` 入 `PERSIST_KEYS`，cycleTheme/cycleMode 落盘，开机回灌 → WebView 重建/重启后不再回默认。
- AppActivity 复制 → 键盘侧 `onStartInputView` 重新 `new ClipboardManager(this)`（同 prefs 跨窗口刷新），回键盘立即可粘贴。
- 九宫功能层动作经 `handleCtrl` 后的 `buildKb()` 在 pyGrid 态自动走 `buildPyGridKb()`，层态保持。

## 仍需真机验证（浏览器无法测）
独立窗口弹出/返回/在笔记本里打字、白屏看门狗（多 App 来回切）、候选并发根治、[. X] 双区手感、气泡/键盘音、12万词库效果（"很智慧/我晚点过去/我晚点回去"）、九宫 Ctrl/Alt 全键位、Shift 删词、emoji 精致度（升级 Android System WebView 后最佳）。

## 约定
- 面板高度存储键 `panelH3`/`emjH2`（改默认值时升级键名）。
- 符号分类：`{n,name,list,fixed?,cols?}`；fixed=禁排序固定排版。
- `CLIP_APPS` 条目：`{id,label,type:'app'|'toggle'}`；app→`openApp(id)`→优先 `Android.openAppWindow(id)` 独立窗口→回退 `openAppOverlay(id)`→`apps/{id}.html`。首条+末条自动当车头/车尾。
- 新增持久键必须同步进 `PERSIST_KEYS` 并在存档点补 `mirrorPrefs()`。
- 词库/emoji 字体在 CI 构建时生成 → **改词库相关必须重新跑 Actions 才进 APK**。
