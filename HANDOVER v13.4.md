# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v13.4

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v13.3 = 剪贴板漏洞收口 + 应用叠加 + 记事本手术式DOM + 造词模式联动 + 表情/符号内容整修。
> v13.4 = v13.3装机回归修复(返回错跳/重命名焦点锁/五笔九宫错配) + 导出体系分块协议根治 + 设置页剪贴板移除 + 词库智慧升级。
> **测试：v13.3批次93项 + v13.4批次88项 = 全绿（含jsdom DOM级、DP仿真、200轮随机压测）。**

## 文件地图（勿忘）
- `keyboard.html`(仓库明文) / `apps/note.html` `apps/pwgen.html`(仓库明文，**CI 构建期加密**后 APK 内无明文)
- `.github/workflows/build.yml`：CI + B64 真身(`WUBIWEBVIEWIME_JAVA_B64`/`APPACTIVITY_JAVA_B64`/`ANDROIDMANIFEST_B64`/`SETTINGSACTIVITY_B64`/`DICTENGINE_JAVA_B64`/`SRC_B64`/`CONV_B64`)
- `dict-extra/`：`pinyin_extra.txt`、`modern_words.txt`、`prepare_dicts.py`
- 改 Java/Manifest/Settings/DictEngine → 重编码回填 B64 并解码逐字节校验（本轮五个变量全部同步）

## ⚠️ 线程模型铁律（v13.2 确立，违者必卡死）
**对自家 WebView 宿主(hostIsSelf，即记事本/密码器)：**
1. **绝不做任何读取型 InputConnection 调用**（getTextBeforeCursor/getExtractedText/getSelectedText…）——binder线程调→死锁；主线程调→卡UI。v13.3 补刀：`deleteBefore` 对 hostIsSelf 改单向投递 KEYCODE_DEL（空块连删卡白屏根因）。
2. 一切光标/选中/编辑操作走 sendKeyEvent 组合键单向投递(handler.post 主线程)。
3. `getSelectedText`/`cursorAtBoundary` JS桥对自家宿主短路返回默认值。
4. 外部宿主(EditText)保留原读取路径 + mcBusy 丢帧。

## ⚠️ 隐私铁律（v13.3/v13.4 确立）
1. 剪贴板捕获**仅限键盘可见时**：`captureAndClearSystemClip` 前置 `isInputViewShown()`；键盘弹出补抓已删除。后台/未打字期间对任何 App 的复制一律不碰。
2. **设置页(App入口)不持有/不读取/不展示私有剪贴板**（v13.4 SettingsActivity 整段删除）。剪贴板只在键盘剪贴面板使用现场可调取。

## ⚠️ 记事本手术式DOM铁律（v13.3 确立）
**块级增删/调序绝不整页重渲**——整页 render() 会摧毁聚焦元素→输入连接断开→输入法重启+符号白屏(#3根因)、滚动归零跳文首(#4/#5根因)。回车断块 splitAtCaret、并块 mergeIntoPrev、×删除 onDel、空块退格、**拖拽调序 onDragEnd(v13.4)** 全部只动涉事 DOM 节点。共用 `blockHTML/blockDom/wireBlockEl/insertBlockAfter/removeBlockEl/caretTo/markActive`。

## ⚠️ 重命名焦点铁锁（v13.4 确立）
车厢重命名浮层激活(`_renameBuf!==null`)期间：
- doDelete：先删编码 → 编码空删名称缓冲 → 名称空也**绝不触碰宿主**（真机+模拟器两套定义同款）
- `handleCtrl/handleAlt/t9Arrow/触控条滑动` 四条宿主光标通道入口全部静默禁止
- commit/回车已由 renameHookCommit/Enter 拦截

## ⚠️ 九宫布局铁律（v13.4）
九宫仅属拼音。`buildPyGridKb()` 入口守卫 `if(mode!=='pinyin'){buildKb();return;}`——所有直接调用点（onIMEBlur/mkNum/toggleSound…）全免疫五笔+九宫错配；与 buildKb 的 `pyGrid && mode==='pinyin'` 守卫互不成环。

## ⚠️ 导出体系（v13.4 根治，勿回退旧协议）
**分块协议**：JS `saveBegin(name,mime)` → ≤512KB 逐块 `saveChunk(b64)` → `saveEnd()`。
- **AppActivity(独立窗口)**：saveEnd 返回 `"PICKER"` → 弹系统 ACTION_CREATE_DOCUMENT「保存至」对话框(用户亲选位置) → onActivityResult 写入 → `window.onSaveResult(ok,msg)` 回调。取消/失败均回调明示。
- **IME(键盘内嵌 overlay)**：无 Activity 上下文，saveEnd 同步 MediaStore 落盘，返回 `"OK:Download/文件名"` 或 `"ERR:原因"`。
- note.html `saveBlob` 是唯一出口，**成功/失败提示统一由它发出**；doExport 各格式分支的盲报"已导出✓"已全部清除——提示=真实落盘结果。旧单次大 base64 `saveFile` 已删（超大字符串单次跨桥失败=历史"显示成功没文件"根因之一）。

## v13.3 修复/优化摘要
1. 剪贴板漏洞收口（隐私铁律1）；导出桥 IME 侧补齐(后被 v13.4 分块协议取代)。
2. 应用叠加：Manifest launchMode→standard，AppActivity.PwBridge 新增 `openApp`(白名单 note/pwgen)；记事本导出面板密码框旁〝🔑 生成器〞按钮(AndroidPW.openApp → Android.openAppWindow 两级通道)。
3. 记事本手术式DOM(见铁律)；Tab 块间循环回绕(Shift+Tab反向，跳过分隔线)；×图标跟随激活块(.blk.on)；顶栏只留返回键盘、底部〝导出 ↧〞带字样。
4. 重命名光标并入文本span内紧贴文字(wm-caret)。
5. 造词模式快照 `_wmMode`(开启那刻锁定)，提交严格落入该模式词库；`userAddTop/userHideWord` 带 mkey 参；`isUserWord` 判定→英数用户词 Ctrl+单击可进菜单可删除。
6. onIMEBlur 释放 ctrl/ctrlLock/alt/t9Ctrl/t9Alt(v13.4 补 mode 条件)。
7. 九宫Ctrl层：全选/撤销图标化(_icoSelAll/_icoUndo)+副字，Ctrl/Alt 不显示"激活"副字。
8. 表情：手势右侧新增〝👾怪物〞类(非人脸/猫脸/龙🐲🐉🦖🦕/鬼怪机器人)；笑脸补人物emoji。符号中文类圆点 •◦‣▪▫。拼音类删大写音标(只留合法小写+ê/ḿ/ń/ň/ǹ)；部首类剔除30+成形单字只留偏旁。

## v13.4 修复/优化摘要
1. **返回键盘错跳设置页**：launchMode=standard 副作用——AppActivity 被压进 App 主任务。Manifest 加 `taskAffinity="com.wubi.ime.apps"` 独立任务栈根治。
2. 设置页剪贴板整段移除（隐私铁律2）。
3. 重命名焦点铁锁（见铁律）。
4. 五笔+九宫错配根治（见铁律；根因=v13.3 onIMEBlur 重建漏查 mode）。
5. 记事本拖拽调序改手术式搬移，滚动/激活块原地不动。
6. 导出分块协议+SAF对话框（见铁律）。
7. 边缘回弹重写 `_edgeSpring(el,axis)`：跟手阻尼0.34(rAF节流,上限84px)+释放弹簧回位(过冲曲线)，全passive不抢原生滚动；纵向挂符号网格/表情/剪贴/候选池，横向挂 catStrip。旧"一次性CSS踢动"已删。
8. 触控条箭头换加粗SVG尖角对；剪贴条去边框/内高光/分隔线为纯背景圆角块(margin 6px)。
9. 表情轨道撤销 spread(10类挤一屏)改可横滚，chip 大触区(padding 15px/min-width 56/height 30/margin 5px)；笑脸区移除 ZWJ 家族序列(多码点挤乱8列网格)，单码点家族👪💑💏👫👬👭保留。
10. **词库智慧升级**：① CI 并入雾凇 tencent 腾讯词向量词库(排 base/ext 后，缺频行 freq=0 只兜底不扰频序)，topN 250000→**320000**；② DictEngine 整句DP(querySentence+T9同款)加 `WORD_BONUS=3.0` 多字词奖励(按词成句：你总是这样/我明天再说/我晚点过去 一次成句)+`SKIP_PEN=36` 容错跳字(单处笔误原样带出不全句崩空；跳字>串长1/3 判失败不冒充整句)。

## v13.2 安防体系（不变，摘要）
代码层 R8 全混淆 → 资产层 apps/*.html CI 期 AES-256-GCM 加密(APK 无明文) → 数据层笔记/剪贴 AES-256-GCM 密钥在 Android Keystore(TEE，Root 拉文件也只有密文) → 运行层 FLAG_SECURE/剪贴隔离/零网络。
**R8 注意**：新增 JS 桥方法必须带 @JavascriptInterface（本轮 saveBegin/saveChunk/saveEnd/openApp 均已带）；`AppActivity.decryptAsset` 勿改可见性。

## 待办
- App 改名/换图标定制(activity-alias 多图标方案)：**等巧巧提供候选图标图片**后实施。

## 本轮上传清单
**必须上传（4个文件，覆盖仓库同名文件）：**
1. `.github/workflows/build.yml` —— 五个 Java/Manifest B64 全部更新 + tencent 词库步骤 + topN 320000
2. `WubiIME/app/src/main/assets/keyboard.html`
3. `WubiIME/app/src/main/assets/apps/note.html`
4. `HANDOVER v13.4.md`（可选，建议随仓库存档）

**不必上传：** `apps/pwgen.html`、`dict-extra/*`（本轮均未改）
