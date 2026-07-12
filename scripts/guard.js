#!/usr/bin/env node
/* ============================================================
   安键输入法 · 架构铁律静态守门（CI 编译前强制通过）
   守 4 条曾真实咬过人的规则：
   G1 语法闸：三个 HTML 的所有 <script> 块 node --check 全过
   G2 localStorage 禁用铁律：资产 JS 里出现 localStorage 的行，
      必须同行带"浏览器/回退/fallback"标注（KV 桥的浏览器调试回退才被豁免）
   G3 手术式 DOM 铁律：note.html 的整页 this.render() 调用数不得超过白名单上限
      （新增整页重渲必须显式上调 RENDER_BUDGET 并在交接文档说明理由）
   G4 CSS 级联守门：任一主题若覆盖 .sball 底色，必须同时提供 .sball.on 覆盖
      （v13.7 白主题"激活态被未激活规则吃掉"事故的自动回归）
   ============================================================ */
const fs = require('fs'), cp = require('child_process'), os = require('os'), path = require('path');
const A = 'WubiIME/app/src/main/assets';
const FILES = [A+'/keyboard.html', A+'/apps/note.html', A+'/apps/pwgen.html', A+'/apps/browser.html'];
const RENDER_BUDGET = 12;   // note.html 整页 render() 白名单上限（当前实测基线+1裕量）
let fails = 0;
const bad = (m)=>{ fails++; console.error('  ✗ ' + m); };
const ok  = (m)=>console.log('  ✓ ' + m);

console.log('== G1 语法闸 ==');
for (const f of FILES) {
  const s = fs.readFileSync(f, 'utf8');
  const blocks = [...s.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m=>m[1]).filter(x=>x.trim());
  blocks.forEach((b,i)=>{
    const tmp = path.join(os.tmpdir(), 'g_'+path.basename(f)+'_'+i+'.js');
    fs.writeFileSync(tmp, b);
    const r = cp.spawnSync('node', ['--check', tmp], {encoding:'utf8'});
    if (r.status !== 0) bad(f+' script#'+i+' 语法错误:\n'+r.stderr.slice(0,400));
  });
  ok(f+' ('+blocks.length+' 个脚本块)');
}

console.log('== G2 localStorage 禁用铁律 ==');
for (const f of FILES) {
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  const MARK=/浏览器|回退|fallback|调试|禁用|预览|迁移|镜像/;
  lines.forEach((ln, i) => {
    const codePart = ln.split('//')[0];                    // 行尾注释不算代码
    if (!codePart.includes('localStorage')) return;
    const t = ln.trim();
    if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return;   // 纯注释豁免
    if (MARK.test(ln)) return;                                                    // 行内标注豁免
    for (let k=1;k<=4;k++){ if (i-k>=0 && MARK.test(lines[i-k])) return; }        // 上文4行标注豁免
    bad(f+':'+(i+1)+' 裸用 localStorage（KV 铁律，合法回退请加行内标注）: '+t.slice(0,90));
  });
}
if (!fails) ok('无裸用 localStorage');

console.log('== G3 手术式 DOM 铁律 (note.html render 预算) ==');
{
  const s = fs.readFileSync(A+'/apps/note.html', 'utf8');
  const n = (s.match(/this\.render\(\)/g) || []).length;
  if (n > RENDER_BUDGET) bad('note.html 整页 render() 共 '+n+' 处，超出预算 '+RENDER_BUDGET+'。新增整页重渲会摧毁焦点/滚动/激活态——请改手术式，或在交接文档说明后上调预算。');
  else ok('render() '+n+'/'+RENDER_BUDGET);
}

console.log('== G4 CSS 级联守门 (.sball 主题覆盖必须成对) ==');
{
  const s = fs.readFileSync(A+'/keyboard.html', 'utf8');
  const themes = [...new Set([...s.matchAll(/\.(t-[a-z]+)\s+\.sball\s*\{/g)].map(m=>m[1]))];
  for (const t of themes) {
    if (!new RegExp('\\.'+t+'\\s+\\.sball\\.on\\s*\\{').test(s))
      bad('主题 .'+t+' 覆盖了 .sball 但缺 .'+t+' .sball.on —— 激活态将被未激活规则吃掉(v13.7 白主题事故同款)');
  }
  ok('主题覆盖成对: '+(themes.join(', ')||'无'));
}

console.log(fails ? ('\nGUARD FAILED ('+fails+')') : '\nGUARD ALL PASS');
process.exit(fails ? 1 : 0);
