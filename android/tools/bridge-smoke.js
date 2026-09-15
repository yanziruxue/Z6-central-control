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
  calls: [],
  saveWallpaper(t, b, n) { this.calls.push(['saveWallpaper', t, n, String(b).length]); },
  launchApp(k) { this.calls.push(['launchApp', k]); },
  launchMusic(k) { this.calls.push(['launchMusic', k]); },
  launchPkg(p) { this.calls.push(['launchPkg', p]); },
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

  const music = [...d.querySelectorAll('#setMusicSrc .set-opt')].map(b => b.textContent);
  const nav = [...d.querySelectorAll('#setNavApp .set-opt')].map(b => b.textContent);
  const listOk = music.some(x => x.includes('酷狗音乐')) && nav.some(x => x.includes('百度地图'));

  // 导航源只列「车机实际已安装」的导航 App：桩里 2 个 nav → 页面 2 项，且不含「系统默认」
  const navOnlyInstalled = nav.length === 2 && !nav.some(x => x.includes('系统默认'));
  // 导航页已整块移除（不再有 #navApps / #btnLaunch）
  const navPageGone = !d.getElementById('navApps') && !d.getElementById('btnLaunch');

  // 选中「百度地图」→ 点 dock 导航按钮（data-go=nav）直接拉起该 App（不再进导航页）
  const baidu = [...d.querySelectorAll('#setNavApp .set-opt')].find(b => b.textContent.includes('百度地图'));
  if (baidu) baidu.onclick();
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

  // 音乐源「启动/唤醒」按钮 + launchMusic 桥：选中真实音乐 App → 点按钮 → 触发 launchMusic(key)，不挂返回按钮
  const musicLaunchBtn = d.getElementById('musicLaunchBtn');
  const musicLaunchBtnOk = !!musicLaunchBtn;
  const launchMusicBridgeOk = typeof window.L6Native.launchMusic === 'function';
  const kugou = [...d.querySelectorAll('#setMusicSrc .set-opt')].find(b => b.textContent.includes('酷狗音乐'));
  if (kugou) kugou.onclick();           // 选中酷狗音乐 → settings.musicSrc='kugou'
  if (musicLaunchBtn) musicLaunchBtn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const launchedMusic = NativeRaw.calls.filter(c => c[0] === 'launchMusic').pop();
  const musicLaunchCall = launchedMusic && launchedMusic[1] === 'kugou';

  // dock 右侧应用快捷方式：设置页钉入 + dock 渲染 + 应用列表抽屉 + launchPkg 桥
  if (typeof window.buildDockAppsSetting === 'function') window.buildDockAppsSetting();
  if (typeof window.buildDockApps === 'function') window.buildDockApps();
  const dockSetWrap = d.getElementById('setDockApps');
  const dockSetOk = !!dockSetWrap && dockSetWrap.querySelectorAll('.set-opt').length >= 4; // 桩返回 4 个 App
  const dockAppsBox = d.getElementById('dockApps');
  const dockRendered = !!dockAppsBox && dockAppsBox.querySelectorAll('.dock-app').length >= 1; // 至少「打开应用列表」按钮
  const moreBtn = dockAppsBox && dockAppsBox.querySelector('.dock-app.more');
  if (moreBtn) moreBtn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const appList = d.getElementById('appList');
  const appListOpened = !!appList && appList.hidden === false;
  const appListItems = appList ? appList.querySelectorAll('.al-item').length : 0;
  const kugouItem = appList && [...appList.querySelectorAll('.al-item')].find(el => el.textContent.includes('酷狗音乐'));
  if (kugouItem) kugouItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const launchedPkg = NativeRaw.calls.filter(c => c[0] === 'launchPkg').pop();
  const launchPkgOk = !!launchedPkg && launchedPkg[1] === 'com.kugou.android';

  const ok = listOk && launched && launched[1] === 'baidu'
    && typeof window.L6Native.saveWallpaper === 'function' && errs.length === 0
    && navOnlyInstalled && navPageGone && layoutGone && wallListOk && wallUpOk
    && barOk && ovOk && homeOk && sysAccOk && rescanOk && autoPlayOk
    && autoPlayToggle && homeBridgeOk && musicLaunchBtnOk && launchMusicBridgeOk && musicLaunchCall
    && dockSetOk && dockRendered && appListOpened && appListItems >= 4 && launchPkgOk;

  console.log('原生音乐源 ->', music.join(' / '));
  console.log('原生导航项 ->', nav.join(' / '));
  console.log('导航源只列已装 ->', navOnlyInstalled);
  console.log('导航页已移除 ->', navPageGone);
  console.log('布局/显示模块已移除 ->', layoutGone);
  console.log('壁纸列表 + 双上传按钮 ->', wallListOk, '/', wallUpOk);
  console.log('上传按钮位置(动态右侧) ->', barOk, '(' + barSeq.join(' | ') + ')');
  console.log('系统权限卡: 通知/悬浮窗/默认桌面 ->', sysAccOk, '/', ovOk, '/', homeOk);
  console.log('重新识别应用按钮 ->', rescanOk);
  console.log('启动后自动播放按钮 ->', autoPlayOk, '(', autoPlayBefore, '→', autoPlayAfter, ')');
  console.log('默认桌面桥接 openHomeSettings ->', homeBridgeOk);
  console.log('音乐启动/唤醒按钮 ->', musicLaunchBtnOk, '| 桥 launchMusic ->', launchMusicBridgeOk, '| 点按触发 kugou ->', musicLaunchCall);
  console.log('dock 设置分区(已装App) ->', dockSetOk, '(' + (dockSetWrap ? dockSetWrap.querySelectorAll('.set-opt').length : 0) + ' 项)');
  console.log('dock 渲染(含打开应用列表) ->', dockRendered);
  console.log('应用列表抽屉打开 ->', appListOpened, '| 项 ->', appListItems);
  console.log('dock 点应用 → launchPkg ->', launchPkgOk, '(' + (launchedPkg ? launchedPkg[1] : '') + ')');
  console.log('dock 导航按钮直接启动 ->', launched ? launched[1] : '(未触发)');
  console.log('saveWallpaper 通道 ->', typeof window.L6Native.saveWallpaper === 'function' ? '可用' : '不可用');
  console.log('运行时错误 =', errs.length, errs.join(' | '));
  console.log(ok ? '✓ 桥接冒烟测试通过' : '✗ 桥接冒烟测试失败');
  process.exit(ok ? 0 : 2);
}, 900);
