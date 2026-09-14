/*
 * OTA 模块冒烟测试（jsdom，不连真实网络）：
 *   1) SHIM 注入后，window.L6Native 应有 checkOtaUpdate / installOtaUpdate / getOtaConfig
 *   2) 设置页应含 OTA 卡片关键元素
 *   3) 模拟原生回调 L6OtaEvent({kind:"check", hasUpdate:true, ...})，验证 UI 更新、强制蒙层显示
 *   4) 点击「立即更新」应带 apkUrl/sha256 调 installOtaUpdate
 *   5) 0 运行时错误
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'MainActivity.java');
const HTML = path.resolve(ROOT, '..', 'apk-dashboard-prototype.html');

const java = fs.readFileSync(JAVA, 'utf8');
const m = java.match(/private static final String SHIM =([\s\S]*?);\n/);
if (!m) { console.error('✗ 未能从 MainActivity.java 解析 SHIM'); process.exit(1); }
const shim = m[1].split('\n').map(l => l.trim()).filter(l => l.startsWith('"'))
  .map(l => l.replace(/^"/, '').replace(/"\s*\+?\s*$/, '')).join('')
  .replace(/\\"/g, '"').replace(/\\'/g, "'");

const NativeRaw = {
  getInstalledAppsJson: () => '[]',
  saveWallpaper() {},
  launchApp() {},
  getOtaConfig: () => JSON.stringify({
    repoOwner: 'yanziruxue', repoName: 'Z6-central-control',
    otaJsonUrl: '', mirror: 'https://gh-proxy.com/',
  }),
  calls: [],
  checkOtaUpdate() {
    this.calls.push(['check']);
    // 模拟 Java 端 L6OtaEvent 派发：5s 后回调（页面有 setTimeout(L6Native.checkOtaUpdate, ...)）
    setTimeout(() => {
      window.L6OtaEvent({
        kind: 'check', hasUpdate: true, currentVersion: '1.0.0', currentCode: 1,
        versionName: '1.1.0', versionCode: 2, changelog: '新增 OTA 在线升级功能',
        apkUrl: 'https://example.com/test.apk', sha256: 'abc', force: false, size: 1234567,
      });
    }, 50);
  },
  installOtaUpdate(url, sha) { this.calls.push(['install', url, sha]); },
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

  const has = (id) => !!d.getElementById(id);
  const ui = has('otaCheck') && has('otaStatus') && has('otaNew') &&
             has('otaNewV') && has('otaCL') && has('otaInstall') &&
             has('otaLater') && has('otaForce') && has('otaForceBtn');
  const api = window.L6Native && typeof window.L6Native.checkOtaUpdate === 'function' &&
              typeof window.L6Native.installOtaUpdate === 'function' &&
              typeof window.L6Native.getOtaConfig === 'function';
  const cfg = window.L6Native.getOtaConfig();

  // 触发原生侧检查
  d.getElementById('otaCheck').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

  setTimeout(() => {
    const newShown = !d.getElementById('otaNew').hidden;
    const newV = d.getElementById('otaNewV').textContent;
    const cl = d.getElementById('otaCL').textContent;
    const curShown = d.getElementById('otaCur').textContent;
    const forceHidden = !d.getElementById('otaForce').classList.contains('on');
    const installBtn = d.getElementById('otaInstall');
    const dataOk = installBtn.dataset.url && installBtn.dataset.url.includes('test.apk');

    // 触发安装
    installBtn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const installCall = NativeRaw.calls.filter(c => c[0] === 'install').pop();

    // 强制更新场景
    NativeRaw.checkOtaUpdate = function () {
      setTimeout(() => window.L6OtaEvent({
        kind: 'check', hasUpdate: true, currentVersion: '1.0.0', versionCode: 1,
        versionName: '1.0.1', versionCode: 2, changelog: '强制升级测试', force: true,
        apkUrl: 'https://example.com/force.apk', sha256: 'deadbeef', size: 0,
      }), 30);
    };
    d.getElementById('otaCheck').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    setTimeout(() => {
      const forceShown = d.getElementById('otaForce').classList.contains('on');

      // --- 镜像回落 / 安装授权 / 下载大小 三类新增事件 ---
      window.L6OtaEvent({ kind: 'retry', message: '主源下载失败，正在尝试国内镜像…', source: '镜像' });
      const retryTxt = d.getElementById('otaStatus').textContent;

      window.L6OtaEvent({ kind: 'needPermission', message: '需要授权「允许安装未知应用」才能完成升级' });
      const permTxt = d.getElementById('otaStatus').textContent;
      const permHidesProg = d.getElementById('otaProg').hidden;

      window.L6OtaEvent({ kind: 'downloaded', size: 541678 });
      const dlTxt = d.getElementById('otaStatus').textContent;

      // --- 同版本不误报：装的就是最新版时，必须显示「已是最新」且不再弹更新卡片 ---
      // （修复前 versionCode=3 vs tag 解析 10200，会永远误报成有更新）
      window.L6OtaEvent({
        kind: 'check', hasUpdate: false, currentVersion: '1.2.1', currentCode: 10201,
        versionName: '1.2.1', versionCode: 10201, changelog: '', apkUrl: '', sha256: '', force: false,
      });
      const sameVerHidden = d.getElementById('otaNew').hidden;
      const sameVerTxt = d.getElementById('otaStatus').textContent;

      // --- 版本量纲一致性：build.sh 的 versionCode 必须与 Ota.java 从 tag 解析的公式同构 ---
      // 不同构就会出现「装了 v1.2.0 还提示升级到 v1.2.0」的误判。
      const bs = fs.readFileSync(path.join(ROOT, 'build.sh'), 'utf8');
      const verName = (bs.match(/^VER_NAME="([^"]+)"/m) || [])[1] || '';
      const codeOf = (v) => { const p = v.split('.').map(Number); return p[0] * 10000 + p[1] * 100 + p[2]; };
      const otaSrc = fs.readFileSync(path.join(ROOT, 'src', 'com', 'l6', 'carmedia', 'Ota.java'), 'utf8');
      // Java 侧公式：major*10000 + minor*100 + patch
      const formulaOk = /Integer\.parseInt\(p\[0\]\)\s*\*\s*10000\s*\+\s*Integer\.parseInt\(p\[1\]\)\s*\*\s*100\s*\+\s*Integer\.parseInt\(p\[2\]\)/.test(otaSrc);
      // 按 Java 公式解析 tag，应等于 build.sh 推出的 versionCode
      const tagCode = codeOf(((verName)) || '0.0.0');
      const verOk = formulaOk && tagCode === codeOf(verName) && tagCode > 0;

      // --- 配置契约：ota.properties / getOtaConfig 暴露的键必须**恰好**落在白名单内 ---
      // 用白名单而非黑名单点名：任何新增配置键（无论叫什么）都会被 unknownProps 抓到，
      // 不会出现「删了 A 又悄悄冒出 B」的漏检。
      const ALLOWED_PROP_KEYS = ['REPO_OWNER', 'REPO_NAME', 'OTA_JSON_URL', 'MIRROR'];
      const ALLOWED_CFG_KEYS = ['repoOwner', 'repoName', 'otaJsonUrl', 'mirror'];
      const otaProps = fs.readFileSync(path.join(ROOT, 'assets', 'ota.properties'), 'utf8');
      const propKeys = [...otaProps.matchAll(/^\s*([A-Z_]+)\s*=/gm)].map(m => m[1]);
      const unknownProps = propKeys.filter(k => !ALLOWED_PROP_KEYS.includes(k));
      const propsOk = ALLOWED_PROP_KEYS.every(k => propKeys.includes(k));
      const cfgObj = (typeof cfg === 'string') ? JSON.parse(cfg) : (cfg || {});
      const cfgKeys = Object.keys(cfgObj);
      const cfgClean = cfgKeys.every(k => ALLOWED_CFG_KEYS.includes(k));

      // --- 信息源契约：GitHub Releases 为主源（OTA_JSON_URL 仅作前置覆盖）---
      const cuSrc = otaSrc.slice(otaSrc.indexOf('checkUpdate(Activity'),
                                 otaSrc.indexOf('public static void downloadAndInstall'));
      const iGithub = cuSrc.indexOf('/releases/latest');
      const iJson = cuSrc.indexOf('cfg.otaJsonUrl');
      const orderOk = iGithub >= 0 && iJson >= 0 && iJson < iGithub;   // 自建 JSON 只作前置覆盖

      // 软失败必须能回落：严格判据（ok:false / 缺版本号 / 缺直链）+ 不外抛的取数封装
      const guardOk = /private static boolean isUsable\(JSONObject/.test(otaSrc) &&
                      /if \(!r\.optBoolean\("ok", true\)\) return false/.test(otaSrc) &&
                      /if \(r\.optInt\("versionCode", 0\) <= 0\) return false/.test(otaSrc) &&
                      /return !r\.optString\("downloadUrl", ""\)\.isEmpty\(\)/.test(otaSrc) &&
                      /private static JSONObject safeFetch\(/.test(otaSrc);

      // --- 源标注 UI：自建 JSON 命中 / 回落 GitHub / 全挂 ---
      const chk = (extra) => Object.assign({
        kind: 'check', hasUpdate: true, currentVersion: '1.2.4', currentCode: 10204,
        versionName: '1.2.5', versionCode: 10205, changelog: 'x',
        apkUrl: 'https://example.com/a.apk', sha256: '', force: false,
      }, extra);
      window.L6OtaEvent(chk({ source: 'json', sourceLabel: '自建 JSON' }));
      const jsonTxt = d.getElementById('otaStatus').textContent;

      window.L6OtaEvent(chk({
        source: 'github', sourceLabel: 'GitHub',
        tried: '自建 JSON example.com：无响应或返回内容不是 JSON',
      }));
      const fbTxt = d.getElementById('otaStatus').textContent;

      // 单源（只剩 GitHub）全挂：Java 给最直白的 error，页面仅加「检查失败：」前缀
      window.L6OtaEvent({
        kind: 'check',
        error: 'GitHub 无响应或未发版（yanziruxue/Z6-central-control）',
      });
      const allFailTxt = d.getElementById('otaStatus').textContent;

      const srcUiOk = /自建 JSON/.test(jsonTxt) && /已回落/.test(fbTxt) &&
                      /^检查失败：/.test(allFailTxt) && /GitHub 无响应或未发版/.test(allFailTxt);

      console.log('OTA 卡片 UI 元素齐全 ->', ui);
      console.log('SHIM 注入 OTA API     ->', api);
      console.log('OTA 配置                ->', cfg);
      console.log('点击后展示新版          ->', newShown, '（版本号=' + newV + ', 当前=' + curShown + '）');
      console.log('changelog 写入          ->', cl);
      console.log('非强制时不弹蒙层        ->', forceHidden);
      console.log('安装按钮带正确 URL      ->', dataOk);
      console.log('点击立即更新 → 原生     ->', installCall ? installCall[1] : '(未触发)');
      console.log('强制更新触发蒙层        ->', forceShown);
      console.log('镜像回落提示            ->', retryTxt);
      console.log('未授权安装提示          ->', permTxt, '（进度条隐藏=' + permHidesProg + '）');
      console.log('下载完成带体积          ->', dlTxt);
      console.log('同版本不误报更新        ->', sameVerHidden, '（' + sameVerTxt + '）');
      console.log('版本量纲 1.2.1->10201   ->', verName, '=', tagCode, '公式同构=' + formulaOk);
      console.log('OTA 配置键白名单内      ->', propsOk, '（键：' + propKeys.join(',') + '）');
      console.log('未知配置键              ->', unknownProps.length ? '⚠ ' + unknownProps.join(',') : '无');
      console.log('getOtaConfig 键白名单内 ->', cfgClean, '（键：' + cfgKeys.join(',') + '）');
      console.log('源顺序 JSON(可选)→GitHub ->', orderOk, '（json@' + iJson + ' < github@' + iGithub + '）');
      console.log('软失败回落护栏          ->', guardOk);
      console.log('源标注UI JSON/回落/全挂 ->', srcUiOk);
      console.log('  自建JSON :', jsonTxt);
      console.log('  回落GitHub:', fbTxt);
      console.log('  全挂      :', allFailTxt);
      console.log('运行时错误              ->', errs.length, errs.join(' | '));

      const ok = ui && api && newShown && /1\.1\.0/.test(newV) && dataOk && installCall &&
                 installCall[1].includes('test.apk') && forceShown && errs.length === 0 &&
                 /镜像/.test(retryTxt) && /未知应用/.test(permTxt) && permHidesProg &&
                 /MB/.test(dlTxt) && verOk && sameVerHidden && /已是最新/.test(sameVerTxt) &&
                 propsOk && unknownProps.length === 0 && cfgClean && orderOk &&
                 guardOk && srcUiOk;
      console.log(ok ? '✓ OTA 冒烟测试通过' : '✗ OTA 冒烟测试失败');
      process.exit(ok ? 0 : 2);
    }, 200);
  }, 200);
}, 900);