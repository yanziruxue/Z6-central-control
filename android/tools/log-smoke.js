/*
 * 日志「立即上报」按钮冒烟测试（不依赖设备，用 jsdom 跑）。
 *
 * 覆盖 v1.4.20 的改动：
 *  ① 日志卡片出现 #logNowBtn
 *  ② 点击 → 真的调用原生 uploadLog 桥
 *  ③ 防连点：一次上报未结束时重复点击无效
 *  ④ 进行中态：按钮 disabled + 文案「上报中…」+ 状态行「正在上报…」
 *  ⑤ 原生回推结果后恢复：状态行显示成功/失败，按钮复位
 *  ⑥ 原生长时间不回推 → 15s 超时兜底提示（测试里把常量调小加速）
 *  ⑦ 前端不因为「上报api接口」开关关闭而拦截（force 在原生侧 uploadNow(true)）
 *  ⑧ v1.5.7「测试接口」探针按钮：调用桥 / 进行中态 / 防连点 / 成功与失败结果渲染
 *
 * 用法： node tools/log-smoke.js
 * 依赖： jsdom（node 环境变量 NODE_PATH 指向已装 jsdom 的 node_modules）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'MainActivity.java');
const HTML = path.resolve(ROOT, '..', 'apk-dashboard-prototype.html');

// 从 Java 源码里抽出真实 SHIM（保证测的就是打进 APK 的那串）
const java = fs.readFileSync(JAVA, 'utf8');
const m = java.match(/private static final String SHIM =([\s\S]*?);\n/);
if (!m) { console.error('✗ 未能从 MainActivity.java 解析 SHIM'); process.exit(1); }
const shim = m[1].split('\n').map(l => l.trim()).filter(l => l.startsWith('"'))
  .map(l => l.replace(/^"/, '').replace(/"\s*\+?\s*$/, '')).join('')
  .replace(/\\"/g, '"').replace(/\\'/g, "'")

const NativeRaw = {
  calls: [],
  apiUpload: true,
  getInstalledAppsJson: () => JSON.stringify([]),
  getAllAppsJson: () => JSON.stringify([]),
  getAppIcon: () => '',
  getLogJson: () => '[]',
  getLogPath: () => '/Download/L6/logs',
  uploadLog() { this.calls.push(['uploadLog']); },
  testLogApi() { this.calls.push(['testLogApi']); },
  setApiUpload(b) { this.apiUpload = !!b; this.calls.push(['setApiUpload', !!b]); },
  isApiUpload() { return this.apiUpload; },
  setLogBroadcast() {}, clearLog() {}, exportLog() {},
  getUploadStatus: () => '{}',
  launchApp() {}, launchPkg() {}, launchMusic() {}, goHome() {},
  checkOtaUpdate() {}, installOtaUpdate() {}, getOtaConfig: () => '{}',
  getSysState: () => '{}', refreshSys() {}, mediaControl() {}, requestLyrics() {},
  hasNotifyAccess: () => false, openNotifyAccess() {},
  hasOverlayPermission: () => false, openOverlaySettings() {}, openHomeSettings() {},
  isDefaultHome: () => false, isNightMode: () => true,
  saveWallpaper() {},
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

const R = [];
const check = (n, pass, detail) => R.push([n, !!pass, detail || '']);

setTimeout(() => {
  window.L6NativeRaw = NativeRaw;
  window.eval(shim);

  const btn = d.getElementById('logNowBtn');
  const msg = d.getElementById('logUpMsg');
  const click = el => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const uploads = () => NativeRaw.calls.filter(c => c[0] === 'uploadLog').length;
  const txt = el => (el ? (el.textContent || '') : '');

  check('① #logNowBtn 存在', !!btn);
  check('① 按钮文案为「立即上报」', txt(btn).indexOf('立即上报') >= 0, txt(btn));
  check('① 开关按钮 #logApiBtn 仍在', !!d.getElementById('logApiBtn'));

  // ② 点击 → 调用桥
  NativeRaw.calls.length = 0;
  click(btn);
  check('② 点击调用原生 uploadLog', uploads() === 1, `调用 ${uploads()} 次`);

  // ④ 进行中态
  check('④ 进行中：按钮 disabled', btn.disabled === true);
  check('④ 进行中：按钮文案变「上报中…」', txt(btn).indexOf('上报中') >= 0, txt(btn));
  check('④ 进行中：状态行显示「正在上报…」', txt(msg).indexOf('正在上报') >= 0, txt(msg));

  // ③ 防连点
  click(btn); click(btn);
  check('③ 防连点：重复点击不重复调用', uploads() === 1, `调用 ${uploads()} 次`);

  // ⑤ 结果回推 → 恢复
  window.L6LogUploadStatus({ ok: true, msg: 'HTTP 200', ts: Date.now() });
  const okShown = txt(msg).indexOf('上报成功') >= 0;
  check('⑤ 成功回推：状态行显示上报成功', okShown, txt(msg));
  check('⑤ 成功回推：按钮复位', btn.disabled === false && txt(btn).indexOf('立即上报') >= 0, txt(btn));

  // 失败回推也要恢复
  NativeRaw.calls.length = 0;
  click(btn);
  window.L6LogUploadStatus({ ok: false, msg: 'HTTP 403', ts: Date.now() });
  check('⑤ 失败回推：状态行显示上报失败并复位按钮',
    txt(msg).indexOf('上报失败') >= 0 && btn.disabled === false, txt(msg));

  // ⑦ 开关关闭态：前端不应拦截（force 由原生 uploadNow(true) 保证）
  NativeRaw.apiUpload = false;
  window.eval('syncLogApi()');
  NativeRaw.calls.length = 0;
  click(btn);
  check('⑦ 开关关闭态仍能手动上报（前端不拦）', uploads() === 1, `调用 ${uploads()} 次`);
  window.L6LogUploadStatus({ ok: true, msg: 'forced', ts: Date.now() });   // 复位
  NativeRaw.apiUpload = true;

  // ⑧ v1.5.7「测试接口」探针：上传失败只回一句「Connection reset」看不出断在哪一段，
  //    这个按钮必须把 DNS / TCP / 请求三段摊开，并标出失败阶段。
  const tbtn = d.getElementById('logTestBtn');
  check('⑧ #logTestBtn 存在且紧挨「立即上报」', !!tbtn && tbtn.previousElementSibling === btn);
  check('⑧ 按钮文案含「测试接口」', txt(tbtn).indexOf('测试接口') >= 0, txt(tbtn));
  const tests = () => NativeRaw.calls.filter(c => c[0] === 'testLogApi').length;
  NativeRaw.calls.length = 0;
  click(tbtn);
  check('⑧ 点击调用原生 testLogApi', tests() === 1, `调用 ${tests()} 次`);
  check('⑧ 测试中：按钮 disabled + 文案「测试中…」',
    tbtn.disabled === true && txt(tbtn).indexOf('测试中') >= 0, txt(tbtn));
  check('⑧ 测试中：状态行提示三段进度(DNS/TCP)',
    txt(msg).indexOf('DNS') >= 0 && txt(msg).indexOf('TCP') >= 0, txt(msg));
  click(tbtn); click(tbtn);
  check('⑧ 防连点：重复点击不重复调用', tests() === 1, `调用 ${tests()} 次`);

  // 失败探针（典型：读响应阶段被 reset）→ 必须把阶段与异常原样摊开
  window.L6LogProbeResult({ ok: false, url: 'https://x/api/l6zk/log', host: 'x', dns: '1.2.3.4',
    family: 'IPv4', dnsMs: 12, tcpMs: 45, code: -1, phase: 'read',
    err: 'SocketException: Connection reset', ms: 3021, resp: '' });
  check('⑧ 失败探针：状态行标出失败阶段 + 异常原文',
    txt(msg).indexOf('read') >= 0 && txt(msg).indexOf('Connection reset') >= 0, txt(msg));
  check('⑧ 失败探针：显示解析到的 IP 与 IP 族',
    txt(msg).indexOf('1.2.3.4') >= 0 && txt(msg).indexOf('IPv4') >= 0, txt(msg));
  check('⑧ 失败探针：按钮复位', tbtn.disabled === false && txt(tbtn).indexOf('测试接口') >= 0, txt(tbtn));

  // 成功探针 → 三段耗时都要出现
  window.L6LogProbeResult({ ok: true, url: 'https://x/api/l6zk/log', host: 'x', dns: '1.2.3.4',
    family: 'IPv4', dnsMs: 12, tcpMs: 45, code: 200, phase: '', err: '', ms: 218, resp: '{"ok":true}' });
  check('⑧ 成功探针：状态行含 DNS/TCP/HTTP 三段',
    txt(msg).indexOf('DNS 12ms') >= 0 && txt(msg).indexOf('TCP 45ms') >= 0
      && txt(msg).indexOf('HTTP 200') >= 0, txt(msg));

  // ⑥ 超时兜底：把常量调小，避免测试真等 15s
  window.eval('LOG_NOW_TIMEOUT=150');
  NativeRaw.calls.length = 0;
  click(btn);
  const duringUpload = txt(msg).indexOf('正在上报') >= 0;
  setTimeout(() => {
    check('⑥ 超时前处于「正在上报…」', duringUpload);
    check('⑥ 超时后给出「未收到上报结果」提示', txt(msg).indexOf('未收到上报结果') >= 0, txt(msg));
    check('⑥ 超时后按钮复位', btn.disabled === false && txt(btn).indexOf('立即上报') >= 0, txt(btn));

    const pass = R.filter(r => r[1]).length;
    R.forEach(([n, ok2, det]) => console.log(`${ok2 ? '✓' : '✗'} ${n}${det ? '  [' + det + ']' : ''}`));
    const noise = errs.filter(e => !/HTMLMediaElement|getContext|Not implemented/.test(e));
    console.log(`\n通过 ${pass}/${R.length}；运行时错误 ${noise.length}`);
    noise.slice(0, 6).forEach(e => console.log('  ! ' + e));
    window.close();
    process.exit(pass === R.length && noise.length === 0 ? 0 : 1);
  }, 320);
}, 300);
