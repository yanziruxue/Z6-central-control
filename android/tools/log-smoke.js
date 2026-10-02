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
 *  ⑨ v1.5.8「接口 DNS」输入框：初值来自桥 / 非法 IP 拦截 / 合法调桥 / 探针显示解析来源与系统 DNS 对比
 *  ⑩ v1.5.11 接口连接契约（源码级）：X-Telemetry-Key 头 / 10s 超时 / 文档字段字典 / 401-403 fatal / 开关切换补发
 *  ⑪ v1.5.12「API 探测矩阵」按钮：调用桥 / 进行中态 / 防连点 / 逐项回推渲染 / 结束汇总
 *  ⑫ v1.5.12 上报默认关闭：Java 默认值 + 一次性迁移 + 页面初值与兜底都必须是「关」
 *  ⑬ v1.5.13 双通道上报（源码级 + UI）：443 主 / :2000 保底、Host 覆盖、回退条件、探针双端口
 *  ⑭ v1.5.14「车机信号采集」：开始/结束/标记三个桥 + 能力清单与变化的渲染 + 轮询参数与落盘契约
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
  probeApiMatrix() { this.calls.push(['probeApiMatrix']); },
  isMatrixRunning() { return false; },
  startSignalCapture() { this.calls.push(['startSignalCapture']); },
  stopSignalCapture() { this.calls.push(['stopSignalCapture']); },
  markSignal(s) { this.calls.push(['markSignal', String(s == null ? '' : s)]); },
  isSignalCapturing() { return false; },
  setApiUpload(b) { this.apiUpload = !!b; this.calls.push(['setApiUpload', !!b]); },
  isApiUpload() { return this.apiUpload; },
  dns: '223.5.5.5',
  setDnsServer(s) { this.dns = String(s == null ? '' : s); this.calls.push(['setDnsServer', this.dns]); },
  getDnsServer() { return this.dns || ''; },
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

  /* ---------- ⑩ 接口连接契约（源码级，对齐《上报触发与接口及上报内容》） ----------
     为什么用源码断言而不是跑起来看：这些是「有没有接上线」的硬事实（请求头 / 字段名 / 超时 /
     fatal 分支），jsdom 里既发不出真实请求、也读不到 Java 侧的行为，只有静态核对最可靠。
     断言的是**公共契约**（头名、字段名、常量值），不是实现细节，重构内部仍会通过。 */
  const LG = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'L6Log.java');
  const lg = fs.readFileSync(LG, 'utf8');
  check('⑩ 鉴权头 X-Telemetry-Key: <deviceId> 已接上',
    /AUTH_HEADER = "X-Telemetry-Key"/.test(lg) && /setRequestProperty\(AUTH_HEADER, deviceId\)/.test(lg),
    (lg.match(/AUTH_HEADER = "([^"]+)"/) || [])[1] || '未找到');
  check('⑩ 单次请求超时 = 10 秒（文档 REQUEST_TIMEOUT_MS）',
    /REQUEST_TIMEOUT_MS = 10000/.test(lg) &&
    /setConnectTimeout\(REQUEST_TIMEOUT_MS\)/.test(lg) && /setReadTimeout\(REQUEST_TIMEOUT_MS\)/.test(lg));
  const META_KEYS = ['device_uuid', 'appVersion', 'appVersionCode', 'os', 'osVersion', 'arch',
    'channel', 'virtualized', 'uploadEnabled', 'ts_iso', 'installedAt', 'hardware', 'details'];
  const miss = META_KEYS.filter(k => lg.indexOf('body.put("' + k + '"') < 0);
  check('⑩ 载荷补齐文档字段字典（' + META_KEYS.length + ' 项）',
    miss.length === 0, miss.length ? '缺 ' + miss.join(', ') : META_KEYS.length + ' 项齐全');
  check('⑩ 只增不改：原有键 device/lines 仍在',
    lg.indexOf('body.put("device", deviceId)') >= 0 && lg.indexOf('body.put("lines", lines)') >= 0);
  check('⑩ 401/403 走 fatal 退避（不持续轰击远端）',
    /r\.code == 401 \|\| r\.code == 403/.test(lg) && /private static int noteFatalAuth\(/.test(lg));
  check('⑩ 开关切换瞬间补发一条（同步 uploadEnabled）',
    /boolean changed = \(apiUploadEnabled != b\)/.test(lg) && /if \(changed\) \{[\s\S]{0,260}?uploadNow\(true\)/.test(lg));
  check('⑩ 探针走同一套元信息（探针过 = 正式上报过）',
    (lg.match(/fillMeta\(body\)/g) || []).length >= 2,
    'fillMeta 调用 ' + ((lg.match(/fillMeta\(body\)/g) || []).length) + ' 处');
  check('⑩ 设置页文案写明「最近 1 分钟」', /最近 1 分钟/.test(fs.readFileSync(HTML, 'utf8')));

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
    txt(msg).indexOf('DNS 12ms') >= 0 && txt(msg).indexOf('TCP:443 45ms') >= 0
      && txt(msg).indexOf('HTTP 200') >= 0, txt(msg));

  // ⑨ v1.5.8「接口 DNS」输入框：根因就是本机解析到了错误 IP，
  //    这个框让用户手填可信 DNS 绕开它，必须能写桥、能拦非法输入、探针能回显解析来源。
  const dinp = d.getElementById('dnsInput');
  check('⑨ #dnsInput 存在', !!dinp);
  check('⑨ 初值来自原生 getDnsServer', dinp && dinp.value === '223.5.5.5',
    dinp ? dinp.value : 'null');
  const hintsOf = () => ((d.getElementById('dnsHint') || {}).textContent || '');
  const dnsCalls = () => NativeRaw.calls.filter(c => c[0] === 'setDnsServer').length;
  NativeRaw.calls.length = 0;
  dinp.value = '999.1.1.1';                                       // 非法 → 不应写桥
  dinp.dispatchEvent(new window.Event('change', { bubbles: true }));
  check('⑨ 非法 IP 不写桥且给红字提示',
    dnsCalls() === 0 && hintsOf().indexOf('合法') >= 0, hintsOf());
  dinp.value = '119.29.29.29';
  dinp.dispatchEvent(new window.Event('change', { bubbles: true }));
  check('⑨ 合法 IP 调原生 setDnsServer',
    dnsCalls() === 1 && NativeRaw.dns === '119.29.29.29', NativeRaw.dns);
  check('⑨ 保存后有确认文案', hintsOf().indexOf('119.29.29.29') >= 0, hintsOf());

  // ⑨ 探针要能显示解析来源，两个解析源不一致时并排给出（正是本次踩的坑）
  window.L6LogProbeResult({ ok: false, url: 'https://x/api/l6zk/log', host: 'x',
    dns: '60.205.231.18', family: 'IPv4', dnsMs: 2, dnsSource: '自定义 223.5.5.5',
    sysDns: '60.205.251.18', tcpMs: 53, code: -1, phase: 'write',
    err: 'SocketException: Connection reset', ms: 80, resp: '' });
  check('⑨ 探针显示解析来源', txt(msg).indexOf('自定义 223.5.5.5') >= 0, txt(msg));
  check('⑨ 探针并排显示系统 DNS（不一致时一眼看穿）',
    txt(msg).indexOf('系统 DNS：60.205.251.18') >= 0, txt(msg));

  // ⑪ v1.5.12「API 探测矩阵」：上报失败在网络侧按 SNI 拦掉之后，App 里只剩一句「网络异常」，
  //    靠这个按钮把「哪条访问方式真能打到 API」逐项摊开；结论落本地日志 + 另存 JSON。
  const mbtn = d.getElementById('logMatrixBtn');
  const mout = d.getElementById('matrixOut');
  check('⑪ #logMatrixBtn 存在', !!mbtn);
  check('⑪ 按钮文案含「API 探测」', txt(mbtn).indexOf('API 探测') >= 0, txt(mbtn));
  check('⑪ 结果区 #matrixOut 存在且初始隐藏', !!mout && mout.hidden === true);
  const matrixCalls = () => NativeRaw.calls.filter(c => c[0] === 'probeApiMatrix').length;
  NativeRaw.calls.length = 0;
  click(mbtn);
  check('⑪ 点击调用原生 probeApiMatrix', matrixCalls() === 1, `调用 ${matrixCalls()} 次`);
  check('⑪ 探测中：按钮 disabled + 文案「探测中…」',
    mbtn.disabled === true && txt(mbtn).indexOf('探测中') >= 0, txt(mbtn));
  check('⑪ 探测中：结果区已展开', mout.hidden === false);
  click(mbtn); click(mbtn);
  check('⑪ 防连点：重复点击不重复调用', matrixCalls() === 1, `调用 ${matrixCalls()} 次`);
  // 逐项回推（kind=case）→ 必须把方式名 / 握手形态 / 失败阶段与 HTTP 码都渲染出来
  window.L6LogMatrixResult({ kind: 'case', idx: 1, total: 17, name: '域名+SNI+TLS1.2',
    sni: '带', tls: 'TLS1.2', dns: '60.205.251.18', dnsMs: 12, tcpMs: 40,
    phase: 'tls', err: 'SocketException: Connection reset', ms: 123 });
  window.L6LogMatrixResult({ kind: 'case', idx: 3, total: 17, name: '钉IP+去SNI+默认协议',
    sni: '无', tls: '默认', dns: '60.205.251.18', dnsMs: 11, tcpMs: 38,
    tlsMs: 90, proto: 'TLSv1.2', certCn: 'ziruxue.top', code: 200, statusLine: 'HTTP/1.1 200 OK',
    resp: '{"success":true}', ms: 260 });
  const mtext = txt(mout);
  check('⑪ 逐项渲染：出现方式名与序号', mtext.indexOf('域名+SNI+TLS1.2') >= 0
    && mtext.indexOf('#1/17') >= 0, mtext.slice(0, 120));
  check('⑪ 逐项渲染：失败项带阶段 + 异常原文',
    mtext.indexOf('tls') >= 0 && mtext.indexOf('Connection reset') >= 0);
  check('⑪ 逐项渲染：成功项带 HTTP 码 / 协议 / 证书 CN',
    mtext.indexOf('HTTP 200') >= 0 && mtext.indexOf('TLSv1.2') >= 0
      && mtext.indexOf('ziruxue.top') >= 0);
  check('⑪ 逐项渲染：两行都在（未被后一条覆盖）', mtext.split('\n').length >= 2,
    '行数 ' + mtext.split('\n').length);
  // 结束回推（kind=done）→ 汇总 + 按钮复位
  window.L6LogMatrixResult({ kind: 'done', total: 17, pass: 4, ms: 21000,
    file: '/Download/L6/logs/api-matrix-20260930-210000.json' });
  const mhint = txt(d.getElementById('matrixHint'));
  check('⑪ 结束回推：按钮复位', mbtn.disabled === false && txt(mbtn).indexOf('API 探测') >= 0, txt(mbtn));
  check('⑪ 结束回推：汇总「通 x/y」+ JSON 路径',
    mhint.indexOf('通 4/17') >= 0 && mhint.indexOf('api-matrix-') >= 0, mhint);

  // ⑫ v1.5.12 上报默认关闭：三个点都要是「关」，只改一处会漏
  check('⑫ Java 默认值为关闭', /private static boolean apiUploadEnabled = false;/.test(lg));
  check('⑫ 一次性迁移标记存在（老包存过 true，只改默认值不管用）',
    /KEY_API_UPLOAD_OFF_V1512/.test(lg) && /putBoolean\(KEY_API_UPLOAD, false\)/.test(lg));
  const htmlSrc = fs.readFileSync(HTML, 'utf8');
  check('⑫ 页面开关初值为「关闭」', /id="logApiBtn">○ 关闭</.test(htmlSrc),
    (htmlSrc.match(/id="logApiBtn">[^<]*</) || ['未找到'])[0]);
  check('⑫ 页面兜底为 false（无桥时不误显示成已开启）',
    /isApiUpload\?n\.isApiUpload\(\):false/.test(htmlSrc));

  // ⑬ v1.5.13 双通道上报。443+去SNI 是「钻过滤器空子」，规则一变就全挂 ⇒ 必须有一条
  //    明文直连网闸的保底腿。这一段的断言全部是**结构性事实**，jsdom 测不到（发不出真请求）。
  check('⑬ 保底通道常量 = http :2000 上报路径',
    /LOG_UPLOAD_URL_FALLBACK = "http:\/\/yanzi-api\.ziruxue\.top:2000\/api\/l6zk\/log"/.test(lg));
  check('⑬ 网闸用的 Host 常量（不带端口）存在',
    /INGEST_HOST = "yanzi-api\.ziruxue\.top"/.test(lg));
  // ★ 这条是本功能最容易静默失效的地方：URL 带 :2000 ⇒ 自动 Host 会带端口 ⇒ 网闸按 Host
  //   精确匹配一条服务都匹配不上 ⇒ 403。必须显式覆盖。
  check('⑬ 明文通道显式覆盖 Host 头（漏了必然 403「未识别到来源服务」）',
    /setRequestProperty\("Host", hostHeader\)/.test(lg)
      && /postJson\(fallbackUrl2000\(\), json, INGEST_HOST\)/.test(lg));
  check('⑬ 保底通道走解析出的 IPv4（明文无证书兜底，更要钉住 IP）',
    /private static String fallbackUrl2000\(\)/.test(lg)
      && /pickIpv4\(resolveHost\(INGEST_HOST\)\.ips\)/.test(lg));
  // 顺序：主通道必须先于保底出现（源码里 postJsonChannels 的书写顺序 = 实际尝试顺序）
  const iCh = lg.indexOf('private static PostResult postJsonChannels');
  const iTls = lg.indexOf('PostResult r1 = retry ? postJsonWithRetry(LOG_UPLOAD_URL', iCh);
  const i2000 = lg.indexOf('postJson(fallbackUrl2000()', iCh);
  check('⑬ 顺序：443 主 → :2000 保底', iCh > 0 && iTls > iCh && i2000 > iTls,
    `index: 方法=${iCh} 443=${iTls} :2000=${i2000}`);
  check('⑬ 只在网络层失败时回退（4xx 换通道答案一样，别白打一次）',
    /if \(r1\.ok\(\) \|\| !LogDiag\.isTransient\(r1\.code\)\)/.test(lg));
  check('⑬ 保底只发一次不重试（已切保底还失败 = 网真断了）',
    i2000 > 0 && lg.indexOf('postJsonWithRetry(fallbackUrl2000', iCh) < 0);
  check('⑬ 上报主路径与探针都走双通道',
    (lg.match(/postJsonChannels\(/g) || []).length >= 3,   // 定义 1 + 上报 1 + 探针 1
    'postJsonChannels 出现 ' + ((lg.match(/postJsonChannels\(/g) || []).length) + ' 次');
  check('⑬ 探针同时裸测 :2000 端口（安全组放没放开的唯一现场指标）',
    /tcp2000Ms/.test(lg) && /sk2\.connect\(new InetSocketAddress\(first2, 2000\), 8000\)/.test(lg));
  check('⑬ 失败状态行与日志都带上通道名',
    /通道 " \+ r\.channel/.test(lg) && /r\.channel \+ " \[轨迹 " \+ r\.trail/.test(lg));
  check('⑬ 探针结果回推 channel/trail',
    /\.put\("channel", r\.channel\)\.put\("trail", r\.trail\)/.test(lg));
  // UI：探针状态行必须把两个端口的 TCP 结果都摊开
  window.L6LogProbeResult({ ok: false, url: 'https://x/api/l6zk/log', host: 'x', dns: '1.2.3.4',
    family: 'IPv4', dnsMs: 12, tcpMs: 45, tcp2000Ms: -1,
    tcp2000Err: 'SocketTimeoutException: connect timed out', code: -1, phase: 'tls',
    err: 'SocketException: Connection reset', ms: 100, resp: '',
    channel: ':2000 HTTP（直连网闸·明文）', trail: '443 失败(Connection reset) → :2000 也失败(超时)' });
  check('⑬ 探针状态行显示 TCP:443 与 TCP:2000',
    txt(msg).indexOf('TCP:443 45ms') >= 0 && txt(msg).indexOf('TCP:2000 失败') >= 0, txt(msg));
  check('⑬ 探针状态行显示走了哪条通道',
    txt(msg).indexOf('443 失败') >= 0 && txt(msg).indexOf(':2000') >= 0, txt(msg));
  window.L6LogProbeResult({ ok: true, url: 'https://x/api/l6zk/log', host: 'x', dns: '1.2.3.4',
    family: 'IPv4', dnsMs: 12, tcpMs: 45, tcp2000Ms: 38, code: 200, phase: '', err: '',
    ms: 218, resp: '{"ok":true}', channel: ':2000 HTTP（直连网闸·明文）', trail: '443 失败(超时) → :2000 成功' });
  check('⑬ :2000 通了要显示耗时（而不是「失败」）',
    txt(msg).indexOf('TCP:2000 38ms') >= 0 && txt(msg).indexOf(':2000 成功') >= 0, txt(msg));

  // ⑭ v1.5.14「车机信号采集」：点开始 → 操作车门/车窗/车辆按钮 → 点结束 → 发日志。
  //    UI/行为部分在 jsdom 里能真跑（点按钮 → 看是否调桥、推事件 → 看渲染）；
  //    轮询间隔/上限/忽略清单/落盘目录这些 jsdom 测不到的硬事实走源码断言。
  const SC = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'SignalCapture.java');
  const SP = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'SysProbe.java');
  const SD = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'SigDiff.java');
  const sc = fs.readFileSync(SC, 'utf8');
  const sp = fs.readFileSync(SP, 'utf8');
  const sd = fs.readFileSync(SD, 'utf8');
  const mainSrc = fs.readFileSync(JAVA, 'utf8');
  const manifest = fs.readFileSync(path.join(ROOT, 'AndroidManifest.xml'), 'utf8');

  const sbtn = d.getElementById('sigBtn');
  const sout = d.getElementById('sigOut');
  const smsg = d.getElementById('sigMsg');
  const sigStarts = () => NativeRaw.calls.filter(c => c[0] === 'startSignalCapture').length;

  check('⑭ 采集按钮 / 输出区 / 状态行都在', !!sbtn && !!sout && !!smsg);

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

  NativeRaw.calls.length = 0;
  click(d.querySelector('.sig-mark'));
  check('⑭ 点标记按钮调原生 markSignal(车门)',
    NativeRaw.calls.some(c => c[0] === 'markSignal' && c[1] === '车门'),
    JSON.stringify(NativeRaw.calls));
  window.L6SignalResult({ kind: 'mark', t: 14000, label: '车门' });
  check('⑭ 标记渲染为 ★ 行', txt(sout).indexOf('★ 车门') >= 0);

  window.L6SignalResult({ kind: 'done', ms: 83000, changes: 17, marks: 3, logcatLines: 0,
    file: '/Download/L6/logs/signal-20261002-213000.json' });
  check('⑭ 结束回推：按钮复位为「开始采集」', txt(sbtn).indexOf('开始采集') >= 0, txt(sbtn));
  check('⑭ 结束回推：汇总含变化条数 + JSON 路径 + 提示发日志',
    txt(smsg).indexOf('17 条') >= 0 && txt(smsg).indexOf('signal-') >= 0
      && txt(smsg).indexOf('L6Signal') >= 0, txt(smsg));
  NativeRaw.calls.length = 0;
  click(sbtn);
  check('⑭ 结束后再点 → 走「开始」分支（能反复采）', sigStarts() === 1, `调用 ${sigStarts()} 次`);
  window.L6SignalResult({ kind: 'done', ms: 800, changes: 0, marks: 0, logcatLines: 0, file: '' });

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
  check('⑭ 桥接契约：4 个新桥都带 @JavascriptInterface',
    ['startSignalCapture', 'stopSignalCapture', 'markSignal', 'isSignalCapturing']
      .every(n => new RegExp('@JavascriptInterface[\\s\\S]{0,200}?' + n + '\\s*\\(').test(mainSrc)));

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
