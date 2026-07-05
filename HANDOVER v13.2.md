# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v13.2

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v13.2 = 记事本卡死总根治(线程模型铁律) + 资产加密 + 主题美化 + 词库 ext 接入 + 车厢重命名等。
> **测试：keyboard 56 + note 10 + 复合压力 8 + 5000次键盘×记事本联合压测(零异常/零状态破坏/零定时器泄漏) = 全绿。**

## 文件地图（勿忘）
- `keyboard.html`(仓库明文) / `apps/note.html` `apps/pwgen.html`(仓库明文，**CI 构建期加密**后 APK 内无明文)
- `.github/workflows/build.yml`：CI + B64 真身(`WUBIWEBVIEWIME_JAVA_B64`/`APPACTIVITY_JAVA_B64`/`ANDROIDMANIFEST_B64`/`SRC_B64`/`CONV_B64`)
- `dict-extra/`：`pinyin_extra.txt`(口语/文化/游戏短语~370条,自动简拼)、`modern_words.txt`、`prepare_dicts.py`
- 改 Java/Manifest/骨架 → 重编码回填 B64 并解码校验（本轮已同步）

## ⚠️ 线程模型铁律（v13.2 确立，违者必卡死）
**对自家 WebView 宿主(hostIsSelf=宿主包名==本包名，即记事本/密码器)：**
1. **绝不做任何读取型 InputConnection 调用**——`getTextBeforeCursor/getTextAfterCursor/getExtractedText/getSelectedText` 都是跨进程同步等待：binder 线程调→死锁；主线程调→卡 UI。真机症状=←→卡死、复制即卡死、Alt 层全卡死。
2. 一切光标/选中/编辑操作走 **sendKeyEvent 组合键单向投递**(handler.post 主线程)：方向/Home/End、Shift+方向=视觉行扩选、Ctrl+Home/End=文首末、Ctrl+C/X/A/Z=复制剪切全选撤销、TAB=跳下一块(段)。粘贴例外：读 sysClip(本进程)+commitText(单向) 安全。
3. `getSelectedText`/`cursorAtBoundary` JS 桥对自家宿主**短路返回默认值**（Chromium 光标到 editable 边界自停，无需边界锁）。
4. 外部宿主(EditText)：读取型调用进程内毫秒级，保留原路径 + `mcBusy` 忙则丢帧。

## v13.2 Bug 修复
1. **记事本横滑真根因**：grid blowout——网格项默认 min-width:auto，长数字串以 min-content 撑破 1fr 列宽。`minmax(0,1fr)`+卡片 min-width:0/overflow:hidden+摘要 break-all 三重根治。
2. **记事本快捷键全线卡死**：见上方铁律（moveCursor 整体重构 + copy/cut/selectAll/undo 组合键化 + 两桥短路）。
3. 展开池「全部候选」+× 顶栏冻结：`.pager-box` 不滚、`.pager-grid` 独立滚动。
4. 黑主题触控条：亮边框(0.28白)+微亮底+内高光，边际清晰。
5. 5000 次联合压测已建立（`test_marathon.js`）：键盘 21 类操作 × 记事本 7 类 × 混合叠加 3 类随机序列，每 500 步校验不变量（剪贴无重复/连发登记表不膨胀/块结构完好/词库结构完好），后续大改必跑。

## v13.2 优化
1. 防截屏激活环 3.5px→2px；车厢 21→26px 增高触控面积；表情间距再拉开(margin 3px+min-width 46)。
2. 面板橡皮筋回弹 `attachRubberBand`：符号/表情/剪贴板/候选池四处滚动容器，只在"已到边缘还继续拉"时接管(阻尼 0.35、上限 64px、过冲弹回曲线)，不干扰正常滚动。
3. 计算器 删除/归零、上屏/算式 双用键改 `bindDualZone` 坐标判区（与数字键盘完全同款：单击左块/右块即生效，左块可长按连发）；归零字样 13px/600 与算式一致。
4. 剪贴条上下紧贴相连（首尾圆角/中间直角+细分隔线）。
5. **九主题按钮全面提亮**：红→中国南红 #d9483a、绿 #2fae57、橙#f06a36/蓝#3b8ee6/紫#a44ef0/粉#e858ae/棕#a56428/青#14b2d4，--acd/--acl 同步；黑主题不动。黑主题剪贴条重做为金调暗玻璃(主题类挂 `#ime`，refreshClip 据此分支)。
6. pwgen：单词/拼音数量上限 7→9；ü 按正确拼音输出(nü/nüe/lü/lüe，弃 v 代写)。
7. **Shift+点击删词已取消**（Shift 态点击=正常上屏），删除统一 Ctrl+单击菜单(带确认弹窗)。
8. **车厢长按重命名**：长按 500ms 弹重命名浮层，借输入法自身上屏通道打字——`renameHookCommit/Delete/Enter` 拦截 commit/doDelete(两套定义各插钩)，回车=保存；名字存 `kbAppNames`(≤8字，空=恢复默认)，`appLabel()` 渲染。

## v13.2 词库
- **ext 词库真正接入**（此前只下载未参与转换=网络词全缺根因）：CI 里 `cat ice_ext.yaml >> ice_base.yaml`（read_rime 按行解析、非数据行自动跳过，可直接拼接），topN 提到 **250000≈全量**。王者荣耀/影视/网络词随 ext 全进。
- `pinyin_extra.txt` 扩至 ~370 条（+游戏/歌词/文化/祝福/购物/外卖高频块），共 734 词典条目含简拼。

## v13.2 安防体系（系统化答复"Root 也要安全"）
**分层防线（由外到内，各层职责与边界如实说明）：**
1. **代码层**：R8 minify+shrinkResources 全混淆（仅 keep JS 桥方法+组件入口）——提高逆向成本，非绝对。
2. **资产层（本轮新增）**：`apps/*.html` CI 构建期 AES-256-GCM 加密为 `assets/enc/*.bin`，**构建树内删除明文——APK 里没有任何可直接打开的应用页面**；运行时 `AppActivity.decryptAsset` 内存解密 `loadDataWithBaseURL`，overlay 走 `Android.loadAppHtml` 桥(白名单 note/pwgen)。密钥=拆分常量派生(CI 与 Java 同源，已端到端验证解密一致)。边界坦白：APK 内派生逻辑可被深度逆向还原，此层意义是杜绝"从目录路径直接打开 html"的零门槛访问。
3. **数据层（绝对防线）**：笔记 `.enc`、剪贴板条目均 AES-256-GCM，密钥在 **Android Keystore（TEE/StrongBox，不出安全硬件）**——Root 拉走全部文件也只有密文，密钥无法导出，这一层对 Root 依然成立。
4. **运行层**：FLAG_SECURE 防截屏、剪贴隔离(复制不留系统剪贴板)、无网络权限(数据物理不出设备)。
**结论**：Root 环境下"数据不可破获"由第 3 层保证（业界同准）；"代码不可读"没有任何 App 能绝对做到，第 1+2 层已把门槛提到需要专业逆向的程度。
**R8 注意**：新增 JS 桥方法必须带 @JavascriptInterface；`AppActivity.decryptAsset` 被 IME 跨类调用勿改可见性。

## 本轮上传清单
**必须上传：** `.github/workflows/build.yml`（两 Java B64+资产加密步骤+ext 接入+250000）、`keyboard.html`、`apps/note.html`、`apps/pwgen.html`、`dict-extra/pinyin_extra.txt`
**不必上传：** `prepare_dicts.py`、`modern_words.txt`（本轮未改）、旧 HANDOVER（可删）
