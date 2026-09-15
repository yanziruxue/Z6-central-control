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
  // 导航页 #navApps 同步动态渲染（不再是硬编码的 amap/baidu/sys 三项）
  const navPageItems = [...d.querySelectorAll('#navApps .nav-app')].map(e => e.dataset.app);
  const navPageOk = navPageItems.length === 2 && !navPageItems.includes('sys');

  const baidu = [...d.querySelectorAll('#setNavApp .set-opt')].find(b => b.textContent.includes('百度地图'));
  if (baidu) baidu.onclick();
  d.getElementById('btnLaunch').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const launched = NativeRaw.calls.filter(c => c[0] === 'launchApp').pop();

  // 「布局和显示」模块已整块移除；壁纸改为列表式（#wallList + 两个独立上传按钮）
  const layoutGone = !d.getElementById('setModules');
  const wallListOk = !!d.getElementById('wallList') && d.querySelectorAll('#wallList .wall-item').length >= 1;
  const wallUpOk = !!d.getElementById('wallUploadStatic') && !!d.getElementById('wallUploadDyn');
  // 上传按钮必须紧跟在「动态壁纸」右侧（同一行 .wall-bar 内，且顺序为 静态/动态/上传静态/上传动态）
  const bar = d.getElementById('setWall') && d.getElementById('setWall').parentElement;
  const barSeq = bar ? [...bar.children].map(e => e.id || e.className) : [];
  const barOk = barSeq[0] === 'setWall' && barSeq[1] === 'wallUpStatic' && barSeq[2] === 'wallUpDyn';

  // 悬浮窗权限卡片（导航返回主页按钮的前提）
  const ovOk = !!(d.getElementById('ovAcc') && d.getElementById('ovAccBtn') && d.getElementById('ovRefresh'));

  // 导航页：点击列表项即直接拉起 App（不再需要先选再点「启动导航」）
  NativeRaw.calls.length = 0;
  const navItem = [...d.querySelectorAll('#navApps .nav-app')].find(e => e.dataset.app === 'amap');
  if (navItem) navItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const clickLaunched = NativeRaw.calls.filter(c => c[0] === 'launchApp').pop();
  const clickLaunchOk = !!clickLaunched && clickLaunched[1] === 'amap';

  const ok = listOk && launched && launched[1] === 'baidu'
    && typeof window.L6Native.saveWallpaper === 'function' && errs.length === 0
    && navOnlyInstalled && navPageOk && layoutGone && wallListOk && wallUpOk
    && barOk && ovOk && clickLaunchOk;

  console.log('原生音乐源 ->', music.join(' / '));
  console.log('原生导航项 ->', nav.join(' / '));
  console.log('导航源只列已装 ->', navOnlyInstalled);
  console.log('导航页动态项 ->', navPageItems.join(' / '), '(无 sys =', !navPageItems.includes('sys'), ')');
  console.log('布局/显示模块已移除 ->', layoutGone);
  console.log('壁纸列表 + 双上传按钮 ->', wallListOk, '/', wallUpOk);
  console.log('上传按钮位置(动态右侧) ->', barOk, '(' + barSeq.join(' | ') + ')');
  console.log('悬浮窗权限卡片 ->', ovOk);
  console.log('点列表项直接启动 ->', clickLaunchOk, '(' + (clickLaunched ? clickLaunched[1] : '未触发') + ')');
  console.log('启动导航 key ->', launched ? launched[1] : '(未触发)');
  console.log('saveWallpaper 通道 ->', typeof window.L6Native.saveWallpaper === 'function' ? '可用' : '不可用');
  console.log('运行时错误 =', errs.length, errs.join(' | '));
  console.log(ok ? '✓ 桥接冒烟测试通过' : '✗ 桥接冒烟测试失败');
  process.exit(ok ? 0 : 2);
}, 900);
