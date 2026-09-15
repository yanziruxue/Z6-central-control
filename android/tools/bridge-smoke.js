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

  // 「布局和显示」模块已整块移除；壁纸改为列表式（#wallList + 两个独立上传按钮）
  const layoutGone = !d.getElementById('setModules');
  const wallListOk = !!d.getElementById('wallList') && d.querySelectorAll('#wallList .wall-item').length >= 1;
  const wallUpOk = !!d.getElementById('wallUploadStatic') && !!d.getElementById('wallUploadDyn');
  const bar = d.getElementById('setWall') && d.getElementById('setWall').parentElement;
  const barSeq = bar ? [...bar.children].map(e => e.id || e.className) : [];
  const barOk = barSeq[0] === 'setWall' && barSeq[1] === 'wallUpStatic' && barSeq[2] === 'wallUpDyn';

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

  // 「重新识别应用」按钮 + 「启动后自动播放」按钮 + 默认桌面桥接
  const rescanOk = !!d.getElementById('rescanApps');
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

  const ok = listOk && launched && launched[1] === 'baidu'
    && typeof window.L6Native.saveWallpaper === 'function' && errs.length === 0
    && navOnlyInstalled && navPageGone && layoutGone && wallListOk && wallUpOk
    && barOk && ovOk && homeOk && sysAccOk && rescanOk && autoPlayOk
    && autoPlayToggle && homeBridgeOk && musicLaunchBtnGone && launchMusicBridgeOk && musicLaunchCall && musicOnlyMusic && localNoLaunch
    && dockSetGone && toggleOk && pinAddOk && pinRemoveOk && lpBound
    && dockRendered && appListOpened && appListItems >= 4 && launchPkgOk
    && homeLeftOfMore && goHomeBridgeOk && goHomeCalled
    && homeStateBridgeOk && homeStateSet && homeStateUnset
    && themeBridgeOk && themeLightOk && themeDarkOk;

  console.log('音乐源卡片 ->', music.join(' / '));
  console.log('导航源卡片 ->', nav.join(' / '));
  console.log('音乐源卡片(U盘/蓝牙+选择应用) ->', musicLocalOk, '| 导航源选择按钮 ->', navPickOk);
  console.log('音乐源抽屉只列音乐(不含游戏) ->', musicOnlyMusic);
  console.log('导航源只列已装 ->', navOnlyInstalled);
  console.log('导航页已移除 ->', navPageGone);
  console.log('布局/显示模块已移除 ->', layoutGone);
  console.log('壁纸列表 + 双上传按钮 ->', wallListOk, '/', wallUpOk);
  console.log('上传按钮位置(动态右侧) ->', barOk, '(' + barSeq.join(' | ') + ')');
  console.log('系统权限卡: 通知/悬浮窗/默认桌面 ->', sysAccOk, '/', ovOk, '/', homeOk);
  console.log('重新识别应用按钮 ->', rescanOk);
  console.log('启动后自动播放按钮 ->', autoPlayOk, '(', autoPlayBefore, '→', autoPlayAfter, ')');
  console.log('默认桌面桥接 openHomeSettings ->', homeBridgeOk);
  console.log('默认桌面状态读取 isDefaultHome ->', homeStateBridgeOk, '| 已设置显示 ->', homeStateSet, '| 未设置显示 ->', homeStateUnset);
  console.log('启动/唤醒按钮已移除 ->', musicLaunchBtnGone, '| 桥 launchMusic ->', launchMusicBridgeOk, '| 播放键拉起 kugou ->', musicLaunchCall, '| 本地源不代拉 ->', localNoLaunch);
  console.log('dock 设置分区已移除 ->', dockSetGone, '| 抽屉长按已绑定 ->', lpBound, '| 长按钉入 ->', pinAddOk, '| 长按移除 ->', pinRemoveOk);
  console.log('日夜主题：桥 isNightMode ->', themeBridgeOk, '| 浅色 ->', themeLightOk, '| 深色 ->', themeDarkOk);
  console.log('dock 渲染(含打开应用列表) ->', dockRendered);
  console.log('应用列表抽屉打开 ->', appListOpened, '| 项 ->', appListItems);
  console.log('dock 点应用 → launchPkg ->', launchPkgOk, '(' + (launchedPkg ? launchedPkg[1] : '') + ')');
  console.log('返回原桌面按钮(在打开应用列表左侧) ->', homeLeftOfMore, '| 桥 goHome ->', goHomeBridgeOk, '| 点按触发 ->', goHomeCalled);
  console.log('dock 导航按钮直接启动 ->', launched ? launched[1] : '(未触发)');
  console.log('saveWallpaper 通道 ->', typeof window.L6Native.saveWallpaper === 'function' ? '可用' : '不可用');
  console.log('运行时错误 =', errs.length, errs.join(' | '));
  console.log(ok ? '✓ 桥接冒烟测试通过' : '✗ 桥接冒烟测试失败');
  process.exit(ok ? 0 : 2);
}, 900);
