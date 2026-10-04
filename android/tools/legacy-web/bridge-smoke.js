/*
 * L6Native 桥接冒烟测试（不依赖设备，用 jsdom 跑）。
 *
 * 作用：把 MainActivity.java 里真实的 SHIM 适配层注入原型页面，用假的 L6NativeRaw
 *       （模拟 PackageManager 返回）验证：设置页音乐源/导航项是否由原生数据重建、
 *       「启动导航」是否带出当前选中 key、「上传壁纸」通道是否可调用、是否 0 运行时错误。
 *
 * 用法： node tools/bridge-smoke.js
 * 依赖： jsdom（node 环境变量 NODE_PATH 指向已装 jsdom 的 node_modules）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'MainActivity.java');
const HTML = path.resolve(ROOT, '..', 'apk-dashboard-prototype.html');

// 从 Java 源码里抽出 SHIM 常量（保证测的就是打进 APK 的那串）
const java = fs.readFileSync(JAVA, 'utf8');
const m = java.match(/private static final String SHIM =([\s\S]*?);\n/);
if (!m) {
  console.error('✗ 未能从 MainActivity.java 解析 SHIM');
  process.exit(1);
}
const shim = m[1].split('\n')
  .map(l => l.trim())
  .filter(l => l.startsWith('"'))
  .map(l => l.replace(/^"/, '').replace(/"\s*\+?\s*$/, ''))
  .join('')
  .replace(/\\"/g, '"')
  .replace(/\\'/g, "'");

const NativeRaw = {
  getInstalledAppsJson: () => JSON.stringify([
    { pkg: 'com.kugou.android', key: 'kugou', name: '酷狗音乐', icon: '🎵', type: 'music' },
    { pkg: 'com.tencent.qqmusic', key: 'qq', name: 'QQ音乐', icon: '🎶', type: 'music' },
    { pkg: 'com.autonavi.amapauto', key: 'amap', name: '高德地图(车机)', icon: '🧭', type: 'nav' },
    { pkg: 'com.baidu.BaiduMap', key: 'baidu', name: '百度地图', icon: '🗺', type: 'nav' },
  ]),
  // 全部可启动 App（含一个游戏，验证音乐源不再混入游戏）
  getAllAppsJson: () => JSON.stringify([
    { pkg: 'com.kugou.android', key: 'kugou', name: '酷狗音乐', icon: '🎵', type: 'music' },
    { pkg: 'com.tencent.qqmusic', key: 'qq', name: 'QQ音乐', icon: '🎶', type: 'music' },
    { pkg: 'com.autonavi.amapauto', key: 'amap', name: '高德地图(车机)', icon: '🧭', type: 'nav' },
    { pkg: 'com.baidu.BaiduMap', key: 'baidu', name: '百度地图', icon: '🗺', type: 'nav' },
    { pkg: 'com.tencent.tmgp.pubgmhd', key: 'com.tencent.tmgp.pubgmhd', name: '和平精英', icon: '📦', type: 'other' },
  ]),
  calls: [],
  saveWallpaper(t, b, n) { this.calls.push(['saveWallpaper', t, n, String(b).length]); },
  launchApp(k) { this.calls.push(['launchApp', k]); },
  launchMusic(k) { this.calls.push(['launchMusic', k]); },
  launchPkg(p) { this.calls.push(['launchPkg', p]); },
  goHome() { this.calls.push(['goHome']); },
  defaultHome: false,
  isDefaultHome() { return this.defaultHome === true; },
  // 真实应用图标桥（桩）：任何包名都回一个 PNG data URL，用于验证页面确实走了桥并渲染 <img class="app-ic">
  getAppIcon(pkg) { this.calls.push(['getAppIcon', pkg]); return pkg ? 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==' : ''; },
  nightMode: true,
  isNightMode() { return this.nightMode === true; },
  // v1.5.23：小部件（App Widget）桩 —— 真机实测 244 个 provider，这里取 3 个代表
  widgetListJson: () => JSON.stringify([
    { label: '万象小组件', pkg: 'com.wanxiang.widget', cls: 'com.wanxiang.widget.Main', w: 250, h: 250 },
    { label: '桌面萌宠', pkg: 'com.pet.widget', cls: 'com.pet.widget.Pet', w: 500, h: 220 },
    { label: '高德地图', pkg: 'com.autonavi.amapauto', cls: 'com.autonavi.amapauto.NavWidget', w: 400, h: 300 },
  ]),
  widgetBind(p2, c2) { this.calls.push(['widgetBind', p2, c2]); },
  widgetAuth(i, p2, c2) { this.calls.push(['widgetAuth', i, p2, c2]); },
  widgetPlace(l, t, w, h) { this.calls.push(['widgetPlace', l, t, w, h]); },
  widgetClear() { this.calls.push(['widgetClear']); },
  widgetStateJson: () => JSON.stringify({ on: false, label: '', id: -1 }),
};

const errs = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => errs.push('jsdomError: ' + e.message));
vc.on('error', e => errs.push('console.error: ' + e));

const dom = new JSDOM(fs.readFileSync(HTML, 'utf8'), {
  runScripts: 'dangerously', pretendToBeVisual: true, url: 'http://localhost/', virtualConsole: vc,
});
const { window } = dom;
window.L6NativeRaw = NativeRaw;

setTimeout(async () => {
  const d = window.document;
  window.eval(shim);

  const appListEl = d.getElementById('appList');
  const music = [...d.querySelectorAll('#setMusicSrc .set-opt')].map(b => b.textContent);
  const nav = [...d.querySelectorAll('#setNavApp .set-opt')].map(b => b.textContent);
  // 音乐源卡片：U盘/蓝牙 常驻 + 「选择应用」；导航源卡片：「选择应用」（均改为抽屉选取）
  const musicLocalOk = music.some(x => x.includes('U盘')) && music.some(x => x.includes('蓝牙'));
  const musicPick = d.getElementById('musicPickApp');
  const navPick = d.getElementById('navPickApp');
  const listOk = musicLocalOk && !!musicPick && !!navPick;

  // 导航源：点「选择应用」→ 抽屉只列 2 个导航 App（不含「系统默认」）→ 选百度地图
  const navPickOk = !!navPick;
  if (navPick) navPick.onclick();
  const navItems = appListEl ? [...appListEl.querySelectorAll('.al-item')].map(e => e.textContent) : [];
  const navOnlyInstalled = navItems.length === 2 && !navItems.some(x => x.includes('系统默认'));
  // 导航页已整块移除（不再有 #navApps / #btnLaunch）
  const navPageGone = !d.getElementById('navApps') && !d.getElementById('btnLaunch');

  // 选「百度地图」→ 点 dock 导航按钮（data-go=nav）直接拉起该 App（不再进导航页）
  const baiduItem = appListEl && [...appListEl.querySelectorAll('.al-item')].find(el => el.textContent.includes('百度地图'));
  if (baiduItem) baiduItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const dockNav = d.querySelector('[data-go="nav"]');
  if (dockNav) dockNav.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const launched = NativeRaw.calls.filter(c => c[0] === 'launchApp').pop();

  // 「布局和显示」模块已整块移除；壁纸改为列表式（#wallList + 单一「上传壁纸」入口，按文件类型自动归档）
  const layoutGone = !d.getElementById('setModules');
  const wallListOk = !!d.getElementById('wallList') && d.querySelectorAll('#wallList .wall-item').length >= 1;
  const wallUpOk = !!d.getElementById('wallUploadAny')
    && !d.getElementById('wallUploadStatic') && !d.getElementById('wallUploadDyn');
  const bar = d.getElementById('setWall') && d.getElementById('setWall').parentElement;
  const barSeq = bar ? [...bar.children].map(e => e.id || e.className) : [];
  const barOk = barSeq[0] === 'setWall' && barSeq[1] === 'wallUpAny' && barSeq.length === 2;

  // 「系统权限」合并卡片：通知使用权 + 悬浮窗权限 + 默认桌面 三项都在
  // （悬浮窗权限的手动刷新已并入卡片内的「刷新」按钮 #sysRefresh，不再单列 #ovRefresh）
  const ovOk = !!(d.getElementById('ovAcc') && d.getElementById('ovAccBtn'));
  const homeOk = !!(d.getElementById('homeState') && d.getElementById('homeBtn'));
  // 默认桌面状态读取：原生 isDefaultHome() 报 true 时必须显示「已设置」（修「设为默认桌面后仍显示未设置」）
  const homeStateBridgeOk = typeof window.L6Native.isDefaultHome === 'function';
  let homeStateSet = false, homeStateUnset = false;
  if (typeof window.readHomeState === 'function') {
    NativeRaw.defaultHome = true;  window.readHomeState();
    homeStateSet = (d.getElementById('homeState').textContent || '').indexOf('已设置') >= 0;
    NativeRaw.defaultHome = false; window.readHomeState();
    homeStateUnset = (d.getElementById('homeState').textContent || '').indexOf('未设置') >= 0;
  }
  const sysAccOk = !!(d.getElementById('sysAcc') && d.getElementById('sysAccBtn'));
  // 文案（v1.4.12）：用户已决定「老六中控就是车机默认桌面」，因此说明里**不能再出现
  // 「改回车机原桌面」的旧建议**；且两态都要交代「导航 App 发的 HOME 会被自动切回导航」。
  let homeMsgSet = '', homeMsgUnset = '';
  if (typeof window.readHomeState === 'function') {
    NativeRaw.defaultHome = true;  window.readHomeState();
    homeMsgSet = (d.getElementById('homeMsg') || {}).textContent || '';
    NativeRaw.defaultHome = false; window.readHomeState();
    homeMsgUnset = (d.getElementById('homeMsg') || {}).textContent || '';
  }
  const homeCopyOk = homeMsgSet.indexOf('切回导航') >= 0 && homeMsgSet.indexOf('改回') < 0
    && homeMsgUnset.indexOf('主屏幕应用') >= 0 && homeMsgUnset.indexOf('改回') < 0;

  // 「🔄 重新识别应用」按钮已移除（v1.4.9）→ 断言它确实不在了；「启动后自动播放」+ 默认桌面桥接
  const rescanGone = !d.getElementById('rescanApps');
  // 源卡片新布局（v1.4.11）：一行排完 ——
  //   音乐源：[U盘/蓝牙] [当前音乐源] [📂 选择应用]
  //   导航源：[当前导航源] [📂 选择应用]
  const srcRowOk = (() => {
    const rowOf = id => { const el = d.getElementById(id); return el ? el.closest('.src-row') : null; };
    const mRow = rowOf('musicSrcCur'), nRow = rowOf('navSrcCur');
    if (!mRow || !nRow) return false;
    const mKids = [...mRow.children].map(e => e.id || e.className);
    const nKids = [...nRow.children].map(e => e.id || e.className);
    return mKids.length === 3
      && mKids[0] === 'setMusicSrc' && mKids[1] === 'musicSrcCur' && mKids[2] === 'musicPickApp'
      && nKids.length === 2 && nKids[0] === 'navSrcCur' && nKids[1] === 'navPickApp';
  })();
  const autoPlayBtn = d.getElementById('autoPlayBtn');
  const autoPlayOk = !!autoPlayBtn;
  const autoPlayBefore = autoPlayBtn ? autoPlayBtn.textContent : '';
  // 「启动后自动播放」现在带当前状态位 #autoPlayState（写法对齐「上报api接口：已开启」）
  const autoPlayStateEl = d.getElementById('autoPlayState');
  const autoPlayStateBefore = autoPlayStateEl ? autoPlayStateEl.textContent : '';
  if (autoPlayBtn) autoPlayBtn.onclick();
  const autoPlayAfter = autoPlayBtn ? autoPlayBtn.textContent : '';
  const autoPlayToggle = autoPlayBefore !== autoPlayAfter;
  const autoPlayStateAfter = autoPlayStateEl ? autoPlayStateEl.textContent : '';
  const autoPlayStateDirty = autoPlayStateEl ? (autoPlayStateEl.style.color || '') : '';
  const autoPlayStateOk = !!autoPlayStateEl
    && /已开启|已关闭/.test(autoPlayStateBefore)
    && autoPlayStateBefore !== autoPlayStateAfter
    && /已开启|已关闭/.test(autoPlayStateAfter)
    && autoPlayStateDirty.indexOf('var(') >= 0;   // 状态位要有颜色区分（绿=开启 / 红=关闭）
  const homeBridgeOk = typeof window.L6Native.openHomeSettings === 'function';

  // 音乐源：点「选择应用」→ 抽屉只列 2 个音乐 App（不含游戏）→ 选酷狗 → 按播放键自动后台拉起它接管
  const musicLaunchBtnGone = !d.getElementById('musicLaunchBtn');   // 独立「启动/唤醒」按钮已移除（并入播放键）
  const launchMusicBridgeOk = typeof window.L6Native.launchMusic === 'function';
  if (musicPick) musicPick.onclick();
  const musicItems = appListEl ? [...appListEl.querySelectorAll('.al-item')].map(e => e.textContent) : [];
  const musicOnlyMusic = musicItems.length === 2 && musicItems.some(x => x.includes('酷狗音乐')) && !musicItems.some(x => x.includes('和平精英'));
  // 真实应用图标：原生 icon 字段只是 emoji → 页面改用 getAppIcon 取 PNG
  const iconBridgeOk = typeof window.L6Native.getAppIcon === 'function';
  const iconUrl = window.eval("appIconSrc('com.kugou.android')");
  const iconDataOk = typeof iconUrl === 'string' && iconUrl.indexOf('data:image/png') === 0;
  const iconSlotsOk = appListEl ? appListEl.querySelectorAll('.ai-ic[data-pkg]').length >= 2 : false;  // 抽屉每项留 data-pkg 供 fillIcons 补图
  // 当前源渲染成真实图标（renderMusicSrcCur → iconHTML → 同步取）
  window.eval("settings.musicSrc='kugou';MUSIC_APPS=[{pkg:'com.kugou.android',key:'kugou',name:'酷狗音乐',icon:'🎵',type:'music'}];renderMusicSrcCur();");
  const srcIconOk = !!d.querySelector('#musicSrcCur img.app-ic');
  // 抽屉每项都应绑定长按（用于钉入 dock 快捷方式）
  const drawerItems = appListEl ? [...appListEl.querySelectorAll('.al-item')] : [];
  const lpBound = drawerItems.length > 0 && drawerItems.every(el => el.__lp === true);
  const kugouItem = drawerItems.find(el => el.textContent.includes('酷狗音乐'));
  if (kugouItem) kugouItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));  // → settings.musicSrc='kugou'
  const playBtn = d.getElementById('play');
  if (playBtn) playBtn.onclick();                     // 播放键：无活跃会话 → 自动拉起选中的音乐 App
  const launchedMusic = NativeRaw.calls.filter(c => c[0] === 'launchMusic').pop();
  const musicLaunchCall = !!(launchedMusic && launchedMusic[1] === 'kugou');
  // 本地源（U盘/蓝牙）没有可代拉的 App → 按播放不应再触发 launchMusic
  let localNoLaunch = false;
  const usbBtn = [...d.querySelectorAll('#setMusicSrc .set-opt')].find(b => (b.dataset.v || '') === 'usb');
  if (usbBtn) {
    usbBtn.onclick();
    const before = NativeRaw.calls.filter(c => c[0] === 'launchMusic').length;
    if (playBtn) playBtn.onclick();
    localNoLaunch = NativeRaw.calls.filter(c => c[0] === 'launchMusic').length === before;
  }

  // dock 右侧应用快捷方式：设置页勾选分区已移除 → 改为「抽屉长按钉入 / dock 长按移除」+ dock 渲染 + launchPkg 桥
  if (typeof window.buildDockApps === 'function') window.buildDockApps();
  const dockSetGone = !d.getElementById('setDockApps');
  const dockAppsBox = d.getElementById('dockApps');
  const toggleOk = typeof window.toggleDockPin === 'function';
  let pinAddOk = false, pinRemoveOk = false;
  if (toggleOk) {
    window.toggleDockPin('com.kugou.android');
    pinAddOk = !!(dockAppsBox && [...dockAppsBox.querySelectorAll('.dock-app')].some(b => (b.title || '').indexOf('酷狗音乐') >= 0));
    window.toggleDockPin('com.kugou.android');
    pinRemoveOk = !(dockAppsBox && [...dockAppsBox.querySelectorAll('.dock-app')].some(b => (b.title || '').indexOf('酷狗音乐') >= 0));
  }
  // 车机主题恒深色：applyTheme 忽略系统日夜模式（白天模式也强制 dark，避免设置页显白）
  const themeBridgeOk = typeof window.L6Native.isNightMode === 'function';
  window.applyTheme(false);   // 白天模式 → 也应为 dark
  const themeLightOk = d.documentElement.getAttribute('data-theme') === 'dark';
  window.applyTheme(true);
  const themeDarkOk = d.documentElement.getAttribute('data-theme') === 'dark';
  const dockRendered = !!dockAppsBox && dockAppsBox.querySelectorAll('.dock-app').length >= 1; // 至少「打开应用列表」按钮
  const moreBtn = dockAppsBox && dockAppsBox.querySelector('.dock-app.more');
  // 「返回原桌面」：必须在「打开应用列表」左侧，点按触发原生 goHome
  const homeBtn = dockAppsBox && dockAppsBox.querySelector('.dock-app.home');
  const dockBtns = dockAppsBox ? [...dockAppsBox.querySelectorAll('.dock-app')] : [];
  const homeLeftOfMore = !!homeBtn && !!moreBtn && dockBtns.indexOf(homeBtn) < dockBtns.indexOf(moreBtn);
  const goHomeBridgeOk = typeof window.L6Native.goHome === 'function';
  if (homeBtn) homeBtn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const goHomeCalled = NativeRaw.calls.some(c => c[0] === 'goHome');
  if (moreBtn) moreBtn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const appList = d.getElementById('appList');
  const appListOpened = !!appList && appList.classList.contains('open');   // 安卓抽屉：用 open 类
  const appListItems = appList ? appList.querySelectorAll('.al-item').length : 0;
  const kugouDockItem = appList && [...appList.querySelectorAll('.al-item')].find(el => el.textContent.includes('酷狗音乐'));
  if (kugouDockItem) kugouDockItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const launchedPkg = NativeRaw.calls.filter(c => c[0] === 'launchPkg').pop();
  const launchPkgOk = !!launchedPkg && launchedPkg[1] === 'com.kugou.android';

  /* ---------- v1.5.15：dock 拖动排序 / 拖到垃圾桶取消钉住 ---------- */
  // jsdom 没有布局引擎（getBoundingClientRect 全 0）→ 给 dock 项与垃圾桶打桩出「真实布局」的矩形，
  // 几何判定本身仍走生产代码里的 getBoundingClientRect。
  const htmlSrc = fs.readFileSync(HTML, 'utf8');
  const trashSec = htmlSrc.slice(htmlSrc.indexOf('#dockTrash{'), htmlSrc.indexOf('#dockTrash{') + 760);
  // 「热区 = 应用尺寸 3 倍」：应用 68u、垃圾桶 204u（68 × 3）
  // v1.5.16 起垃圾桶是 #dockApps 的**子元素** ⇒ left:50% / bottom:100% 量的是「应用栏」的盒子
  //（应用栏宽度随应用数量变，靠 CSS 相对定位自动跟随，不能用 JS 算坐标）
  const trashCssOk = /left:50%/.test(trashSec) &&
                     /width:calc\(204 \* var\(--u\)\)/.test(trashSec) &&
                     /height:calc\(204 \* var\(--u\)\)/.test(trashSec) &&
                     /bottom:calc\(100% \+ 28 \* var\(--u\)\)/.test(trashSec) &&
                     /\.dock-app\{width:calc\(68 \* var\(--u\)\);height:calc\(68 \* var\(--u\)\)/.test(htmlSrc);
  // ★ v1.5.16：buildDockApps() 不能再用 innerHTML="" 清空 —— 那会把常驻垃圾桶一起删掉，拖动就没有落点了
  const dockBuildSec = htmlSrc.slice(htmlSrc.indexOf('function buildDockApps()'),
                                     htmlSrc.indexOf('function buildDockApps()') + 700);
  const dockBuildOk = !/box\.innerHTML\s*=/.test(dockBuildSec) &&
                      /querySelectorAll\("\.dock-app"\)/.test(dockBuildSec);

  const dragApiOk = ['bindDockDrag', 'dockDragBegin', 'dockDragMove', 'dockDragEnd',
                     'dockTrashHit', 'dockCommitOrder', 'isDockDragging']
    .every(f => typeof window[f] === 'function');
  const dockApps = () => [...dockAppsBox.querySelectorAll('.dock-app:not(.fixed)')];
  const dockOrder = () => dockApps().map(b => b.dataset.pkg).join(',');
  window.toggleDockPin('com.kugou.android');
  window.toggleDockPin('com.baidu.BaiduMap');
  window.toggleDockPin('com.tencent.qqmusic');
  window.buildDockApps();
  // ⚠️ buildDockApps() 会重画整排 → home/more 必须重画之后再取，否则拿到的是已脱离 DOM 的旧节点
  const homeBtnF = dockAppsBox.querySelector('.dock-app.home');
  const moreBtnF = dockAppsBox.querySelector('.dock-app.more');
  const fixedOk = homeBtnF.classList.contains('fixed') && moreBtnF.classList.contains('fixed');
  const boundOk = dockApps().length === 3 && dockApps().every(b => b.__lp === true && b.__dockDrag === true && typeof b.__lpFired === 'function');
  const orderBefore = dockOrder();

  // 打桩：三项横排（x=1400/1500/1600，宽 68，y=600），垃圾桶 204×204（左 410 上 98 → 中心 512,200）
  const stubRect = (el, l, t, w, h) => { el.getBoundingClientRect = () => ({ left: l, top: t, width: w, height: h, right: l + w, bottom: t + h }); };
  const restub = () => dockApps().forEach((b, i) => stubRect(b, 1400 + i * 100, 600, 68, 68));
  const trashEl = d.getElementById('dockTrash');
  // ★ v1.5.16：垃圾桶必须常驻在应用栏**内部**（外置成兄弟节点 = 退回「屏幕居中」，不跟应用数量走）
  const trashInBoxOk = !!trashEl && trashEl.parentNode === dockAppsBox;
  // 打桩按「垃圾桶在应用栏正上方 · 水平对应用栏中心」给：应用栏 x 1300..1700 → 中心 1500
  stubRect(trashEl, 1398, 388, 204, 204);                    // 204×204 → 中心 (1500, 490)
  restub();
  const hitIn = window.dockTrashHit(1500, 490) === true;
  const hitOut = window.dockTrashHit(1434, 634) === false;    // y 在垃圾桶下方（dock 行上）→ 不算命中
  const trashHitOk = hitIn && hitOut;

  const firstApp = dockApps()[0];
  const beginOk = window.dockDragBegin(firstApp, 1434, 634) === true;
  const draggingOn = window.isDockDragging() === true;
  const ghostOn = !!d.querySelector('.dock-ghost');
  const trashOn = trashEl.classList.contains('on');
  const srcDim = firstApp.classList.contains('dragging');
  restub();
  window.dockDragMove(1700, 634);                       // 拖到最右 → 应排到末尾
  const orderMoved = dockOrder();
  restub();
  const dropRes = window.dockDragEnd(1700, 634);        // 不在垃圾桶松手 → 提交新顺序
  const ghostGone = !d.querySelector('.dock-ghost');
  const trashOff = !trashEl.classList.contains('on');
  const savedOrder = (() => { try { return JSON.parse(window.localStorage.getItem('l6_settings_v1')).dockApps.list.join(','); } catch (e) { return ''; } })();
  const allDock = [...dockAppsBox.querySelectorAll('.dock-app')];
  const homeStillLast = allDock[allDock.length - 2] === homeBtnF && allDock[allDock.length - 1] === moreBtnF;
  const dragRunOk = beginOk && draggingOn && ghostOn && trashOn && srcDim &&
                    orderMoved !== orderBefore && orderMoved === savedOrder &&
                    dropRes === 'drop' && ghostGone && trashOff && homeStillLast;

  // 拖到垃圾桶松手 → 取消钉住
  restub();
  const victim = dockApps()[0];
  const victimPkg = victim.dataset.pkg;
  const beforeUnpin = dockApps().length;
  window.dockDragBegin(victim, 1434, 634);
  restub();
  window.dockDragMove(1500, 490);                       // 移进垃圾桶中心（应用栏正上方）
  const trashHot = trashEl.classList.contains('hot');
  const dropTrash = window.dockDragEnd(1500, 490);
  const afterUnpin = dockApps().length;
  const dragTrashOk = trashHot && dropTrash === 'trash' && beforeUnpin === 3 &&
                      afterUnpin === 2 && dockOrder().indexOf(victimPkg) < 0;

  // 真实长按路径：把长按阈值临时置 0（省去真等 550ms），仍是 mousedown → 计时器 → 进拖动
  window.DOCK_LP_MS = 0;
  restub();
  const lpEl = dockApps()[0];
  lpEl.dispatchEvent(new window.MouseEvent('mousedown', { bubbles: true, clientX: 1434, clientY: 634 }));
  await new Promise(r => setTimeout(r, 30));
  const lpDragOn = window.isDockDragging() === true;
  const lpFired = lpEl.__lpFired() === true;            // 长按已触发 → 随后的 click 必须被吃掉
  const swallowOk = window.dockSwallowClick() === true;
  d.dispatchEvent(new window.MouseEvent('mouseup', { bubbles: true, clientX: 1434, clientY: 634 }));
  const lpEnded = window.isDockDragging() === false;
  window.DOCK_LP_MS = 550;
  const dragLpOk = lpDragOn && lpFired && swallowOk && lpEnded;

  const dragOk = dragApiOk && trashCssOk && trashInBoxOk && dockBuildOk && fixedOk && boundOk &&
                 trashHitOk && dragRunOk && dragTrashOk && dragLpOk;


  /* ---------- v1.5.16：设置页「主页模式」+ 主页三栏排序 + 导航悬浮窗口 ---------- */
  // ⚠️ renderHomeMode() 会整排重画 ⇒ 每步都必须重新 querySelector，拿旧节点会断言到已脱离 DOM 的对象
  const hmItems = () => [...d.querySelectorAll('#homeModeList .hm-item')];
  const hmOrder = () => hmItems().map(x => x.dataset.mod).join(',');
  const hmOn = () => hmItems().filter(x => x.classList.contains('on')).map(x => x.dataset.mod).join(',');
  const modSel = () => [...d.querySelectorAll('#pageMusic > [data-mod]')];
  // ⚠️ modSel() 是 **DOM 序**（三片永远不动），视觉顺序在 inline order 上 —— 两者必须分开断言，
  //    否则「顺序变了但 DOM 没动」这种正确实现会被判失败。
  const domMods = () => modSel().map(x => x.dataset.mod).join(',');
  const modOrderMap = () => { const o = {}; modSel().forEach(x => { o[x.dataset.mod] = x.style.order; }); return o; };
  const modShown = () => modSel().filter(x => x.style.display !== 'none').map(x => x.dataset.mod).join(',');
  // ⚠️ 别用 JSON.stringify 比对象：那比的是**键的插入顺序**，顺序一变就假失败 → 逐键比
  const hmOrderIs = (want) => { const g = modOrderMap();
    return Object.keys(want).every(k => g[k] === want[k]) && Object.keys(g).length === Object.keys(want).length; };
  // on / 显示集合都是「集合」，不是序列（hmToggle 只 push）→ 比较前先排序
  const set = s2 => s2.split(',').filter(Boolean).sort().join(',');
  const readHome = () => { try { return JSON.parse(window.localStorage.getItem('l6_settings_v1')).homeMode || null; } catch (e) { return null; } };

  const hmDefaultOk = (() => { try { const c = window.hmCfg();
    return c.order.join(',') === 'state,nav,music' && set(c.on.join(',')) === 'music,state'; } catch (e) { return false; } })();
  const hmRenderOk = hmItems().length === 3 && hmOrder() === 'state,nav,music' && set(hmOn()) === 'music,state' &&
                     hmItems().every(x => x.__hmDrag === true && typeof x.__hmFired === 'function');
  // 主页三栏：DOM 里三片都在且**没被搬动**（顺序只写 inline order ⇒ 车辆状态/歌词绑定不会失效）
  const homeThreeOk = domMods() === 'state,nav,music' &&
                      hmOrderIs({ state: '0', nav: '1', music: '2' }) &&
                      set(modShown()) === 'music,state';

  // 勾选「导航」→ 三栏全显；#miniNav 与 #homeNav 互斥
  window.hmToggle('nav');
  const hmNavOnOk = set(hmOn()) === 'music,nav,state' && set(modShown()) === 'music,nav,state' &&
                    set(readHome().on.join(',')) === 'music,nav,state';
  const homeNavEl = d.getElementById('homeNav');
  const homeNavIdleOk = !!homeNavEl && homeNavEl.innerHTML.indexOf('未在导航') >= 0 &&
                        homeNavEl.innerHTML.indexOf('启动导航') >= 0 &&
                        homeNavEl.innerHTML.indexOf('悬浮窗') >= 0 &&
                        homeNavEl.innerHTML.indexOf('全局悬浮导航') >= 0 &&   // v1.5.18：空态给了画面从哪来的引导
                        homeNavEl.innerHTML.indexOf('hn-dbg') >= 0 &&        // v1.5.19：空态带导航通知诊断行
                        homeNavEl.innerHTML.indexOf('hn-bc') >= 0 &&         // v1.5.20：空态带高德广播探针行
                        homeNavEl.innerHTML.indexOf('通知监听服务') >= 0 &&      // v1.5.21：空态能看出通知服务连没连上
                        homeNavEl.innerHTML.indexOf('hn-wg') >= 0 &&           // v1.5.22：空态带小部件探测行
                        homeNavEl.innerHTML.indexOf('对齐区域') >= 0 &&       // v1.5.20：对齐引导入口
                        homeNavEl.innerHTML.indexOf('画中画') < 0;          // v1.5.17 起画中画按钮已移除
  // 真实导航数据进来 → #homeNav 显示导航中，同时顶部 #miniNav 必须让位（互斥，不重复显示）
  window.applyRealNav({ active: true, arrow: '↱', turn: '前方 300m 右转', road: '滨江大道',
                        remain: '8.6km', eta: '14min', dest: '公司', app: '高德地图' });
  const homeNavLiveOk = homeNavEl.innerHTML.indexOf('导航中') >= 0 &&
                        homeNavEl.innerHTML.indexOf('8.6km') >= 0 &&
                        homeNavEl.innerHTML.indexOf('14min') >= 0 &&
                        homeNavEl.innerHTML.indexOf('公司') >= 0;
  const miniNavYieldOk = !d.getElementById('miniNav').classList.contains('show');

  // v1.5.21：广播源（src=bcast）走另一套渲染 —— 转向词 + 下一道路名自己拼，另带来源标签与状态条
  window.applyRealNav({ active: true, src: 'bcast', arrow: '\u21B0', turn: '左转', next: '兴物线',
                        remain: '5.6公里', eta: '11分钟', seg: '1.1公里',
                        chips: '0km/h · 电子眼 451m', app: '高德地图', cruise: false });
  const hnLive = homeNavEl.innerHTML;
  const homeNavBcastOk = hnLive.indexOf('hn-src') >= 0 && hnLive.indexOf('广播') >= 0 &&
                         hnLive.indexOf('左转 · 进入 兴物线') >= 0 &&
                         hnLive.indexOf('本段剩') >= 0 && hnLive.indexOf('1.1公里') >= 0 &&
                         hnLive.indexOf('hn-chips') >= 0 && hnLive.indexOf('电子眼 451m') >= 0 &&
                         hnLive.indexOf('5.6公里') >= 0;

  // v1.5.21：两条源字段契约不同 —— 通知源的 turn 已是整句，绝不能把 n.next 再拼上去
  //（通知源的 next 里装的是距离，拼上去会变成「进入 8.6km」这种鬼话）
  window.applyRealNav({ active: true, arrow: '\u21B1', turn: '前方 300m 右转 · 进入 滨江大道',
                        next: '8.6km', remain: '8.6km', eta: '14min', app: '高德地图' });
  const navSrcSplitOk = homeNavEl.innerHTML.indexOf('前方 300m 右转 · 进入 滨江大道') >= 0 &&
                        homeNavEl.innerHTML.indexOf('进入 8.6km') < 0 &&
                        homeNavEl.innerHTML.indexOf('hn-src') < 0;

  // v1.5.21：通知服务连接状态要显示；且「导航结束」复位时不得把它一起清掉（svc 与本次导航无关）
  window.L6SysEvent({ kind: 'navbc', bc: '[3] KEY_TYPE=10001 · 导航', n: 3, svc: true });
  window.applyRealNav(null);
  const svcLineOk = homeNavEl.innerHTML.indexOf('通知监听服务：已连接') >= 0 &&
                    !!window.REAL && window.REAL.nav.svc === true;
  window.L6SysEvent({ kind: 'navbc', bc: '', n: 0, svc: false });   // 收尾，免得影响后面

  window.applyRealNav(null);

  /* ---------- v1.5.23：小部件（App Widget）承载 ----------
     只读探测已确认这条路可走（host OK / 244 provider / 直接绑定被拒 / 授权界面有），
     本组断言守的是「承载链路」本身：入口 → 列表 → 选中带对 pkg → 拉授权 → 占位框 → 矩形上报 → 移除。 */
  const wgCalls = (f) => NativeRaw.calls.filter(c => c[0] === f);
  // ① 空态入口
  const wgBtnOk = homeNavEl.innerHTML.indexOf('data-act="wgpick"') >= 0 &&
                  homeNavEl.innerHTML.indexOf('选择小部件') >= 0;
  // ② 面板：provider 由我们自己的列表渲染（不用系统 PICK —— 它返回的 id 和我们的 host 对不上）
  const wgSheetEl = d.getElementById('wgSheet'), wgScrimEl = d.getElementById('wgScrim');
  window.wgOpen();
  const wgRowsEl = d.getElementById('wgRows');
  const wgOpenOk = !!wgSheetEl && !!wgScrimEl && !!d.getElementById('wgSearch') && !!wgRowsEl &&
                   wgSheetEl.classList.contains('open') && wgScrimEl.classList.contains('open') &&
                   wgRowsEl.querySelectorAll('.wg-row').length === 3;
  // ③ 搜索过滤后点第一行，必须取到「筛出来的那一项」——★ 下标若按原数组算就会嵌错卡片
  window.wgRender('高德');
  const wgFilteredRows = d.getElementById('wgRows').querySelectorAll('.wg-row');
  const wgFilterOk = wgFilteredRows.length === 1 &&
                     d.getElementById('wgRows').innerHTML.indexOf('高德地图') >= 0;
  wgFilteredRows[0].onclick();
  const wgBindCall = wgCalls('widgetBind').pop();
  const wgPickOk = !!wgBindCall && wgBindCall[1] === 'com.autonavi.amapauto' &&
                   wgBindCall[2] === 'com.autonavi.amapauto.NavWidget' && window.wgOn() === false;
  // ④ 直接绑定被拒 ⇒ 必须回拉系统授权框，且 id / provider 都要带上（少了 provider 系统框拉不起来）
  window.L6SysEvent({ kind: 'widget', on: false, needAuth: true, id: 42, label: '高德地图' });
  const wgAuthCall = wgCalls('widgetAuth').pop();
  const wgAuthOk = !!wgAuthCall && wgAuthCall[1] === 42 &&
                   wgAuthCall[2] === 'com.autonavi.amapauto' &&
                   wgAuthCall[3] === 'com.autonavi.amapauto.NavWidget';
  // ⑤ 授权通过 ⇒ 区域出现占位框（原生 overlay 就盖在它上面）+「已嵌入」行，面板自动收起
  window.L6SysEvent({ kind: 'widget', on: true, label: '高德地图' });
  const wgBoxEl = d.getElementById('hnWgBox');
  const wgLiveOk = window.wgOn() === true && !!wgBoxEl &&
                   homeNavEl.innerHTML.indexOf('hn-wgbox') >= 0 &&
                   homeNavEl.innerHTML.indexOf('已嵌入') >= 0 &&
                   !d.getElementById('wgSheet').classList.contains('open');
  // ⑥ 矩形上报：按**百分比**（页面是 --u 等比缩放，报像素必漂），且同一矩形不重复跨桥
  const iw = window.innerWidth, ih = window.innerHeight;
  stubRect(wgBoxEl, iw * 0.25, ih * 0.2, iw * 0.5, ih * 0.4);
  window.REAL.wgKey = '';
  window.reportWidgetRect();
  const placeCall = wgCalls('widgetPlace').pop();
  const wgRectOk = !!placeCall && Math.abs(placeCall[1] - 0.25) < 0.005 &&
                   Math.abs(placeCall[2] - 0.2) < 0.005 &&
                   Math.abs(placeCall[3] - 0.5) < 0.005 && Math.abs(placeCall[4] - 0.4) < 0.005;
  const beforeDup = wgCalls('widgetPlace').length;
  window.reportWidgetRect();                                  // 同一矩形再来一次
  const wgDedupeOk = wgCalls('widgetPlace').length === beforeDup;
  // ⑦ 区域不可见（宽高 0）⇒ 必须上报 0，原生据此收起 overlay（否则小部件会飘在别的页面上）
  stubRect(wgBoxEl, 0, 0, 0, 0);
  window.REAL.wgKey = '';
  window.reportWidgetRect();
  const placeZero = wgCalls('widgetPlace').pop();
  const wgHideOk = !!placeZero && placeZero[1] === 0 && placeZero[3] === 0;
  // ⑧ 移除 ⇒ 走 widgetClear，并退回「选择小部件」入口
  window.wgClear();
  const wgClearCalled = wgCalls('widgetClear').length > 0;
  window.L6SysEvent({ kind: 'widget', on: false });
  const wgClearOk = wgClearCalled && window.wgOn() === false &&
                    homeNavEl.innerHTML.indexOf('选择小部件') >= 0 &&
                    homeNavEl.innerHTML.indexOf('hn-wgbox') < 0;
  const wgOk = wgBtnOk && wgOpenOk && wgFilterOk && wgPickOk && wgAuthOk && wgLiveOk &&
               wgRectOk && wgDedupeOk && wgHideOk && wgClearOk;

  // 拖动排序：#pageMusic 顺序要跟着「设置页三项的顺序」变
  const hmStub = (el, l, t, w, h) => { el.getBoundingClientRect = () => ({ left: l, top: t, width: w, height: h, right: l + w, bottom: t + h }); };
  const hmRestub = () => hmItems().forEach((x, i) => hmStub(x, 200 + i * 200, 300, 180, 44));
  hmRestub();
  const hmFirst = hmItems()[0];
  const hmBeginOk = window.hmDragBegin(hmFirst, 290, 322) === true;
  const hmGhostOn = !!d.querySelector('.hm-ghost');
  const hmDim = hmFirst.classList.contains('dragging');
  hmRestub();
  window.hmDragMove(810, 322);                       // 拖到最右 → 应排到末尾
  const hmOrderMoved = hmOrder();
  hmRestub();
  const hmDrop = window.hmDragEnd();
  const hmGhostGone = !d.querySelector('.hm-ghost');
  const hmSaved = readHome();
  const hmDragOk = hmBeginOk && hmGhostOn && hmDim && hmOrderMoved === 'nav,music,state' &&
                   hmDrop === 'drop' && hmGhostGone && !!hmSaved &&
                   hmSaved.order.join(',') === 'nav,music,state' &&
                   domMods() === 'state,nav,music' &&        // DOM 仍没动（只改了 order）
                   hmOrderIs({ nav: '0', music: '1', state: '2' });

  // 三项全部取消 → 自动回默认「状态 + 音乐」
  window.hmToggle('state'); window.hmToggle('music'); window.hmToggle('nav');
  const hmAllOffOk = set(hmOn()) === 'music,state' && set(modShown()) === 'music,state' &&
                     set(readHome().on.join(',')) === 'music,state';

  // v1.5.17 取消画中画：源码级确认设置页开关已删、页面与 Java 都不再提 PiP
  const pipGoneOk = !d.getElementById('pipBtn') &&
                    !/pipEnabled|syncPip|navEnterPip/.test(htmlSrc) &&
                    !/画中画|enterPip|isPipAvailable/.test(htmlSrc) &&
                    !/enterPip|isPipAvailable|PictureInPicture/.test(java) &&
                    [].slice.call(d.querySelectorAll('.set-label')).length > 0;

  // 三栏等宽（v1.5.17 源码级 —— jsdom 不解析外部样式表，getComputedStyle 拿不到 flex/grid）
  // ★ flex 只均分内容盒，三栏 padding 不同 ⇒ 外框不等宽；必须用 grid 的 minmax(0,1fr)
  const equalThreeOk = /#pageMusic\{display:grid;grid-auto-flow:column;grid-auto-columns:minmax\(0,1fr\)\}/.test(htmlSrc) &&
                       !/\.music\{flex:2;/.test(htmlSrc) &&
                       !/#pageMusic > \[data-mod="nav"\]\{flex:1\}/.test(htmlSrc);

  const homeModeOk = hmDefaultOk && hmRenderOk && homeThreeOk && hmNavOnOk && homeNavIdleOk &&
                     homeNavLiveOk && homeNavBcastOk && navSrcSplitOk && svcLineOk &&
                     miniNavYieldOk && hmDragOk && hmAllOffOk &&
                     pipGoneOk && equalThreeOk;

  /* ---------- v1.5.24：设置页翻页错位 / 点按焦点框 / 小部件入口 ---------- */
  // ① 翻页位移必须用**像素**。translateY(-N*100%) 的百分比相对「#track 自身高度」，
  //    高度 ≠ 一屏时就**静默错位**（不报错）—— 主页 idx=0 位移为 0 看不出来，
  //    设置页正好是 idx=1 ⇒ 一翻就呈现黑屏（车机实测就是这个现象）。
  const goPixelSrcOk = htmlSrc.indexOf("track.style.transform='translateY(-'+trackOffset(idx)+'px)'") >= 0 &&
                       htmlSrc.indexOf('translateY(-${idx*100}%)') < 0 &&
                       typeof window.trackOffset === 'function' && typeof window.applyTrackOffset === 'function';
  // jsdom 无布局引擎 ⇒ 自己给 offsetHeight / clientHeight 打桩，验行为而不是验数字来源
  const pageEls = [...d.querySelectorAll('.page')];
  const stubH = (el, h) => Object.defineProperty(el, 'offsetHeight', { configurable: true, get: () => h });
  pageEls.forEach(p => stubH(p, 720));
  window.go(1);
  const goPixelOk = d.getElementById('track').style.transform === 'translateY(-720px)';
  // ★ 零值兜底：布局还没量到（全 0）时必须退到 idx × 一屏高 —— 原地不动就等于黑屏
  pageEls.forEach(p => stubH(p, 0));
  Object.defineProperty(d.getElementById('viewport'), 'clientHeight', { configurable: true, get: () => 500 });
  window.go(1);
  const goFallbackOk = d.getElementById('track').style.transform === 'translateY(-500px)';
  // ★ 回主页必须归零，否则「设置页 → 主页」会停在一片空白上
  window.go(0);
  const goHomeZeroOk = d.getElementById('track').style.transform === 'translateY(-0px)';
  const goPixelBehaviorOk = goPixelOk && goFallbackOk && goHomeZeroOk;

  // ② 车机那代 WebView 没有 :focus-visible 的「只在键盘操作时才画焦点环」语义 ⇒
  //    手指点按也让 <button> 进 :focus 并画默认描边，圆按钮上就套出一个方形焦点框
  const focusResetOk = /\*:focus\{outline:none\}/.test(htmlSrc);

  // ③ 小部件入口必须在**设置页也有一份** —— 主页那份住在 #homeNav 内部，
  //    而「导航」默认不显示 ⇒ 默认配置下主页根本看不到入口（v1.5.23 的可用性疏漏）
  const setWg = d.getElementById('setWidget');
  const setWgPick = d.getElementById('wgSetPick'), setWgState = d.getElementById('wgSetState');
  const setWgClearRow = d.getElementById('wgSetClearRow');
  const setWgEntryOk = !!setWg && !!setWgPick && !!setWgState && !!setWgClearRow &&
                       setWg.parentNode === d.querySelector('#pageSet .set-scroll') &&
                       setWg.innerHTML.indexOf('选择小部件') >= 0 &&
                       setWg.innerHTML.indexOf('原生卡片') >= 0;      // 明确「不是网页内嵌」
  // 按钮真的接上了（漏绑定 = 点了没反应，而且不报错 —— 必须点一次验）
  const pickBound = typeof setWgPick.onclick === 'function';
  if (pickBound) setWgPick.onclick();
  const setPickBoundOk = pickBound && d.getElementById('wgSheet').classList.contains('open');
  window.wgClose();
  // 文案跟着状态走：未嵌入 → 已嵌入 → 未嵌入（移除行随状态显隐）
  const setWgStateOffOk = setWgState.textContent === '未嵌入' && setWgClearRow.style.display === 'none';
  window.L6SysEvent({ kind: 'widget', on: true, label: '高德地图' });
  const setWgStateOnOk = setWgState.textContent.indexOf('已嵌入') >= 0 &&
                         setWgClearRow.style.display === '';
  window.L6SysEvent({ kind: 'widget', on: false });
  const setWgStateBackOk = setWgState.textContent === '未嵌入' && setWgClearRow.style.display === 'none';
  const setWgOk = setWgEntryOk && setPickBoundOk && setWgStateOffOk && setWgStateOnOk && setWgStateBackOk;

  const widgetEntryOk = goPixelSrcOk && goPixelBehaviorOk && focusResetOk && setWgOk;

  // 空态自诊断：必须能把「桥读不到应用」与「车机真没装应用」区分开。
  // 历史教训（v1.4.6~v1.4.14）：原生 getAllAppsJson 漏了 @JavascriptInterface，
  // 桥调用在 JS 侧抛错、被 SHIM 的 catch 静默转成空数组，页面只显示「未读到已安装的应用」，
  // 与「真的没装」长得一模一样 → 定位花了 8 个版本。此断言守住这条可观测性。
  const realGetAllApps = window.L6Native.getAllApps;
  window.L6Native.getAllApps = function (cb) { cb([]); };
  window.__l6Err = ['getAllApps: TypeError: R.getAllAppsJson is not a function'];
  if (typeof window.openAppList === 'function') window.openAppList();
  const emptyEl = d.querySelector('#appListBody .al-empty');
  const diagEl = d.querySelector('#appListBody .al-diag');
  const emptyDiagOk = !!(emptyEl && diagEl && /getAllApps/.test(diagEl.textContent));
  window.L6Native.getAllApps = realGetAllApps;
  window.__l6Err = undefined;
  if (typeof window.closeAppList === 'function') window.closeAppList();

  const ok = listOk && launched && launched[1] === 'baidu'
    && typeof window.L6Native.saveWallpaper === 'function' && errs.length === 0
    && navOnlyInstalled && navPageGone && layoutGone && wallListOk && wallUpOk
    && barOk && ovOk && homeOk && sysAccOk && rescanGone && srcRowOk && autoPlayOk
    && autoPlayToggle && autoPlayStateOk && homeBridgeOk && musicLaunchBtnGone && launchMusicBridgeOk && musicLaunchCall && musicOnlyMusic && localNoLaunch
    && iconBridgeOk && iconDataOk && iconSlotsOk && srcIconOk
    && dockSetGone && toggleOk && pinAddOk && pinRemoveOk && lpBound
    && dockRendered && appListOpened && appListItems >= 4 && launchPkgOk && emptyDiagOk
    && homeLeftOfMore && goHomeBridgeOk && goHomeCalled && dragOk && homeModeOk
    && homeStateBridgeOk && homeStateSet && homeStateUnset && homeCopyOk && wgOk && widgetEntryOk
    && themeBridgeOk && themeLightOk && themeDarkOk;

  console.log('音乐源卡片 ->', music.join(' / '));
  console.log('导航源卡片 ->', nav.join(' / '));
  console.log('音乐源卡片(U盘/蓝牙+选择应用) ->', musicLocalOk, '| 导航源选择按钮 ->', navPickOk);
  console.log('音乐源抽屉只列音乐(不含游戏) ->', musicOnlyMusic);
  console.log('导航源只列已装 ->', navOnlyInstalled);
  console.log('导航页已移除 ->', navPageGone);
  console.log('布局/显示模块已移除 ->', layoutGone);
  console.log('壁纸列表 + 单一上传入口(自动归档) ->', wallListOk, '/', wallUpOk);
  console.log('上传入口位置(类型切换右侧) ->', barOk, '(' + barSeq.join(' | ') + ')');
  console.log('系统权限卡: 通知/悬浮窗/默认桌面 ->', sysAccOk, '/', ovOk, '/', homeOk);
  console.log('重新识别应用按钮已移除 ->', rescanGone);
  console.log('源卡片一行布局(U盘/蓝牙·当前源·选择应用) ->', srcRowOk);
  console.log('真实图标: 桥 ->', iconBridgeOk, '| 取到 PNG ->', iconDataOk, '| 抽屉 data-pkg 占位 ->', iconSlotsOk, '| 当前源显图标 ->', srcIconOk);
  console.log('启动后自动播放按钮 ->', autoPlayOk, '(', autoPlayBefore, '→', autoPlayAfter, ')');
  console.log('默认桌面桥接 openHomeSettings ->', homeBridgeOk);
  console.log('默认桌面状态读取 isDefaultHome ->', homeStateBridgeOk, '| 已设置显示 ->', homeStateSet, '| 未设置显示 ->', homeStateUnset);
  console.log('默认桌面文案(不再劝退/含「切回导航」) ->', homeCopyOk, '| 已设置态 ->', (homeMsgSet || '').slice(0, 28) + '…');
  console.log('启动/唤醒按钮已移除 ->', musicLaunchBtnGone, '| 桥 launchMusic ->', launchMusicBridgeOk, '| 播放键拉起 kugou ->', musicLaunchCall, '| 本地源不代拉 ->', localNoLaunch);
  console.log('dock 设置分区已移除 ->', dockSetGone, '| 抽屉长按已绑定 ->', lpBound, '| 长按钉入 ->', pinAddOk, '| 长按移除 ->', pinRemoveOk);
  console.log('车机主题恒深色：桥 isNightMode ->', themeBridgeOk, '| 白天模式 ->', themeLightOk, '(应 true) | 夜间模式 ->', themeDarkOk);
  console.log('dock 渲染(含打开应用列表) ->', dockRendered);
  console.log('应用列表抽屉打开 ->', appListOpened, '| 项 ->', appListItems);
  console.log('空态自诊断(读不到 vs 真没装) ->', emptyDiagOk);
  console.log('dock 点应用 → launchPkg ->', launchPkgOk, '(' + (launchedPkg ? launchedPkg[1] : '') + ')');
  console.log('返回原桌面按钮(在打开应用列表左侧) ->', homeLeftOfMore, '| 桥 goHome ->', goHomeBridgeOk, '| 点按触发 ->', goHomeCalled);
  console.log('dock 拖动：长按已绑定/固定位/fixed ->', dragApiOk, '/', boundOk, '/', fixedOk, '| 垃圾桶 CSS(3倍·应用栏居中·正上方) ->', trashCssOk);
  console.log('dock 垃圾桶：常驻应用栏内 ->', trashInBoxOk, '| buildDockApps 不清空 ->', dockBuildOk);
  console.log('dock 拖动：进拖动态/ghost/垃圾桶/原位淡影 ->', beginOk, '/', ghostOn, '/', trashOn, '/', srcDim);
  console.log('dock 拖动：换位 ' + orderBefore + '  →  ' + orderMoved, '| 已落盘 ->', orderMoved === savedOrder, '| home/more 仍在末尾 ->', homeStillLast);
  console.log('dock 垃圾桶命中判定(内/外) ->', hitIn, '/', hitOut, '| hot 高亮 ->', trashHot, '| 松手取消钉住 ->', dropTrash, '(' + beforeUnpin + '→' + afterUnpin + ')');
  console.log('dock 长按真实路径(阈值置0) ->', lpDragOn, '| 吃掉后续 click ->', lpFired && swallowOk, '| 松手收尾 ->', lpEnded);
  console.log('dock 导航按钮直接启动 ->', launched ? launched[1] : '(未触发)');
  console.log('主页模式：默认(状态+音乐) ->', hmDefaultOk, '| 设置页三项渲染 ->', hmRenderOk, '| 主页三栏按 order ->', homeThreeOk);
  console.log('主页模式：勾选导航后三栏全显 ->', hmNavOnOk, '| 空态文案 ->', homeNavIdleOk,
              '| 通知源填充 ->', homeNavLiveOk, '| 广播源填充 ->', homeNavBcastOk,
              '| 两源字段不串 ->', navSrcSplitOk, '| 通知服务状态 ->', svcLineOk);
  console.log('主页模式：顶部迷你卡让位(#miniNav 互斥) ->', miniNavYieldOk, '| 拖动排序落盘 ->', hmDragOk, '(' + hmOrderMoved + ')');
  console.log('主页模式：三项全取消回默认 ->', hmAllOffOk, '| 画中画已移除 ->', pipGoneOk, '| 三栏等宽 ->', equalThreeOk);
  console.log('小部件承载：入口 ->', wgBtnOk, '| 面板/列表 ->', wgOpenOk, '| 搜索筛选 ->', wgFilterOk,
              '| 选中带对 pkg ->', wgPickOk, '| 拉授权框 ->', wgAuthOk);
  console.log('小部件承载：占位框 ->', wgLiveOk, '| 矩形按百分比上报 ->', wgRectOk,
              '| 同矩形去重 ->', wgDedupeOk, '| 不可见上报 0 ->', wgHideOk, '| 移除 ->', wgClearOk);
  console.log('翻页位移改像素：源码 ->', goPixelSrcOk, '| 实测高度累加 ->', goPixelOk,
              '| 零值兜底 ->', goFallbackOk, '| 回主页归零 ->', goHomeZeroOk);
  console.log('点按焦点方框已消除(*:focus{outline:none}) ->', focusResetOk);
  console.log('设置页小部件入口 ->', setWgEntryOk, '| 按钮已绑定 ->', setPickBoundOk,
              '| 状态文案同步 ->', setWgStateOffOk, '/', setWgStateOnOk, '/', setWgStateBackOk);
  console.log('saveWallpaper 通道 ->', typeof window.L6Native.saveWallpaper === 'function' ? '可用' : '不可用');
  console.log('运行时错误 =', errs.length, errs.join(' | '));
  console.log(ok ? '✓ 桥接冒烟测试通过' : '✗ 桥接冒烟测试失败');
  process.exit(ok ? 0 : 2);
}, 900);
