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

setTimeout(() => {
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
  if (autoPlayBtn) autoPlayBtn.onclick();
  const autoPlayAfter = autoPlayBtn ? autoPlayBtn.textContent : '';
  const autoPlayToggle = autoPlayBefore !== autoPlayAfter;
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
  // 日夜主题：跟随系统（isNightMode 桥 → applyTheme 切 html[data-theme]）
  const themeBridgeOk = typeof window.L6Native.isNightMode === 'function';
  window.applyTheme(false);
  const themeLightOk = d.documentElement.getAttribute('data-theme') === 'light';
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
    && autoPlayToggle && homeBridgeOk && musicLaunchBtnGone && launchMusicBridgeOk && musicLaunchCall && musicOnlyMusic && localNoLaunch
    && iconBridgeOk && iconDataOk && iconSlotsOk && srcIconOk
    && dockSetGone && toggleOk && pinAddOk && pinRemoveOk && lpBound
    && dockRendered && appListOpened && appListItems >= 4 && launchPkgOk && emptyDiagOk
    && homeLeftOfMore && goHomeBridgeOk && goHomeCalled
    && homeStateBridgeOk && homeStateSet && homeStateUnset && homeCopyOk
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
  console.log('日夜主题：桥 isNightMode ->', themeBridgeOk, '| 浅色 ->', themeLightOk, '| 深色 ->', themeDarkOk);
  console.log('dock 渲染(含打开应用列表) ->', dockRendered);
  console.log('应用列表抽屉打开 ->', appListOpened, '| 项 ->', appListItems);
  console.log('空态自诊断(读不到 vs 真没装) ->', emptyDiagOk);
  console.log('dock 点应用 → launchPkg ->', launchPkgOk, '(' + (launchedPkg ? launchedPkg[1] : '') + ')');
  console.log('返回原桌面按钮(在打开应用列表左侧) ->', homeLeftOfMore, '| 桥 goHome ->', goHomeBridgeOk, '| 点按触发 ->', goHomeCalled);
  console.log('dock 导航按钮直接启动 ->', launched ? launched[1] : '(未触发)');
  console.log('saveWallpaper 通道 ->', typeof window.L6Native.saveWallpaper === 'function' ? '可用' : '不可用');
  console.log('运行时错误 =', errs.length, errs.join(' | '));
  console.log(ok ? '✓ 桥接冒烟测试通过' : '✗ 桥接冒烟测试失败');
  process.exit(ok ? 0 : 2);
}, 900);
