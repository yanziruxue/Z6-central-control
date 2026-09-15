/*
 * sys-smoke.js —— 「音乐/导航真实数据」页面侧回归
 *
 * 用 jsdom 加载原型页面，注入一个假的 L6Native 桥（记录调用），
 * 再模拟原生侧推来的 window.L6SysEvent 事件，断言页面确实被真实数据接管。
 *
 *   node tools/sys-smoke.js        （需要在有 jsdom 的 NODE_PATH 下运行，见 build 说明）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const HTML = path.resolve(__dirname, '..', '..', 'apk-dashboard-prototype.html');
const html = fs.readFileSync(HTML, 'utf8');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra !== undefined ? ('  → ' + extra) : '')); }
};

const errs = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => errs.push('jsdomError: ' + e.message));
vc.on('error', e => errs.push('console.error: ' + e));

const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  url: 'http://localhost/',
  virtualConsole: vc,
});

const w = dom.window, d = w.document;
const $ = s => d.querySelector(s);
const txt = s => { const e = $(s); return e ? e.textContent.trim() : '<missing ' + s + '>'; };

const calls = [];
const DELAY = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  await DELAY(300);

  console.log('\n== 结构 & 初始状态 ==');
  ok('新卡片元素齐全', !!( $('#sysAcc') && $('#sysAccBtn') && $('#sysRefresh') && $('#sysProbe') && $('#sysMsg') ));
  ok('无桥接时提示「未接入」（浏览器预览态）', txt('#sysAcc') === '未接入', txt('#sysAcc'));
  ok('页面无运行时错误', errs.length === 0, errs.join(' | '));

  console.log('\n== 注入假原生桥 ==');
  w.L6Native = {
    getSysState: () => ({ notifyAccess: true, media: {} }),
    hasNotifyAccess: () => true,
    openNotifyAccess: () => calls.push(['openNotifyAccess']),
    refreshSys: () => calls.push(['refreshSys']),
    mediaControl: a => calls.push(['mediaControl', a]),
    requestLyrics: (t, a) => calls.push(['requestLyrics', t, a]),
  };
  w.L6SysEvent({ kind: 'access', granted: true });
  ok('授权状态回填为「已授权」', txt('#sysAcc') === '已授权', txt('#sysAcc'));
  ok('授权提示文案更新', txt('#sysMsg').includes('真实数据'), txt('#sysMsg'));

  console.log('\n== 媒体真实数据接管音乐页 ==');
  w.L6SysEvent({
    kind: 'media', active: true, title: '稻香', artist: '周杰伦', album: '魔杰座',
    pkg: 'com.tencent.qqmusic', app: 'QQ音乐', playing: true,
    pos: 65000, dur: 223000, art: 'data:image/jpeg;base64,/9j/AAAA',
  });
  ok('歌名来自系统媒体会话', txt('#npTitle') === '稻香', txt('#npTitle'));
  ok('歌手来自系统媒体会话', txt('#npArtist') === '周杰伦', txt('#npArtist'));
  ok('来源显示为真实 App', txt('#npSrc') === 'QQ音乐', txt('#npSrc'));
  ok('封面换成专辑图', $('#mbCover').innerHTML.includes('<img'), $('#mbCover').innerHTML);
  ok('播放按钮显示「暂停」', txt('#play') === '⏸', txt('#play'));
  ok('总时长格式化 3:43', txt('#tDur') === '3:43', txt('#tDur'));
  ok('当前进度格式化 1:05', txt('#tCur') === '1:05', txt('#tCur'));
  const w0 = $('#seek i').style.width;
  ok('进度条按真实进度 29.1%', w0.indexOf('29.1') === 0, w0);
  const rl = calls.filter(c => c[0] === 'requestLyrics').pop();
  ok('换歌触发歌词拉取（带歌名+歌手）', !!rl && rl[1] === '稻香' && rl[2] === '周杰伦', JSON.stringify(rl));

  console.log('\n== 真实歌词跟随进度高亮 ==');
  const LINES = [0, 60000, 70000, 80000, 90000, 100000, 110000];
  w.L6SysEvent({
    kind: 'lyrics', title: '稻香', artist: '周杰伦', source: '网易云音乐',
    lines: LINES.map((t, i) => ({ t, s: '歌词' + String.fromCharCode(65 + i) })),
  });
  const ps = [...d.querySelectorAll('#lyr p')];
  ok('歌词区固定渲染 6 行', ps.length === 6, ps.length);
  const on = ps.filter(p => p.classList.contains('on'));
  ok('仅一行高亮', on.length === 1, on.length);
  ok('高亮行 = 进度 65s 对应的那句（B）', on.length === 1 && on[0].textContent === '歌词B',
    on.length ? on[0].textContent : '-');
  ok('不足 6 行用占位行撑住行数', ps.filter(p => p.classList.contains('ph')).length === 1,
    ps.filter(p => p.classList.contains('ph')).length);

  console.log('\n== 传输控制转给系统播放器 ==');
  $('#play').onclick();
  $('#prev').onclick();
  $('#next').onclick();
  $('#seek').getBoundingClientRect = () => ({ left: 0, width: 100 });
  $('#seek').onclick({ clientX: 50 });
  const ctl = calls.filter(c => c[0] === 'mediaControl').map(c => c[1]);
  ok('播放/暂停 → toggle', ctl.includes('toggle'), JSON.stringify(ctl));
  ok('上一曲 → prev', ctl.includes('prev'), JSON.stringify(ctl));
  ok('下一曲 → next', ctl.includes('next'), JSON.stringify(ctl));
  ok('进度条点击 → seek 到对应毫秒（50% ≈ 111500ms）', ctl.includes('seek:111500'), JSON.stringify(ctl));

  console.log('\n== 导航真实数据接管导航页 ==');
  w.L6SysEvent({
    kind: 'nav', active: true, app: '高德地图 · 车机版', arrow: '↱',
    turn: '前方 300米 右转', road: '滨江大道', next: '300 m', remain: '5.6 km',
    eta: '12 分钟', clock: '14:25', dest: '公司',
  });
  ok('转向箭头来自导航通知', txt('#mArr') === '↱', txt('#mArr'));
  ok('迷你卡片剩余距离', txt('#mDist') === '5.6 km', txt('#mDist'));
  ok('迷你卡片预计用时', txt('#mEta') === '12 分钟', txt('#mEta'));
  ok('转向 + 道路名', txt('#mTurn').includes('滨江大道'), txt('#mTurn'));
  ok('目的地', txt('#mDest') === '公司', txt('#mDest'));
  // 假地图全屏层已删除：真实导航数据改落到迷你卡片与导航状态行（外部 App 全屏时本页不可见）
  ok('导航 App 名显示在导航状态行', txt('#navState').includes('高德地图'), txt('#navState'));
  ok('假地图节点已全部移除',
    !d.querySelector('#navOverlay') && !d.querySelector('#extBadge') && !d.querySelector('#extEta'),
    'navOverlay/extBadge/extEta 均已不存在');

  // 演示用模拟导航是 1.5s 一跳，等 1.8s 看它有没有被真实数据压住
  await DELAY(1800);
  ok('演示导航不会覆盖真实导航数据', txt('#mDist') === '5.6 km', txt('#mDist'));

  console.log('\n== 断开真实数据 → 回落演示 ==');
  w.L6SysEvent({ kind: 'media', active: false });
  ok('封面回到占位图标', txt('#mbCover') === '🎵', txt('#mbCover'));
  ok('歌名回到演示曲目', txt('#npTitle') === '夜空中最亮的星', txt('#npTitle'));
  w.L6SysEvent({ kind: 'nav', active: false });
  await DELAY(1700);
  ok('导航回落后演示数据恢复滚动', txt('#mDist') !== '5.6 km', txt('#mDist'));

  console.log('\n== 当前接入自检 ==');
  $('#sysRefresh').onclick();
  ok('刷新调用原生 refreshSys', calls.some(c => c[0] === 'refreshSys'));
  await DELAY(1000);
  $('#sysProbe').onclick();
  const probe = txt('#sysMsg');
  ok('自检输出含媒体/导航/权限三项', probe.includes('媒体：') && probe.includes('导航：') && probe.includes('通知使用权'),
    probe);
  $('#sysAccBtn').onclick();
  ok('授权按钮调原生打开通知使用权设置', calls.some(c => c[0] === 'openNotifyAccess'));

  console.log('\n== 运行时错误 ==');
  ok('全程 0 运行时错误', errs.length === 0, errs.join(' | '));

  console.log('\n结果: 通过 ' + pass + ' / 失败 ' + fail + '\n');
  process.exit(fail > 0 ? 1 : 0);
})();
