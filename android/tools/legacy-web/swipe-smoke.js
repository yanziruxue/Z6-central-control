/*
 * 手势体系冒烟测试（不依赖设备，用 jsdom 跑）。
 *
 * 覆盖 v1.4.20 的四方向可配置手势：
 *  ① 中心热区（屏幕 79.167%×72.222%，即原设计 1520×520）边界判定
 *  ② 默认绑定：down="nav"（下滑→当前导航源），up/left/right=""（不做任何事）
 *  ③ 触摸 / 滚轮 / 鼠标拖拽 三条输入路径都接进 inNavHot
 *  ④ 方向→坐标映射：上滑 dy<0 / 下滑 dy>0 / 左滑 dx<0 / 右滑 dx>0
 *  ⑤ 通道选择：nav 走 launchApp（带保活），其它 App 走 launchPkg（不保活）
 *  ⑥ 设置页禁用滑动、dock 按钮仍能双向翻页（主页上滑不再翻页后的入口护栏）
 *  ⑦ 绑定写入 localStorage 并还原；脏值经 normalizeGestures 兜住
 *
 * ★ 两个夹具陷阱：
 *   - jsdom 无布局引擎，getBoundingClientRect() 全返回 0 → 必须按 id 打桩还原 stage / navHot
 *   - startNav 自带 600ms 同 key 去重 → 同一轮多次测试必须重置 lastNavStart/lastNavKey
 *
 * 用法： node tools/swipe-smoke.js
 * 依赖： jsdom（node 环境变量 NODE_PATH 指向已装 jsdom 的 node_modules）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'MainActivity.java');
const HTML = path.resolve(ROOT, '..', 'apk-dashboard-prototype.html');

/* 热区：响应式改造后 #navHot 用百分比锚定 #stage，这里按「模拟视口 × 百分比」还原真实 rect。
   百分比必须与 HTML 里 #navHot 的 left/top/width/height 一致（见下方 CSS 断言）。 */
const HOT_PCT = { l: 0.10417, t: 0.13889, w: 0.79167, h: 0.72222 };
const VW = 1920, VH = 720;                      // 模拟车机屏（1920×720 下 --u 恰好 = 1）
const HOT = {
  l: VW * HOT_PCT.l, t: VH * HOT_PCT.t,
  r: VW * (HOT_PCT.l + HOT_PCT.w), b: VH * (HOT_PCT.t + HOT_PCT.h),
};
const CX = 960, CY = 360;                       // 热区中心
const OUT_X = 80, OUT_Y = 690;                  // 热区外（左边缘 / 底部 dock 区）
const PKG = 'com.demo.player';                  // 用于绑定测试的假包名

// 从 Java 源码里抽出真实 SHIM（保证测的就是打进 APK 的那串）
const java = fs.readFileSync(JAVA, 'utf8');
const m = java.match(/private static final String SHIM =([\s\S]*?);\n/);
if (!m) { console.error('✗ 未能从 MainActivity.java 解析 SHIM'); process.exit(1); }
const shim = m[1].split('\n').map(l => l.trim()).filter(l => l.startsWith('"'))
  .map(l => l.replace(/^"/, '').replace(/"\s*\+?\s*$/, '')).join('')
  .replace(/\\"/g, '"').replace(/\\'/g, "'");

const NativeRaw = {
  calls: [],
  getInstalledAppsJson: () => JSON.stringify([
    { pkg: 'com.autonavi.amapauto', key: 'amap', name: '高德地图(车机)', icon: '\uD83E\uDDED', type: 'nav' },
  ]),
  getAllAppsJson: () => JSON.stringify([
    { pkg: 'com.autonavi.amapauto', key: 'amap', name: '高德地图(车机)', icon: '\uD83E\uDDED', type: 'nav' },
    { pkg: PKG, key: PKG, name: '演示播放器', icon: '\u25A6', type: 'other' },
  ]),
  getAppIcon: () => '',
  launchApp(k) { this.calls.push(['launchApp', k]); },
  launchPkg(p) { this.calls.push(['launchPkg', p]); },
  launchMusic() {}, goHome() { this.calls.push(['goHome']); }, isDefaultHome: () => false, isNightMode: () => true,
};

const errs = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => errs.push('jsdomError: ' + e.message));
vc.on('error', e => errs.push('console.error: ' + e));

const dom = new JSDOM(fs.readFileSync(HTML, 'utf8'), {
  runScripts: 'dangerously', pretendToBeVisual: true, url: 'http://localhost/', virtualConsole: vc,
});
const { window } = dom;
const d = window.document;

setTimeout(() => {
  window.L6NativeRaw = NativeRaw;
  window.eval(shim);

  /* jsdom 无布局：按 id 打桩 rect —— #stage 铺满整个视口，#navHot 是它内部的百分比区域 */
  const rect = (l, t, r, b) => ({
    left: l, top: t, right: r, bottom: b, width: r - l, height: b - t, x: l, y: t,
    toJSON() { return {}; },
  });
  window.Element.prototype.getBoundingClientRect = function () {
    if (this.id === 'stage') return rect(0, 0, VW, VH);
    if (this.id === 'navHot') return rect(HOT.l, HOT.t, HOT.r, HOT.b);
    return rect(0, 0, 0, 0);
  };

  const vp = d.getElementById('viewport');
  const ev = (type, props) => {
    const e = new window.Event(type, { bubbles: true, cancelable: true });
    Object.keys(props).forEach(k => Object.defineProperty(e, k, { value: props[k] }));
    return e;
  };
  /* 统一滑动：从 (x,y) 起手，位移 (dx,dy) */
  function tSwipe(x, y, dx, dy) {
    vp.dispatchEvent(ev('touchstart', { touches: [{ clientX: x, clientY: y }] }));
    vp.dispatchEvent(ev('touchmove', { touches: [{ clientX: x + dx, clientY: y + dy }] }));
    vp.dispatchEvent(ev('touchend', {}));
  }
  function pSwipe(x, y, dx, dy) {
    vp.dispatchEvent(ev('pointerdown', { pointerType: 'mouse', clientX: x, clientY: y }));
    vp.dispatchEvent(ev('pointermove', { pointerType: 'mouse', clientX: x + dx, clientY: y + dy }));
    vp.dispatchEvent(ev('pointerup', {}));
  }
  const wheel = (x, y, deltaY) => vp.dispatchEvent(ev('wheel', { clientX: x, clientY: y, deltaY }));
  const click = el => el && el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

  const hit = t => NativeRaw.calls.filter(c => c[0] === t);
  // 每次断言前清调用记录 + 重置 startNav 的 600ms 去重
  const reset = () => { NativeRaw.calls.length = 0; window.eval('lastNavStart=0;lastNavKey="";'); };
  const curIdx = () => window.eval('idx');
  const toHome = () => window.eval('go(0)');
  const G = () => window.eval('JSON.stringify(settings.gestures)');
  const clearAll = () => ['up', 'down', 'left', 'right'].forEach(k => window.eval(`setGesture("${k}", "")`));

  const R = [];
  const check = (name, pass, detail) => R.push([name, !!pass, detail || '']);

  /* ---------- ① 结构 ---------- */
  check('热区 #navHot 存在', !!d.getElementById('navHot'));
  check('全屏舞台 #stage 存在', !!d.getElementById('stage'));
  // ===== 响应式重排护栏：不再有 1920×720 固定画布，也不再整体 scale =====
  const rawHtml = fs.readFileSync(HTML, 'utf8');
  check('#stage 铺满视口（无固定画布、无 --l6s 整体缩放）',
    /#stage\{position:fixed;inset:0;width:100%;height:100%/.test(rawHtml) &&
    !/--l6s/.test(rawHtml) && !/translate\(-50%,-50%\) scale/.test(rawHtml));
  const hotCss = ((rawHtml.match(/#navHot\{[^}]*\}/) || [''])[0] || '').replace(/\s+/g, ' ');
  check('#navHot 用百分比锚定（与冒烟夹具 HOT_PCT 一致）',
    /left:10\.417%/.test(hotCss) && /top:13\.889%/.test(hotCss) &&
    /width:79\.167%/.test(hotCss) && /height:72\.222%/.test(hotCss), hotCss);
  const uNow = parseFloat(d.documentElement.style.getPropertyValue('--u'));
  const uWant = Math.max(0.4, Math.min(3, Math.min(window.innerWidth / 1920, window.innerHeight / 720)));
  check('fitUnit() 已按真实视口写入 --u = min(vw/1920, vh/720)',
    typeof window.fitUnit === 'function' && Math.abs(uNow - uWant) < 1e-3,
    `--u=${uNow} 期望=${uWant.toFixed(4)}（jsdom 视口 ${window.innerWidth}×${window.innerHeight}）`);
  check('尺寸已改为 calc(N * var(--u))（随视口缩放）',
    (rawHtml.match(/var\(--u\)/g) || []).length > 300,
    `var(--u) 出现 ${(rawHtml.match(/var\(--u\)/g) || []).length} 次`);
  check('手势容器 #setGestures 存在', !!d.getElementById('setGestures'));
  ['inNavHot', 'handleSwipe', 'runGesture', 'setGesture', 'renderGestures', 'gestureItem']
    .forEach(fn => check(`函数 ${fn} 已定义`, window.eval(`typeof ${fn}`) === 'function'));

  /* ---------- ② 热区边界 ---------- */
  check('圆心命中', window.inNavHot(CX, CY) === true);
  check('内侧 1px 命中', window.inNavHot(HOT.l + 1, HOT.t + 1) && window.inNavHot(HOT.r - 1, HOT.b - 1));
  check('外侧 1px 不命中',
    !window.inNavHot(HOT.l - 1, CY) && !window.inNavHot(HOT.r + 1, CY) &&
    !window.inNavHot(CX, HOT.t - 1) && !window.inNavHot(CX, HOT.b + 1));

  /* ---------- ③ 默认值（下滑=nav，上滑=返回原车机桌面，左右空）---------- */
  check('默认 gestures = {up:"home",down:"nav",left:"",right:""}',
    G() === JSON.stringify({ up: 'home', down: 'nav', left: '', right: '' }), G());
  check('手势卡片渲染出 4 行', d.querySelectorAll('#setGestures .ges-row').length === 4,
    `实得 ${d.querySelectorAll('#setGestures .ges-row').length} 行`);
  check('每行只有「选择应用 / 默认」2 个按钮',
    d.querySelectorAll('#setGestures .ges-row').length > 0 &&
    [...d.querySelectorAll('#setGestures .ges-row')].every(r => r.querySelectorAll('button').length === 2) &&
    [...d.querySelectorAll('#setGestures .ges-row')].every(r => /默认/.test(r.querySelectorAll('button')[1].textContent || '')),
    `实得 ${[...d.querySelectorAll('#setGestures .ges-row')].map(r => r.querySelectorAll('button').length).join('/')}`);
  check('「清除」按钮已改为「默认」',
    !/清除/.test([...d.querySelectorAll('#setGestures button')].map(b => b.textContent).join('|')),
    [...d.querySelectorAll('#setGestures button')].map(b => b.textContent).join(' / '));
  // 「跟随导航源」按钮已按需求移除；但它仍是 "nav" 绑定值的语义（默认下滑）， UI 上不能再出现入口
  check('「跟随导航源」按钮已移除',
    !/跟随导航源/.test([...d.querySelectorAll('#setGestures button')].map(b => b.textContent).join('|')),
    [...d.querySelectorAll('#setGestures button')].map(b => b.textContent).join(' / '));
  check('默认下滑仍显示为「跟随导航源」（保留 nav 语义）',
    /跟随导航源/.test(d.querySelectorAll('#setGestures .ges-row')[1].textContent || ''),
    (d.querySelectorAll('#setGestures .ges-row')[1] || {}).textContent);

  /* ---------- ④ 默认：下滑 → 调导航 ---------- */
  toHome(); reset();
  tSwipe(CX, CY, 0, 60);
  check('默认·下滑(dy>0) → 调起导航 App', hit('launchApp').length === 1,
    `launchApp=${JSON.stringify(hit('launchApp'))}`);

  /* ---------- ⑤ 默认：上滑 → 返回原车机桌面（goHome）；左/右 → 什么都不做 ---------- */
  toHome(); reset();
  tSwipe(CX, CY, 0, -60);
  check('默认·上滑(dy<0) → 返回原车机桌面（与 dock 小房子同一条 goHome）',
    hit('goHome').length === 1 && hit('launchApp').length === 0 && hit('launchPkg').length === 0,
    `goHome=${hit('goHome').length} launchApp=${hit('launchApp').length}`);
  [['左滑', -60, 0], ['右滑', 60, 0]].forEach(([n, dx, dy]) => {
    toHome(); reset();
    tSwipe(CX, CY, dx, dy);
    check(`默认·${n} → 不触发任何拉起`,
      hit('launchApp').length === 0 && hit('launchPkg').length === 0
        && hit('goHome').length === 0 && curIdx() === 0,
      `launchApp=${hit('launchApp').length} launchPkg=${hit('launchPkg').length} goHome=${hit('goHome').length}`);
  });

  /* ---------- ⑥ 热区外四方向都不响应 ---------- */
  [['下滑', 0, 60], ['上滑', 0, -60], ['左滑', -60, 0], ['右滑', 60, 0]].forEach(([n, dx, dy]) => {
    toHome(); reset();
    tSwipe(OUT_X, OUT_Y, dx, dy);
    check(`热区外·${n} → 不响应`,
      hit('launchApp').length === 0 && hit('launchPkg').length === 0 && hit('goHome').length === 0);
  });

  /* ---------- ⑦ 三条输入路径 ---------- */
  toHome(); reset();
  wheel(CX, CY, 20);
  const wheelIn = hit('launchApp').length;
  toHome(); reset();
  wheel(OUT_X, CY, 20);
  const wheelOut = hit('launchApp').length;
  check('滚轮·区内触发 / 区外不触发', wheelIn === 1 && wheelOut === 0, `in=${wheelIn} out=${wheelOut}`);

  toHome(); reset();
  pSwipe(CX, CY, 0, 60);
  const pdIn = hit('launchApp').length;
  toHome(); reset();
  pSwipe(OUT_X, CY, 0, 60);
  const pdOut = hit('launchApp').length;
  check('鼠标拖拽·区内触发 / 区外不触发', pdIn === 1 && pdOut === 0, `in=${pdIn} out=${pdOut}`);

  /* ---------- ⑧ 方向映射：绑定到任意 App，只有对应方向触发 ---------- */
  const dirMap = [['up', 0, -60], ['down', 0, 60], ['left', -60, 0], ['right', 60, 0]];
  dirMap.forEach(([dir, dx, dy]) => {
    clearAll();
    window.eval(`setGesture("${dir}", "${PKG}")`);
    toHome(); reset();
    tSwipe(CX, CY, dx, dy);
    const selfHit = hit('launchPkg').length === 1 && hit('launchPkg')[0][1] === PKG;
    let otherHit = null;
    dirMap.filter(x => x[0] !== dir).forEach(([od, odx, ody]) => {
      toHome(); reset();
      tSwipe(CX, CY, odx, ody);
      if (hit('launchPkg').length > 0) otherHit = od;
    });
    check(`方向映射·${dir} 只由对应位移触发（且走 launchPkg）`, selfHit && otherHit === null,
      `本方向=${selfHit} 误触方向=${otherHit || '无'}`);
  });

  /* ---------- ⑨ "nav" 跟随导航源，且走 launchApp ---------- */
  clearAll();
  window.eval('setGesture("down","nav")');
  toHome(); reset();
  tSwipe(CX, CY, 0, 60);
  check('nav 走 launchApp 且带出当前导航源 key',
    hit('launchApp').length === 1 && hit('launchApp')[0][1] === 'amap' && hit('launchPkg').length === 0,
    JSON.stringify(hit('launchApp')));

  /* ---------- ⑩ 持久化 + 脏值归一化 ---------- */
  window.eval(`setGesture("right", "${PKG}")`);
  let saved = {};
  try { saved = JSON.parse(window.localStorage.getItem('l6_settings_v1') || '{}'); } catch (e) { }
  check('绑定写入 localStorage', !!saved.gestures && saved.gestures.right === PKG,
    JSON.stringify(saved.gestures));
  window.eval('settings.gestures={up:123,down:null};normalizeGestures();');
  check('normalizeGestures 归一化脏值（非字符串 → 回落新默认 up=home）',
    G() === JSON.stringify({ up: 'home', down: 'nav', left: '', right: '' }), G());

  /* ---------- ⑪ 设置页禁用 + dock 入口护栏 ---------- */
  clearAll();
  click(d.querySelector('[data-go="set"]'));
  const onSet = curIdx();
  reset();
  tSwipe(CX, CY, 0, 60);
  check('设置页·滑动完全禁用',
    onSet === 1 && hit('launchApp').length === 0 && hit('launchPkg').length === 0, `idx=${onSet}`);
  click(d.querySelector('[data-go="home"]'));
  const backHome = curIdx();
  click(d.querySelector('[data-go="set"]'));
  const toSet = curIdx();
  check('dock 主页/设置按钮仍能双向翻页', backHome === 0 && toSet === 1, `home=${backHome} set=${toSet}`);

  /* ---------- ⑫ v1.5.15：「默认」按钮 + 老存档一次性迁移 ---------- */
  clearAll();
  window.eval('setGesture("down","com.x.y");setGesture("up","com.x.y")');
  const rows = d.querySelectorAll('#setGestures .ges-row');
  click(rows[1].querySelectorAll('button')[1]);      // 下滑行的「默认」
  click(rows[0].querySelectorAll('button')[1]);      // 上滑行的「默认」
  check('「默认」按钮把下滑恢复成跟随导航源、上滑恢复成返回原车机桌面',
    JSON.parse(G()).down === 'nav' && JSON.parse(G()).up === 'home', G());

  window.eval('localStorage.removeItem("l6_gesture_up_v1515");'
    + 'settings.gestures={up:"",down:"nav",left:"",right:""};normalizeGestures();');
  check('老存档 up="" 被一次性迁移补成 home（只改 def 不生效）',
    JSON.parse(G()).up === 'home', G());

  window.eval('localStorage.removeItem("l6_gesture_up_v1515");'
    + 'settings.gestures={up:"com.pkg.a",down:"nav",left:"",right:""};normalizeGestures();');
  check('迁移不覆盖用户自己绑定的应用', JSON.parse(G()).up === 'com.pkg.a', G());

  /* ---------- 输出 ---------- */
  const pass = R.filter(r => r[1]).length;
  R.forEach(([n, ok, det]) => console.log(`${ok ? '✓' : '✗'} ${n}${det ? '  [' + det + ']' : ''}`));
  const noise = errs.filter(e => !/HTMLMediaElement|getContext|Not implemented/.test(e));
  console.log(`\n通过 ${pass}/${R.length}；运行时错误 ${noise.length}`);
  noise.slice(0, 6).forEach(e => console.log('  ! ' + e));
  window.close();
  process.exit(pass === R.length && noise.length === 0 ? 0 : 1);
}, 300);
