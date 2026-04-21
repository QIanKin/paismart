#!/usr/bin/env node
// xhs-qr-login · 后端代理扫码登录
//
// 由 Java LoginBrowserRunner 直接 fork:
//   node run.mjs --session <uuid> --platforms <csv> --timeout <sec>
//
// 每条事件一行 JSON 写到 stdout；stderr 只输出调试日志。
// 详细协议见同目录 README.md。

import { chromium } from 'playwright';
import process from 'node:process';
import readline from 'node:readline';

// ---------- 参数解析 ----------

function parseArgs(argv) {
  const out = { session: '', platforms: 'xhs_pc,xhs_creator,xhs_pgy,xhs_qianfan', timeout: 180 };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--session') out.session = argv[++i];
    else if (a === '--platforms') out.platforms = argv[++i];
    else if (a === '--timeout') out.timeout = Number.parseInt(argv[++i], 10) || 180;
  }
  return out;
}

const ARGS = parseArgs(process.argv);
const PLATFORMS = ARGS.platforms.split(',').map((s) => s.trim()).filter(Boolean);
const TIMEOUT_MS = Math.max(30, ARGS.timeout) * 1000;

// ---------- 事件输出 ----------

function emit(event) {
  process.stdout.write(JSON.stringify(event) + '\n');
}
function log(msg, extra) {
  // stderr 不进事件流，只用于排障
  const line = `[xhs-qr-login ${ARGS.session}] ${msg}`;
  if (extra !== undefined) process.stderr.write(line + ' ' + JSON.stringify(extra) + '\n');
  else process.stderr.write(line + '\n');
}

// ---------- stdin 监听：支持外部 cancel ----------

let cancelled = false;
const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  if (!line.trim()) return;
  try {
    const cmd = JSON.parse(line);
    if (cmd.type === 'cancel') {
      cancelled = true;
      log('got cancel from stdin');
    }
  } catch (e) {
    // ignore
  }
});

// ---------- 工具函数 ----------

/**
 * 把 playwright 返回的 cookie 数组序列化为 "a=b; c=d" 形式。
 */
function serializeCookies(cookies) {
  return cookies.map((c) => `${c.name}=${c.value}`).join('; ');
}

function hasRequired(cookieStr) {
  const keys = new Set(cookieStr.split(';').map((s) => s.split('=')[0].trim()));
  return keys.has('a1') && keys.has('web_session') && keys.has('webId');
}

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

const PLATFORM_URLS = {
  xhs_pc: 'https://www.xiaohongshu.com/explore',
  xhs_creator: 'https://creator.xiaohongshu.com/creator-micro/home',
  xhs_pgy: 'https://pgy.xiaohongshu.com/',
  xhs_qianfan: 'https://qianfan.xiaohongshu.com/'
};

/**
 * 在 xhs.com 登录页尝试把二维码元素截图。
 * 不同登录弹窗 DOM 不同，做几层兜底选择器。
 * 成功返回 base64 data URL；否则 null。
 */
async function captureQrImage(page) {
  const selectors = [
    '.qrcode-img',
    '.login-qrcode img',
    '.login-container .qrcode-img',
    '.reds-login-dialog .qrcode',
    'img[src*="qrcode"]',
    'canvas' // 最后一根救命稻草：有的版本二维码是 canvas
  ];
  for (const sel of selectors) {
    try {
      const el = await page.$(sel);
      if (!el) continue;
      const box = await el.boundingBox();
      if (!box || box.width < 50 || box.height < 50) continue;
      const buf = await el.screenshot({ type: 'png' });
      return 'data:image/png;base64,' + buf.toString('base64');
    } catch (e) {
      // 继续下一个选择器
    }
  }
  return null;
}

/**
 * 从当前 context 读取特定平台的 cookie（按域名过滤）。
 * 返回形如 "a1=..; web_session=..; webId=.."；域名下没 cookie 则 ''.
 */
async function grabPlatformCookies(context, platform) {
  const url = PLATFORM_URLS[platform];
  if (!url) return '';
  const list = await context.cookies(url);
  if (!list || list.length === 0) return '';
  return serializeCookies(list);
}

/**
 * 判断在当前页是否已经登录成功（多种信号取或）。
 */
async function isLoggedIn(page) {
  try {
    // XHS 登录成功后 URL 里会带 /explore 或看得到用户侧边栏；登录弹窗会消失
    const stillHasDialog = await page.$('.login-container .qrcode-img, .reds-login-dialog .qrcode');
    if (stillHasDialog) return false;
    // cookie 维度：web_session 存在
    const cookies = await page.context().cookies('https://www.xiaohongshu.com');
    if (!cookies) return false;
    const names = cookies.map((c) => c.name);
    return names.includes('web_session') && names.includes('a1');
  } catch (e) {
    return false;
  }
}

/**
 * 主流程。
 */
async function main() {
  let browser;
  const deadline = Date.now() + TIMEOUT_MS;

  try {
    browser = await chromium.launch({
      headless: true,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-blink-features=AutomationControlled'
      ],
      executablePath: process.env.CHROMIUM_EXECUTABLE || undefined
    });
  } catch (e) {
    emit({
      type: 'error',
      errorType: 'chromium_launch_failed',
      message: 'Chromium 启动失败：' + (e?.message || String(e)) +
        '。请确认镜像里已 `npx playwright install chromium` 且安装了所需系统依赖（libnss3/libatk 等）。'
    });
    return 1;
  }

  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    viewport: { width: 1280, height: 900 },
    locale: 'zh-CN'
  });
  const page = await context.newPage();

  try {
    log('goto xhs_pc');
    await page.goto('https://www.xiaohongshu.com/explore', { waitUntil: 'domcontentloaded', timeout: 30000 });

    // 等登录弹窗出现；如果用户刚好已登录（history cookies），直接跳到抓 cookie
    let qrDataUrl = null;
    for (let i = 0; i < 20 && !cancelled && Date.now() < deadline; i++) {
      // 有些情况 XHS 进去就展示登录 modal；有些需要点“登录”按钮
      try {
        const loginBtn = await page.$('text=登录');
        if (loginBtn) {
          await loginBtn.click({ timeout: 2000 }).catch(() => {});
        }
      } catch (e) {}
      qrDataUrl = await captureQrImage(page);
      if (qrDataUrl) break;
      await sleep(750);
    }

    if (cancelled) { emit({ type: 'error', errorType: 'cancelled', message: '用户取消' }); return 2; }

    if (!qrDataUrl) {
      emit({
        type: 'error',
        errorType: 'qr_not_found',
        message: '首页未找到可截图的二维码元素，可能 XHS 登录布局变了或访问被风控拦截'
      });
      return 3;
    }
    emit({ type: 'qr_ready', dataUrl: qrDataUrl });
    log('qr emitted');

    // 轮询登录状态
    let sawScanned = false;
    let loggedIn = false;
    while (!cancelled && Date.now() < deadline) {
      // 观察文案变化（有的版本 DOM 会出现 "已扫描，请确认" 之类）
      if (!sawScanned) {
        const tip = await page.$('text=/已扫|请确认|Scanned/');
        if (tip) {
          sawScanned = true;
          emit({ type: 'status', status: 'SCANNED' });
        }
      }
      if (await isLoggedIn(page)) {
        loggedIn = true;
        emit({ type: 'status', status: 'CONFIRMED' });
        break;
      }
      // 重新采一次二维码：若页面因超时自己刷新了 QR，推个新图给前端
      const maybeNewQr = await captureQrImage(page);
      if (maybeNewQr && maybeNewQr !== qrDataUrl) {
        qrDataUrl = maybeNewQr;
        emit({ type: 'qr_ready', dataUrl: qrDataUrl });
      }
      await sleep(1500);
    }

    if (cancelled) { emit({ type: 'error', errorType: 'cancelled', message: '用户取消' }); return 2; }
    if (!loggedIn) {
      emit({ type: 'error', errorType: 'timeout', message: '登录超时' });
      return 4;
    }

    // 采集各平台 cookie；失败的平台空字符串留给 Java 归入 missing
    const cookies = {};
    for (const platform of PLATFORMS) {
      try {
        const url = PLATFORM_URLS[platform];
        if (!url) { cookies[platform] = ''; continue; }
        if (platform !== 'xhs_pc') {
          // 访问一下以触发跨站同步（很多子站点是走同源登录态）
          try {
            await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 });
            await sleep(1200);
          } catch (e) {
            log(`goto ${url} failed`, { err: e.message });
          }
        }
        const c = await grabPlatformCookies(context, platform);
        cookies[platform] = c;
        log(`grabbed ${platform}`, { len: c.length, hasRequired: hasRequired(c) });
      } catch (e) {
        cookies[platform] = '';
        log(`grab ${platform} failed`, { err: e.message });
      }
    }

    emit({ type: 'success', cookies });
    return 0;
  } catch (err) {
    emit({
      type: 'error',
      errorType: 'runtime_error',
      message: (err?.message || String(err)).slice(0, 480)
    });
    return 5;
  } finally {
    try { await browser?.close(); } catch (e) {}
    try { rl.close(); } catch (e) {}
  }
}

main().then((code) => {
  // 立刻退出，避免 stdin readline 挂住
  process.exit(code || 0);
}).catch((err) => {
  emit({ type: 'error', errorType: 'uncaught', message: String(err?.message || err) });
  process.exit(99);
});
