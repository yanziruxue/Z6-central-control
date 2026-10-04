#!/usr/bin/env node
/**
 * 桥接契约静态自检（纯文本分析，不需要真机、不需要 jsdom）
 *
 * 为什么需要它：
 *   v1.4.6 引入 `getAllAppsJson()` 时漏了 `@JavascriptInterface` 注解，而 SHIM 是
 *   `R.getAllAppsJson()` 调的 → Android 4.2+ 下 JS 侧等同「方法不存在」→ 调用抛错 →
 *   SHIM 的 `catch(e){cb([])}` **静默**转成空数组 → 页面显示「未读到已安装的应用」。
 *
 *   这个 bug 拖了 8 个版本（v1.4.6 → v1.4.14）才定位，因为：
 *     ① 编译通过、运行不崩溃、logcat 无异常；
 *     ② 所有 jsdom 冒烟全绿 —— 冒烟里的桥是 **JS 桩**，桩上 getAllApps 永远可用，
 *        结构性地测不出「原生侧没暴露」这类问题；
 *     ③ 症状与「车机真的没装应用」完全一样。
 *   唯一能提前拦住它的是**静态检查**，即本脚本。
 *
 * 检查项：
 *   A【致命】SHIM 里 `R.xxx(...)` 调用的每个方法，源码里必须带 `@JavascriptInterface`
 *   B【致命】页面里直接调用的 `L6Native.xxx`，必须在 SHIM 里定义（否则静默 undefined）
 *   C【提示】带注解但 SHIM 未调用（可能是死代码）
 *
 * 用法：
 *   node android/tools/bridge-contract-check.js
 *   退出码 0 = 通过，2 = 有致命问题
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
// 支持 L6_JAVA / L6_HTML 覆盖路径 —— 用于**反向自测**（故意去掉注解，确认脚本真的会报错，
// 排除「脚本恒绿」的假绿情形）。
const JAVA = process.env.L6_JAVA || path.join(ROOT, 'android', 'src', 'com', 'l6', 'carmedia', 'MainActivity.java');
const HTML = process.env.L6_HTML || path.join(ROOT, 'apk-dashboard-prototype.html');

let fail = 0;
let warn = 0;
function ok(name, pass, detail) {
  console.log((pass ? '  ✓ ' : '  ✗ ') + name + (detail ? '   → ' + detail : ''));
  if (!pass) fail++;
}
function tip(name, detail) {
  console.log('  · ' + name + (detail ? '   → ' + detail : ''));
  warn++;
}

const src = fs.readFileSync(JAVA, 'utf8');

/* ---------- 1) SHIM 里 R.xxx(...) 调用的原生方法名 ---------- */
const shimStart = src.indexOf('String SHIM =');
if (shimStart < 0) {
  console.log('✗ 找不到 SHIM 常量，脚本需同步更新');
  process.exit(2);
}
// SHIM 是一个很长的字符串拼接常量，取足够窗口再在结尾 "}(catch(e){}})()"; 处截断
const shimEndMark = src.indexOf('SHIM_DELAYED', shimStart);
const shimRaw = src.slice(shimStart, shimEndMark > 0 ? shimEndMark : shimStart + 12000);
const shim = shimRaw.slice(0, shimRaw.indexOf('"}(catch(e){}})()"') > 0
  ? shimRaw.indexOf('"}(catch(e){}})()"')
  : shimRaw.length);

const shimCalls = new Set();
for (const m of shim.matchAll(/R\.([A-Za-z_]\w*)\s*\(/g)) shimCalls.add(m[1]);

/* ---------- 2) 源码里带 @JavascriptInterface 的方法名 ---------- */
// ⚠️ 必须用「行首独立」匹配（`^[ \t]*@JavascriptInterface[ \t]*$`），不能用 indexOf 全局扫：
//    本项目的 javadoc 里也会出现 `@JavascriptInterface` 字样（就是在解释「漏了会怎样」），
//    全局扫会把**注释里的文字**当成真注解 → 注解已丢的方法被算成「有注解」→ 脚本假绿。
//    这一点是用「故意去掉注解」的反向自测实测到的（当时脚本仍报 22/22 全通过）。
const exposed = new Set();
const annoRe = /^[ \t]*@JavascriptInterface[ \t]*$/gm;
let am;
while ((am = annoRe.exec(src)) !== null) {
  const seg = src.slice(am.index, am.index + 800);
  const mm = seg.match(/public\s+[\w.<>\[\],\s]*?([A-Za-z_]\w*)\s*\(/);
  if (mm) exposed.add(mm[1]);
}

/* ---------- 3) SHIM 暴露给页面的桥名 ---------- */
// 注意：SHIM 的 JS 对象字面量 key **不带引号**（形如 `getAllApps:function(cb){...},`），
// 所以正则里不能要求引号 —— 这里曾写错成 ["']key["'] 导致桥名恒为 0、B 项全部误报。
const bridgeNames = new Set();
for (const m of shim.matchAll(/([A-Za-z_]\w*)\s*:\s*function/g)) bridgeNames.add(m[1]);

console.log('SHIM 桥 ' + bridgeNames.size + ' 个 | SHIM 调原生 ' + shimCalls.size + ' 个 | 带 @JavascriptInterface ' + exposed.size + ' 个\n');

console.log('== A. SHIM 调用的原生方法必须带 @JavascriptInterface（漏了 = 静默空数据）==');
const missingAnno = [...shimCalls].filter((n) => !exposed.has(n)).sort();
ok('SHIM 调用的 ' + shimCalls.size + ' 个原生方法全部带注解', missingAnno.length === 0,
  missingAnno.length ? '❌ 缺 @JavascriptInterface: ' + missingAnno.join(', ') : '');

console.log('\n== B. 页面直接调用的 L6Native.xxx 必须在 SHIM 里定义 ==');
const html = fs.readFileSync(HTML, 'utf8');
const pageCalls = new Set();
for (const m of html.matchAll(/L6Native\.([A-Za-z_]\w*)/g)) pageCalls.add(m[1]);
const undef = [...pageCalls].filter((n) => !bridgeNames.has(n)).sort();
ok('页面引用的 ' + pageCalls.size + ' 个桥全部已在 SHIM 定义', undef.length === 0,
  undef.length ? '❌ SHIM 未定义: ' + undef.join(', ') : '');

console.log('\n== C. 带注解但 SHIM 未调用（提示，可能为死代码）==');
const unused = [...exposed].filter((n) => !shimCalls.has(n)).sort();
if (unused.length) {
  tip('未通过 SHIM 调用 ' + unused.length + ' 个', unused.join(', '));
} else {
  console.log('  · 无');
}

console.log('\n结果: ' + (fail === 0 ? '全部通过' + (warn ? '（' + warn + ' 条提示）' : '') : '致命问题 ' + fail + ' 项'));
process.exit(fail === 0 ? 0 : 2);
