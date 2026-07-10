# 安键输入法 (AnKey IME) — 交接文档 HANDOVER_v13.6

> 给新窗口的 Claude：读完本文件即可完整接手。巧巧用 iPhone 通过 GitHub Actions 编译这个 Android WebView 输入法。
> v13.4 = 装机回归修复 + 导出分块协议 + 词库智慧升级。
> **v13.5 = 白屏/卡死双 Bug 根治（渲染心跳看门狗 + onWindowShown 通路 + destroy崩溃修复）+ 轨道交互大改（主题/模式轨道直选、白主题、双环英文键盘）+ 计算器前置插入 + 大容量分块数据桥。**
> 测试：Java 语法解析、看门狗状态机 7 场景、calcEval 13 用例、计算器前置状态机 10 场景、分块协议 5+7 用例（含代理对最坏情形地毯式）、jsdom DOM 级（note 插块 4 项、keyboard 交互 21 项）——全绿。

## 文件地图（勿忘）
- `keyboard.html`(仓库明文) / `apps/note.html` `apps/pwgen.html`(仓库明文，**CI 构建期加密**后 APK 内无明文)
- `.github/workflows/build.yml`：CI + B64 真身(`WUBIWEBVIEWIME_JAVA_B64`/`APPACTIVITY_JAVA_B64`/`ANDROIDMANIFEST_B64`/`SETTINGSACTIVITY_B64`/`DICTENGINE_JAVA_B64`/`THEMEMANAGER_B64`/`SRC_B64`/`CONV_B64`)
- 改 Java/Manifest → 重编码回填 B64 并解码逐字节校验（本轮 WUBIWEBVIEWIME/APPACTIVITY 两个变量已同步）

## ⚠️ 稳定性体系（v13.5 确立，两大顽疾根治）
**Bug1 白屏/崩溃、Bug2 从笔记本返回"有声无画"卡死，数十版本未根治的原因与修法：**
1. **rAF 渲染心跳看门狗**：老看门狗只 ping JS(`evaluateJavascript("1")`)——"有声无画"(JS 活着、触摸有音效、画面冻结)永远检不出。新增 `Android.wdBeat(seq)`：注入 `requestAnimationFrame(()=>wdBeat)`，JS 活但 900ms 无出帧 = Surface 冻结 → `repairSurface()` 软复位 → 再 700ms 无心跳 → `rebuildWebView()` 核弹重建。`pageReady`(onPageFinished) 防加载期误判；`wdSeq++` 作废在途看门狗。
2. **onWindowShown 通路**：从 AppActivity(笔记本/密码器)返回时 IME 窗口只走 hide→show **不触发 onStartInputView**，修复逻辑从未运行——Bug2 只能"退出宿主再进"救活的直接原因。`onWindowShown()` 补上（`lastRepairAt` 250ms 与 onStartInputView 去重）。
3. **destroy-attached 崩溃**：`rebuildWebView`/`onDestroy` 曾对仍挂在视图树上的 WebView 直接 `destroy()`(官方明令禁止，部分机型崩进程——"白屏→重建→崩溃"连锁根源)。现：先 setInputView 新视图 → 旧视图脱树 → post 后销毁。
4. **软复位强化**：同步 GONE→VISIBLE 同帧被视图系统合并优化=没修。改 INVISIBLE 立即生效 + post VISIBLE，强制两次真实绘制提交；层切换 HW→SW→HW + imeRepaint 两段补刀保留。

## ⚠️ 线程模型铁律（v13.2，不变）
对自家 WebView 宿主(hostIsSelf)：绝不做读取型 InputConnection 调用；一切光标/选中走 sendKeyEvent 单向投递；getSelectedText/cursorAtBoundary 对自家宿主短路。外部宿主保留读取路径 + mcBusy 丢帧。

## ⚠️ 隐私铁律（v13.3/v13.4，不变）
剪贴板捕获仅限键盘可见时；设置页不持有/不读取/不展示私有剪贴板。

## ⚠️ 记事本手术式DOM铁律（v13.3，v13.5 补一处违规）
块级增删/调序绝不整页重渲。**v13.5 修复：`onAdd`(增加文本块)原实现整页 render() 违反铁律**，现改手术式 `insertBlockAfter`，且新块插到**光标所在块(激活块 .blk.on)的下方**，无激活块才落文末（#14）。添加菜单收起=只摘 .add-sheet/.add-scrim 节点。

## ⚠️ 大容量分块数据桥（v13.5 新增，两处 PwBridge 同构）
`dataSaveBegin(app)→dataSaveChunk(str)*n→dataSaveEnd()` / `dataLoadBegin(app,useBak)→dataLoadNext()*n(前缀"D"=数据,""=正常结束,"ERR:"=失败)`。
- Java 端 CipherOutputStream/CipherInputStream **流式 AES-GCM，内存 O(块)**；文件格式与旧 saveAppData **完全一致(IV+GCM 流)——新旧互通零迁移**。tmp+bak+原子换名保持。
- 读取用 InputStreamReader 按**字符**边界返回(防多字节切断)；JS 切块**避开 UTF-16 高代理**(块尾是 emoji 前半则回退 1 字符——否则 getBytes 编出替换字符污染内容，本轮实测抓获并修复)。
- note.html Store：串长 >480K 走分块，否则旧单次(快)；主文件 GCM 校验失败自动回退 .bak。
- **容量结论(#15)**：Java 端已到文件系统级可靠(数十 MB 稳)；剩余瓶颈在 JS 端整串 JSON.parse——**单本安全水位 ≤3MB(提示分本)、12MB 硬拒绝**；"按本切块"架构下总量几十至上百 MB 均可靠(打开哪本才载哪本)。

## v13.5 功能清单
1. **主题轨道直选(#3)**：点颜色球 → 全部主题球在编码栏轨道排开点选(`toggleThemeStrip/buildThemeStrip/setThemeIdx`)。新增 **t-white 白·磨砂玻璃主题**(iOS 键盘质感：石墨强调色/白瓷键帽/冷雾底盘)，白瓷 theme-ball.white；所有主题选择器组已批量追加 .t-white(变量驱动)。
2. **模式轨道直选(#4)**：点模式键 → 五笔/拼音/九宫/英文排开(`toggleModeStrip/applyModeOpt`，九宫=pinyin+pyGrid)。**英文模式下模式键呈未激活白瓷质感**(#modeBtn.plain)；模式标签九宫态显示"九宫"。
3. **轨道选中样式统一(#1/#2)**：符号分类选中改与表情同款灰底(.cat-chip.on 不再主题色渐变，t-dark 白雾变体)；表情 chip 间距收窄与符号一致(margin 0/padding 11px)。
4. **Ctrl+Shift 切输入法(#6)**：两个按键顺序都通(`pressShift` 见 ctrl、`_ctrlToggle` 见 shift)→`Android.switchNextIME()`(API28+ switchToNextInputMethod，低版本 IMM 回退，主线程)。Ctrl 层 Shift 键面换**地球"输入法"图标**；九宫 Ctrl 层按 Shift 同款生效。
5. **九宫双环英文键盘(#7)**：九宫行2左空位 = **⇧英文键**→`t9Shift=true`→`buildRingKb()`。布局：左列(⇧点亮退出/符号)+中央双环+右列(⌫/空格/回车)。内环8键 f g l k j h s d(home 排贴中心)、外环18键+**10°齿轮错位嵌缝**；中心键=大小写切换；字母长按500ms 拖拽**两键换位**(kbRingOrder 持久)。onIMEBlur 释放 t9Shift；buildKb/buildPyGridKb 入口恢复行 display(双环层隐藏 r2-r4)。尺寸兜底(clientWidth=0 也渲染)。
6. **数字键盘紧凑(#8)**：行距 10→6px、键高 44→47(总高 220 不变无跳动)。
7. **计算器前置插入(#9)**：% 键改双用[% | 前置]。按[前置]→当前算式括为`(...)`进前缀态(显示`前缀▸(旧式)`)：数字进缓冲、按运算符封口完成前置(`5000÷(85223×629)`)，**可再叠加**(再括一层)；前缀态按 DEL 回退/退出、按 = 默认×。=后按运算符=以结果续算、按数字=开新算式(calcSealed)。**calcEval 已扩括号**(词法/一元负号(后/shunting-yard/配对校验)。
8. **九宫功能层字样调大(#10)**：Ctrl/Alt 键名 13→15px；t9fnsub 副字 14→15px。
9. **符号分类(#11)**：标记改名"**特殊**"调至第5位、箭头第6位(数组物理重排，_symOrder 按新索引，不考虑旧序迁移)；**网址去 fixed 可长按排序**(保 cols:4 等宽)。
10. **拖拽自动滚动(#5)**：`dragAutoScroll(scroller,y)` rAF 自驱——拖到容器上/下 40px 热区连续滚动(4→16px/帧随贴边深度加速)；符号滚动容器=grid、表情=外层 panel-scroll；cleanupDrag 统一 `dragAutoScrollStop()`。
11. **滚动质感(#12)**：`attachFlingBounce`——惯性甩动撞端点按余速弹性过冲回位(补 _edgeSpring 只管"按住拉"的缺口)，纵横皆挂；`attachScrollHint`——自绘细进度条(主题色渐变，贴右缘，滚动显形/静止600ms淡出)挂全部纵向滚动区。
12. **车厢重命名联动(#16)**：键盘与 note/pwgen **同源 file:// 共享 localStorage**——应用启动/聚焦/可见性变化时读 `kbAppNames` 联动标题("小小 · 笔记本"/"01车 · 密码生成器")；note 品牌在 libHTML 模板内联取名(防 render 覆盖)，pwgen React 渲染后按 id 改写+轻量兜底。**补漏：kbAppNames/kbRingOrder 加入 PERSIST_KEYS + setAppName 补 mirrorPrefs**(此前 WebView 数据清除会丢车厢名)。
13. **密码历史左滑删除(#13)**：pwgen 历史行左滑(-24px 阈值)滑开红色"删除"钮单条删除；右滑/点击合拢；swipedHist state + histTouch 判横滑防误触复制。

## v13.2 安防体系（不变）
R8 全混淆 → apps/*.html CI 期 AES-256-GCM 加密 → 数据 AES-256-GCM 密钥在 Android Keystore(TEE) → FLAG_SECURE/剪贴隔离/零网络。
**R8 注意**：新增 JS 桥方法必须带 @JavascriptInterface（本轮 wdBeat/switchNextIME/dataSave*/dataLoad* 均已带）；`AppActivity.decryptAsset` 勿改可见性。

## 待办
- App 改名/换图标定制：等巧巧提供候选图标图片。
- 双环键盘装机手感调优（键径/环距参数在 buildRingKb 顶部，一处可调）。



## ⚠️ v13.6 铁律：localStorage 全面禁用
巧巧明令：localStorage 是浏览器缓存技术，被清缓存/系统管家优化即丢数据，**本软件禁用**。
- keyboard.html：`KV` 持久层取代——真身=Java 桥 AES-256-GCM 加密文件 kbprefs.enc(Keystore 硬件密钥)。启动同步载入内存→读即取→写=内存即改+200ms防抖落盘→visibilitychange/pagehide/onIMEBlur 强制 flush。旧 localStorage 数据首启一次性迁移进桥。全部 `localStorage.get/setItem` 调用已替换为 `KV.get/set`；`mirrorPrefs()` 收敛为 `KV.flush()` 壳。**新增持久键直接 KV.set 即可，无需登记。**无桥(浏览器调试)才回退 localStorage。
- note.html：Store 桥文件为主(原有)；kbAppNames 联动改为桥读 kbprefs。
- pwgen.html：历史走桥(原有)；联动同改桥读。

## v13.6 功能清单
1. **白主题统一(#1)**：`.t-white .sball` 全部工具按钮统一冷白瓷配方，英文 plain 键与白色球与其它按钮完全一致。
2. **九宫英文层重做(#2)**：双环废弃 → **multi-tap 经典九宫英文**(与拼音九宫同构骨架)：行1 [A/a大写锁][1符号.,?!'-@][2abc][3def][⌫]；行2 [⇧Shift点亮退出] 4/5/6 [0]；行3 [,] 7/8/9 [.]；行4 计算器/空格/回车。700ms 内连按同键=删上一字循环下一字(mtPress)；长按数字键=直接输数字；Shift 键面字样="Shift"；无符号按钮。
3. **剪贴卡片(#3)**：左缘主题色签条+双行clamp预览+字数微标+按压微缩，圆角12卡片。
4. **(插算)常驻token(#4)**：% 键恢复纯%；显示屏算式前常驻可触 `(插算)`——有内容后灰显、前置态点亮描边；点击=calcKey('PREP')括住前置(可复加)；纯 UI 元素绝不进 calcExpr、上屏(PUT/PUTALL 取变量)永不带。
5. **表情乱码根治(#5)**：符号分类星座♊等 68 个 BMP 符号(U+2000-2BFF)追加 VS16(U+FE0F) 强制彩色 emoji 字形——文本呈现在部分厂商字体缺字=问号的根因。
6. **球阴影(#6)**：轨道主题球默认无阴影，`.cat-chip.on .theme-ball` 选中才有。
7. **键距(#7)**：主键盘 .kb-row gap 5→3px。
8. **笔记本(#8)**：note 窗口**无条件 FLAG_SECURE**(不能截屏)；删除导出区"生成器"入口(自家应用切换走车厢)；切后台三通道强制落盘(visibilitychange/pagehide/blur→flush 原有)；块 click 补 markActive 兜底。
9. **剪贴分家(#9)**：IME 剪贴监听对自家宿主(ei.packageName==自家包)一律跳过——note 内复制绝不进输入法私有剪贴板；note 用 document copy/cut 事件捕获选区进**自己的加密贴**(note_clips, 置顶去重限50)，readClips=自有贴置顶+IME私有去重后附(外部复制仍可粘入)。
10. **剪贴交互(#10)**：条目/清空按钮误触根治——绝不在 touchstart 触发，touchend 实测 changedTouches 位移+容器 scrollTop 双判定(滚动接管后 touchmove 不派发的根因)；清空=幽灵药丸→武装态填充警示红两段确认；回弹曲线升级 cubic-bezier(.22,1.9,.36,.94) 0.55s 大过冲柔性震荡(边拉/惯性撞墙两处同步)。
11. **钢琴音效(#11)**：`playPiano(midi)`=4谐波加性+轻微失谐+锤击噪声瞬态+双段衰减+音高追随低通；`playChord`=6ms琶音错位。主键盘 QWERTYUIOP+ASDFGHJKL=C3→G5 十九白键低中高音区；ZXCVBNM=C/Dm/Em/F/G/Am/G7 七个百搭和弦；九宫 2~9=do~高do(C4→C5)、1=C和弦、0=G和弦。接线：addKeyListeners 的 fire 读 `div._snd`(元素级声音覆盖)，修饰层(Ctrl/Alt)自动回落机械click；音效总开关(kbSound)不变。

## v13.6 测试记录
keyboard jsdom 24项(KV/multi-tap循环大小写超时/插算token全链/％回归/轨道/钢琴映射/VS16)全绿；note jsdom 5项(自有贴去重/copy事件捕获/入口删除/插块位置)全绿；三 HTML+两 Java 语法全检 PASS；B64 逐字节回验一致。

## 本轮上传清单
**必须上传（5个文件，覆盖仓库同名文件）：**
1. `.github/workflows/build.yml` —— WUBIWEBVIEWIME/APPACTIVITY 两个 B64 已更新
2. `WubiIME/app/src/main/assets/keyboard.html`
3. `WubiIME/app/src/main/assets/apps/note.html`
4. `WubiIME/app/src/main/assets/apps/pwgen.html`
5. `HANDOVER v13.6.md`（建议随仓库存档）

**不必上传：** `dict-extra/*`（本轮未改）

## 装机验证要点
1. 微信↔各 app 反复切输入框：白屏应≤1秒自动恢复，不再崩溃。
2. 从笔记本返回后立即打字：不再卡死(有声无画会被心跳检出自动复位)。
3. 点颜色球→轨道选白色主题；点模式键→轨道点"九宫"/"英文"(英文时模式键变白瓷)。
4. 九宫按⇧进双环英文；Ctrl 亮时按⇧切系统输入法。
5. 计算器：算 85223×629= 后按[前置]→5000→÷→= 应得 0.00009327。
6. 符号"网址"长按可排序；"特殊"在第5位；末排长按拖到顶部边缘自动上滚。
7. 车厢改名后打开应用内部标题联动。
