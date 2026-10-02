/*
 * 设置页「车机信号采集」冒烟测试（不依赖设备，用 jsdom 跑）。
 *
 * 覆盖：
 *  ⑭ v1.5.14 / v1.5.15「车机信号采集」：开始/结束两个桥 + 能力清单与变化的渲染 +
 *     轮询参数、忽略清单、落盘目录、Base64 回推等源码契约
 *  ⑮ v1.5.15 移除校验：整套「运行日志 API 上报」已删干净 ——
 *     Java 实现 / 4 个只服务于上报的死代码类 / 10 个桥 / 页面卡片与全部相关 JS / 死 CSS
 *     （负向断言，防止哪天又从别处「长回来」）
 *
 * 用法： node tools/log-smoke.js
 * 依赖： jsdom（node 环境变量 NODE_PATH 指向已装 jsdom 的 node_modules）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src', 'com', 'l6', 'carmedia');
const JAVA = path.join(SRC, 'MainActivity.java');
const LOGJ = path.join(SRC, 'L6Log.java');
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
  getInstalledAppsJson: () => JSON.stringify([]),
  getAllAppsJson: () => JSON.stringify([]),
  getAppIcon: () => '',
  getLogJson: () => '[]',
  getLogPath: () => '/Download/L6',
  startSignalCapture() { this.calls.push(['startSignalCapture']); },
  stopSignalCapture() { this.calls.push(['stopSignalCapture']); },
  isSignalCapturing() { return false; },
  setLogBroadcast() {}, clearLog() {}, exportLog() {},
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

  const click = el => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const txt = el => (el ? (el.textContent || '') : '');
  const lg = fs.readFileSync(LOGJ, 'utf8');
  const mainSrc = fs.readFileSync(JAVA, 'utf8');
  const htmlSrc = fs.readFileSync(HTML, 'utf8');
  const manifest = fs.readFileSync(path.join(ROOT, 'AndroidManifest.xml'), 'utf8');

  /* ============ ⑭ 车机信号采集（v1.5.14 / v1.5.15）============
     UI/行为部分在 jsdom 里能真跑（点按钮 → 看是否调桥、推事件 → 看渲染）；
     轮询间隔/上限/忽略清单/落盘目录这些 jsdom 测不到的硬事实走源码断言。 */
  const SC = path.join(SRC, 'SignalCapture.java');
  const SP = path.join(SRC, 'SysProbe.java');
  const SD = path.join(SRC, 'SigDiff.java');
  const sc = fs.readFileSync(SC, 'utf8');
  const sp = fs.readFileSync(SP, 'utf8');
  const sd = fs.readFileSync(SD, 'utf8');

  const sbtn = d.getElementById('sigBtn');
  const sout = d.getElementById('sigOut');
  const smsg = d.getElementById('sigMsg');
  const sigStarts = () => NativeRaw.calls.filter(c => c[0] === 'startSignalCapture').length;

  check('⑭ 采集按钮 / 输出区 / 状态行都在', !!sbtn && !!sout && !!smsg);
  check('⑭ 只有一个动作按钮（开始/结束切换），不再有车门/车窗等标记按钮',
    !!sbtn && d.querySelectorAll('#sigBtn').length === 1 && d.querySelectorAll('.sig-mark').length === 0,
    `#sigBtn=${d.querySelectorAll('#sigBtn').length} .sig-mark=${d.querySelectorAll('.sig-mark').length}`);

  NativeRaw.calls.length = 0;
  click(sbtn);
  check('⑭ 点「开始采集」真的调原生 startSignalCapture', sigStarts() === 1, `调用 ${sigStarts()} 次`);
  check('⑭ 采集中：按钮变「结束采集」', txt(sbtn).indexOf('结束采集') >= 0, txt(sbtn));
  check('⑭ 采集中：输出区已展开', sout.hidden === false);

  window.L6SignalResult({ kind: 'cap', cap: {
    logcat: { ok: false, err: 'permission denied' },
    dumpsys: { ok: false, err: 'Permission Denial' },
    car: { class: false, automotiveFeature: false },
    propCount: 412, vehicleKeyCount: 2,
    vehicleKeys: ['prop:persist.sys.door.fl', 'prop:persist.sys.gear'] } });
  let stext = txt(sout);
  check('⑭ 能力清单：logcat 标为「不可读」（读不到要如实说，别装作采集了）',
    stext.indexOf('logcat 不可读') >= 0, stext.split('\n')[0]);
  check('⑭ 能力清单：列出「疑车辆键」（没变化也能看出能挖什么）',
    stext.indexOf('persist.sys.door.fl') >= 0 && stext.indexOf('persist.sys.gear') >= 0);

  window.L6SignalResult({ kind: 'change', t: 12340, src: 'prop',
    key: 'persist.sys.door.fl', from: '0', to: '1' });
  stext = txt(sout);
  check('⑭ 变化渲染：相对时间 + 来源 | 键: 旧 → 新',
    stext.indexOf('[12.3s]') >= 0 && stext.indexOf('prop | persist.sys.door.fl: 0 → 1') >= 0,
    stext.split('\n').pop());
  window.L6SignalResult({ kind: 'change', t: 12500, src: 'audio', key: 'vol.music', from: '', to: '12/15' });
  check('⑭ 空旧值渲染成「(无)」而不是空白',
    txt(sout).indexOf('vol.music: (无) → 12/15') >= 0, txt(sout).split('\n').pop());

  window.L6SignalResult({ kind: 'beat', t: 20000, rounds: 20, changes: 3 });
  check('⑭ 心跳行显示累计变化（不再有「标记 N 个」）',
    txt(d.getElementById('sigState')).indexOf('变化 3 条') >= 0
      && txt(d.getElementById('sigState')).indexOf('标记') < 0,
    txt(d.getElementById('sigState')));

  window.L6SignalResult({ kind: 'done', ms: 83000, changes: 17, logcatLines: 0,
    file: '/Download/L6/signal-20261002-213000.json' });
  check('⑭ 结束回推：按钮复位为「开始采集」', txt(sbtn).indexOf('开始采集') >= 0, txt(sbtn));
  check('⑭ 结束回推：汇总含变化条数 + JSON 路径 + 提示发日志',
    txt(smsg).indexOf('17 条') >= 0 && txt(smsg).indexOf('signal-') >= 0
      && txt(smsg).indexOf('L6Signal') >= 0, txt(smsg));
  NativeRaw.calls.length = 0;
  click(sbtn);
  check('⑭ 结束后再点 → 走「开始」分支（能反复采）', sigStarts() === 1, `调用 ${sigStarts()} 次`);
  window.L6SignalResult({ kind: 'done', ms: 800, changes: 0, logcatLines: 0, file: '' });

  // —— 源码级硬事实 ——
  check('⑭ 轮询间隔 = 1s', /INTERVAL_MS = 1000;/.test(sc));
  check('⑭ 单次采集上限 = 300s（防用户忘了点结束）', /MAX_MS = 300_000L;/.test(sc));
  check('⑭ 每 10s 一条心跳（证明采集确实活着、没被系统掐掉）', /BEAT_MS = 10_000L;/.test(sc));
  check('⑭ 忽略清单挡掉最吵的键（否则车门信号被内存/进度刷屏淹没）',
    /s\.add\("pwr:uptimeMs"\)/.test(sc) && /s\.add\("media:pos"\)/.test(sc)
      && /s\.add\("mem:availKB"\)/.test(sc));
  check('⑭ 落盘目录复用 logFile() 的父目录（否则日志与 JSON 会分家，人工收集必漏）',
    /public static File artifactDir\(\)/.test(lg) && /L6Log\.artifactDir\(\)/.test(sc)
      && /File f = logFile\(\)/.test(lg));
  check('⑭ 结果回推走 Base64（中文/引号直接拼 evaluateJavascript 会静默炸掉）',
    /jsCall\("L6SignalResult"/.test(mainSrc)
      && /public static String jsCall\(String fn, String json\)/.test(lg)
      && /window\.L6B64/.test(lg));
  check('⑭ 采集器逐块 try/catch（一组抛异常不能拖垮整份快照）',
    /private static void group\(TreeMap<String, String> m, Runnable r\)/.test(sp)
      && (sp.match(/group\(m, new Runnable\(\)/g) || []).length >= 12,
    'group(...) ' + ((sp.match(/group\(m, new Runnable\(\)/g) || []).length) + ' 处');
  check('⑭ Settings 走 ContentProvider（Settings.*.getAll 是隐藏 API，编译期就找不到符号）',
    /content:\/\/settings\/system/.test(sp) && /getContentResolver\(\)\.query\(/.test(sp)
      && !/=\s*Settings\.\w+\.getAll\(/.test(sp));
  check('⑭ 只读：不新增任何权限（无 READ_LOGS / PACKAGE_USAGE_STATS）',
    !/READ_LOGS/.test(manifest) && !/PACKAGE_USAGE_STATS/.test(manifest));
  check('⑭ 采集开始前先做能力探测（先回答「能挖什么」，再谈变化）',
    /SysProbe\.capability\(appCtx\)/.test(sc) && /logCapability\(cap\)/.test(sc));
  check('⑭ diff 抽成零 Android 依赖的纯逻辑类（可在 JVM 上回归）',
    !/^import android\./m.test(sd) && /public static List<Change> diff\(/.test(sd));
  check('⑭ 关键字用词边界匹配（acc 不会撞 accessibility、lock 不会撞 clock）',
    /public static boolean hit\(String lowKey, String lowKeyword\)/.test(sd)
      && /!alnum\(lowKey\.charAt\(i - 1\)\)/.test(sd));
  check('⑭ 桥接契约：3 个新桥都带 @JavascriptInterface',
    ['startSignalCapture', 'stopSignalCapture', 'isSignalCapturing']
      .every(n => new RegExp('@JavascriptInterface[\\s\\S]{0,200}?' + n + '\\s*\\(').test(mainSrc)));
  check('⑭ 标记功能已彻底移除（桥 / 方法 / 产物字段都不再存在）',
    !/markSignal/.test(mainSrc) && !/public static void mark\(/.test(sc)
      && !/marks/.test(sc) && !/kind", "mark"/.test(sc));

  /* ============ ⑮ v1.5.15 移除校验：上报链路必须一点不剩 ============ */
  const gone = [path.join(SRC, 'PinnedSsl.java'), path.join(SRC, 'LogDiag.java'),
                path.join(SRC, 'DnsQuery.java'), path.join(SRC, 'LogBatch.java')];
  check('⑮ 4 个只服务于上报的死代码类已删除',
    gone.every(f => !fs.existsSync(f)), gone.filter(f => fs.existsSync(f)).join(', '));

  check('⑮ L6Log 里已无上报实现（URL/开关/定时上传/POST/矩阵/DNS）',
    !/LOG_UPLOAD_URL|apiUploadEnabled|uploadOnce|postJson|probeApiMatrix|probeLogApi/.test(lg)
      && !/LOG_UPLOAD_URL_FALLBACK|INGEST_HOST|customDns|setCustomDns/.test(lg),
    (lg.match(/LOG_UPLOAD_URL|apiUploadEnabled|uploadOnce|postJson|probeApiMatrix|probeLogApi|LOG_UPLOAD_URL_FALLBACK|INGEST_HOST|customDns/g) || []).join(', '));
  check('⑮ L6Log 只留下本地日志能力（写/落盘/缓冲/Base64 回推）',
    /public static void log\(String level/.test(lg) && /public static File logFile\(\)/.test(lg)
      && /public static JSONArray recentJson\(int n\)/.test(lg)
      && /public static String jsCall\(String fn, String json\)/.test(lg));
  check('⑮ L6Log 不再引用已删除的 4 个类',
    !/PinnedSsl|LogDiag|DnsQuery|LogBatch/.test(lg));

  const deadBridges = ['setApiUpload', 'isApiUpload', 'uploadLog', 'testLogApi',
    'getUploadStatus', 'setDnsServer', 'getDnsServer', 'probeApiMatrix', 'isMatrixRunning', 'markSignal'];
  check('⑮ 10 个上报/标记桥已从 MainActivity 删除',
    deadBridges.every(n => mainSrc.indexOf(n) < 0),
    deadBridges.filter(n => mainSrc.indexOf(n) >= 0).join(', '));
  check('⑮ 上报相关监听器接线已移除（upload/probe/matrix 三条）',
    !/setUploadListener|setProbeListener|setMatrixListener|L6LogUploadStatus|L6LogProbeResult|L6LogMatrixResult/.test(mainSrc));

  const shimCalls = (shim.match(/:\s*function/g) || []).length;
  check('⑮ SHIM 桥数量 = 35（删 10 条 + cancelOtaUpdate + 主页模式 3 条）', shimCalls === 35, `实得 ${shimCalls}`);
  // v1.5.16 主页模式：导航 App 悬浮窗权限入口 + 本应用画中画（两者都用 !! 包成布尔回页面）
  check('⑮ 主页模式 3 个桥已接线（openAppOverlaySettings / enterPip / isPipAvailable）',
    /openAppOverlaySettings:function\(p\)\{try\{return !!R\.openAppOverlaySettings\(p\);\}catch\(e\)\{return false;\}\},/.test(shim) &&
    /enterPip:function\(\)\{try\{return !!R\.enterPip\(\);\}catch\(e\)\{return false;\}\},/.test(shim) &&
    /isPipAvailable:function\(\)\{try\{return !!R\.isPipAvailable\(\);\}catch\(e\)\{return false;\}\},/.test(shim) &&
    /public boolean openAppOverlaySettings\(String pkg\)/.test(mainSrc) &&
    /public boolean enterPip\(\)/.test(mainSrc) &&
    /public boolean isPipAvailable\(\)/.test(mainSrc));
  check('⑮ manifest 已开启画中画（resizeableActivity=true + supportsPictureInPicture）',
    /android:resizeableActivity="true"/.test(manifest) && /android:supportsPictureInPicture="true"/.test(manifest));

  const deadUi = ['logApiBtn', 'logNowBtn', 'logTestBtn', 'logMatrixBtn', 'logUpState',
    'logUpMsg', 'matrixOut', 'matrixHint', 'dnsInput', 'dnsHint'];
  check('⑮ 页面「运行日志」卡片已整块移除（含开关/立即上报/测试接口/API 探测/接口 DNS）',
    deadUi.every(id => htmlSrc.indexOf(id) < 0),
    deadUi.filter(id => htmlSrc.indexOf(id) >= 0).join(', '));
  check('⑮ 页面上报/矩阵 JS 已移除（含两个回推入口与死 CSS）',
    !/syncLogApi|syncLogUp|syncDnsInput|L6LogUploadStatus|L6LogProbeResult|L6LogMatrixResult/.test(htmlSrc)
      && !/dns-inp/.test(htmlSrc));
  check('⑮ syncLogCfg 只同步采集状态', /function syncLogCfg\(\)\{ *syncSig\(\);/.test(htmlSrc));

  check('⑮ 日志目录改为直接落 Download/L6（不再有 logs 子目录）',
    /"L6"\);/.test(lg) && !/L6\/logs/.test(lg)
      && !/L6\/logs/.test(fs.readFileSync(path.join(SRC, 'OtaFileProvider.java'), 'utf8')));

  const pass = R.filter(r => r[1]).length;
  R.forEach(([n, ok2, det]) => console.log(`${ok2 ? '✓' : '✗'} ${n}${det ? '  [' + det + ']' : ''}`));
  const noise = errs.filter(e => !/HTMLMediaElement|getContext|Not implemented/.test(e));
  console.log(`\n通过 ${pass}/${R.length}；运行时错误 ${noise.length}`);
  noise.slice(0, 6).forEach(e => console.log('  ! ' + e));
  window.close();
  process.exit(pass === R.length && noise.length === 0 ? 0 : 1);
}, 300);
