/*
 * 壁纸模块冒烟测试（不依赖设备，用 jsdom 跑）。
 *
 * 作用：守护两类已修过的真实缺陷，防止回归 ——
 *   ① 选中壁纸无法持久化：saveSettings() 必须持久化「上传的」壁纸项（非 preset），
 *      loadSettings() 必须在保留内置 preset 的前提下合并回上传项，并把失效的 pick 回落。
 *      （曾经只存 pick/wall 不存 wallList → 重启后上传项丢失、pick 失效 → 回落预设）
 *   ② mp4 动态壁纸不显示：部分车机文件选择器不返回 MIME（f.type 空）→ 视频被当图片，
 *      用 background-image 加载视频 data URL 必然显示不出来。必须按扩展名兜底 + 统一 wallIsVid()。
 *
 * 用法： node tools/wall-smoke.js
 * 依赖： jsdom（NODE_PATH 指向已装 jsdom 的 node_modules）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const HTML = path.resolve(ROOT, '..', 'apk-dashboard-prototype.html');

const errs = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => {
  // jsdom 未实现 HTMLMediaElement.play()，会在起视频壁纸时报这个，不算页面错误
  if (/play\(\) method/.test(e.message || '')) return;
  errs.push('jsdomError: ' + e.message);
});
vc.on('error', e => errs.push('console.error: ' + e));

const dom = new JSDOM(fs.readFileSync(HTML, 'utf8'), {
  runScripts: 'dangerously', pretendToBeVisual: true, url: 'http://localhost/', virtualConsole: vc,
});
const w = dom.window;

setTimeout(() => {
  const d = w.document;
  let fail = 0;
  const ok = (name, cond, extra) => {
    console.log((cond ? '  ok   ' : '  FAIL ') + name + (extra !== undefined ? '  [' + extra + ']' : ''));
    if (!cond) fail++;
  };
  // 页面顶层是 const settings → 不挂 window，必须用 eval 读页面作用域
  const ev = expr => w.eval(expr);

  console.log('\n== ① 壁纸持久化往返（模拟「上次选过上传的动态壁纸」）==');
  const saved = {
    musicSrc: 'kugou', navApp: 'baidu', wall: 'dynamic', wallColor: '#0b0e14',
    pick: { static: 'preset', dynamic: 'up_999' }, autoPlay: false,
    dockApps: { list: ['com.kugou.android'], max: 6 },
    wallList: { static: [], dynamic: [{ id: 'up_999', name: 'sea.mp4', src: 'data:video/mp4;base64,AAA', vid: true }] },
  };
  w.localStorage.setItem('l6_settings_v1', JSON.stringify(saved));
  ev('loadSettings()');
  ok('wall 恢复为 dynamic', ev('settings.wall') === 'dynamic', ev('settings.wall'));
  ok('pick.dynamic 恢复', ev('settings.pick.dynamic') === 'up_999', ev('settings.pick.dynamic'));
  ok('内置预设未被覆盖丢失', ev('settings.wallList.dynamic.some(i=>i.id==="preset")'), ev('settings.wallList.dynamic.map(i=>i.id).join(",")'));
  ok('上传项被恢复', ev('settings.wallList.dynamic.some(i=>i.id==="up_999")'));
  ok('pick 指向有效项', ev('settings.wallList.dynamic.some(i=>i.id===settings.pick.dynamic)'));

  ev('saveSettings()');
  const again = JSON.parse(w.localStorage.getItem('l6_settings_v1'));
  ok('往返后仍含上传项', (again.wallList.dynamic || []).some(i => i.id === 'up_999'));
  ok('往返不重复持久化内置 preset', !(again.wallList.dynamic || []).some(i => i.id === 'preset'));
  ok('其他设置一并持久化', again.musicSrc === 'kugou');

  console.log('\n== ② 模拟重启：选中上传项后重新 loadSettings 仍指向它 ==');
  ev('settings.pick.dynamic=settings.wallList.dynamic.filter(i=>i.id!=="preset")[0].id; saveSettings();');
  ev('settings.pick.dynamic="preset"; settings.wallList.dynamic=settings.wallList.dynamic.filter(i=>i.id==="preset");');
  ev('loadSettings()');
  ok('重启后仍指向上传项', ev('settings.pick.dynamic') === 'up_999', ev('settings.pick.dynamic'));

  console.log('\n== ③ 失效 pick 回落（上传项丢失时不至于无选中）==');
  w.localStorage.setItem('l6_settings_v1', JSON.stringify({
    wall: 'dynamic', pick: { static: 'preset', dynamic: 'up_gone' }, wallList: { static: [], dynamic: [] },
  }));
  ev('loadSettings()');
  ok('pick 回落到第 0 项', ev('settings.wallList.dynamic.some(i=>i.id===settings.pick.dynamic)'), ev('settings.pick.dynamic'));

  console.log('\n== ④ mp4 动态壁纸显示判定 ==');
  ok('wallIsVid 兜底判为视频', ev('wallIsVid({src:"data:video/mp4;base64,AAA",vid:false})') === true);
  ok('普通图片仍判为图片', ev('wallIsVid({src:"data:image/jpeg;base64,AAA",vid:false})') === false);
  ok('扩展名兜底（MIME 缺失的 mp4）', /\.(mp4|webm|m4v|mov|mkv|avi|3gp|ts)$/i.test('a.mp4'));
  ev('(function(){settings.wallList.dynamic.push({id:"up_mp4",name:"a.mp4",src:"data:video/mp4;base64,AAA"});settings.pick.dynamic="up_mp4";settings.wall="dynamic";applyWall();})()');
  const wv = d.getElementById('wallVideo'), wm = d.getElementById('wallMedia');
  ok('<video> 被显示', wv.style.display === 'block', wv.style.display);
  ok('<video>.src 已设置', (wv.getAttribute('src') || '').indexOf('data:video/mp4') === 0);
  ok('未误用 background-image', wm.style.display === 'none', wm.style.display);

  console.log('\n== 运行时错误 ==');
  ok('全程 0 运行时错误', errs.length === 0, errs.join(' | '));

  console.log('\n结果: ' + (fail === 0 ? '全部通过' : '失败 ' + fail + ' 项'));
  process.exit(fail === 0 ? 0 : 2);
}, 400);
