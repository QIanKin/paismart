#!/usr/bin/env node
/**
 * qiangua-brand-discover · search_qiangua.mjs
 *
 * 通过 CDP 连业务员本机 Chrome 的已登录千瓜 tab，
 * 按品牌名抓取品牌详情页 + 相关达人列表。
 *
 * 来源：openclaw-backup/.openclaw/workspace/xiaohongshu-outreach-bridge/search_qiangua.mjs 扩展。
 * 增加了：
 *   - 参数化 brandName / maxKols
 *   - 尝试结构化抽取达人（宽松 selector + 正则兜底）
 *   - out.json 规范（ok/pageText/kols/warnings/errorType）
 */

import fs from 'fs';
import path from 'path';
import { chromium } from 'playwright';

// ---- argv ----

function parseArgs() {
  const a = process.argv.slice(2);
  const out = {};
  for (let i = 0; i < a.length; i++) {
    const k = a[i];
    if (!k.startsWith('--')) continue;
    const key = k.slice(2);
    const v = a[i + 1];
    if (v === undefined || v.startsWith('--')) out[key] = 'true';
    else { out[key] = v; i++; }
  }
  return out;
}

function writeOut(outPath, payload) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(payload, null, 2), 'utf8');
}

function fail(outPath, errorType, msg, extra = {}) {
  writeOut(outPath, { ok: false, errorType, error: msg, ...extra });
  process.exit(1);
}

// ---- 数字解析（千瓜常见 "12.3w" / "4567" / "1.2万" 格式） ----

function parseCn(n) {
  if (!n) return null;
  const s = String(n).trim().toLowerCase().replace(',', '');
  const m = s.match(/([0-9.]+)\s*([wk万])?/);
  if (!m) return null;
  let num = parseFloat(m[1]);
  if (isNaN(num)) return null;
  if (m[2] === 'w' || m[2] === '万') num *= 10000;
  else if (m[2] === 'k') num *= 1000;
  return Math.round(num);
}

// ---- 页面抽取 ----

async function extractKols(page, maxKols) {
  // 尽量宽松抓取 —— 千瓜的 "相关达人" 表格 / 卡片
  return await page.evaluate((MAX) => {
    const out = [];
    // 策略一：有 href 指到小红书 user/profile 的节点，多半是达人卡
    const anchors = document.querySelectorAll('a[href*="xiaohongshu.com/user/profile/"]');
    anchors.forEach((a) => {
      if (out.length >= MAX) return;
      const url = a.href;
      const m = url.match(/user\/profile\/([^?#/]+)/);
      const xhsUserId = m ? m[1] : null;
      // 找最近的行/卡片容器，抓昵称和粉丝
      let parent = a.closest('tr, .kol-item, li, .card, .ant-list-item, div');
      const row = parent ? parent.innerText : a.innerText;
      const nickname = (a.innerText || '').trim() || (row.split('\n')[0] || '').trim();
      // 粉丝/互动数的启发式抽取
      let followers = null, avgLikes = null, avgComments = null, engagementRate = null;
      const fM = row.match(/粉丝\s*([0-9.]+[wk万]?)/i);
      if (fM) followers = fM[1];
      const lM = row.match(/(?:赞|点赞|平均点赞)\s*([0-9.]+[wk万]?)/i);
      if (lM) avgLikes = lM[1];
      const cM = row.match(/(?:评论|平均评论)\s*([0-9.]+[wk万]?)/i);
      if (cM) avgComments = cM[1];
      const eM = row.match(/(?:互动率|互动)\s*([0-9.]+)\s*%?/);
      if (eM) engagementRate = parseFloat(eM[1]);
      out.push({ nickname, xhsUserId, profileUrl: url, followers, avgLikes, avgComments, engagementRate });
    });
    return out;
  }, maxKols);
}

// ---- 主流程 ----

async function main() {
  const args = parseArgs();
  const outPath = args['output'];
  if (!outPath) { console.error('--output 必填'); process.exit(2); }

  const brandName = args['brand-name'];
  if (!brandName) fail(outPath, 'bad_args', '--brand-name 必填');
  const maxKols = parseInt(args['max-kols'] || '30', 10);
  const timeoutMs = parseInt(args['timeout-ms'] || '30000', 10);
  const includePageText = (args['include-page-text'] || 'true') === 'true';
  const cdpEndpoint = process.env.CDP_ENDPOINT || 'http://localhost:9222';

  let browser;
  try {
    browser = await chromium.connectOverCDP(cdpEndpoint);
  } catch (e) {
    fail(outPath, 'cdp_unreachable',
      `无法连接 Chrome CDP (${cdpEndpoint})：${e.message}。请先启动业务员本机 Chrome --remote-debugging-port=9222`);
  }

  // 找已登录的 qian-gua.com tab，没有则新开
  let page = null;
  for (const ctx of browser.contexts()) {
    for (const p of ctx.pages()) {
      if (p.url().includes('qian-gua.com')) { page = p; break; }
    }
    if (page) break;
  }
  if (!page) {
    const ctx = browser.contexts()[0] || (await browser.newContext());
    page = await ctx.newPage();
    try {
      await page.goto('https://app.qian-gua.com/', { waitUntil: 'domcontentloaded', timeout: timeoutMs });
    } catch (e) { /* ignore */ }
  }

  const warnings = [];
  // 登录态检测
  const currentUrl = page.url();
  if (currentUrl.includes('login') || currentUrl.includes('signin')) {
    fail(outPath, 'not_logged_in',
      '千瓜未登录。请业务员在本机 Chrome 里先登录 https://app.qian-gua.com/ 再重试');
  }

  // 访问品牌详情
  const sourceUrl = `https://app.qian-gua.com/#/brand/brandDetail?brandName=${encodeURIComponent(brandName)}`;
  try {
    await page.goto(sourceUrl, { waitUntil: 'networkidle', timeout: timeoutMs });
  } catch (e) {
    warnings.push(`goto networkidle 超时，继续抓当前状态：${e.message}`);
  }
  await page.waitForTimeout(3000);

  // 尝试结构化抽取
  let kols = [];
  try {
    kols = await extractKols(page, maxKols);
  } catch (e) {
    warnings.push(`extractKols 异常: ${e.message}`);
  }

  // 数值字符串转数字
  const normalizedKols = kols.map((k) => ({
    ...k,
    followers: parseCn(k.followers),
    avgLikes: parseCn(k.avgLikes),
    avgComments: parseCn(k.avgComments),
  })).filter((k) => k.nickname || k.xhsUserId);

  let pageText = '';
  if (includePageText) {
    try {
      pageText = await page.evaluate(() => (document.body ? document.body.innerText : ''));
      if (pageText.length > 20000) pageText = pageText.slice(0, 20000) + '\n...[truncated]';
    } catch (_) {}
  }

  if (normalizedKols.length === 0) {
    warnings.push('未抓到结构化达人。selector 可能已失效；建议根据 pageText 用 LLM 兜底分析。');
  }

  try { /* 不要 close，因为用的是业务员已登录的 tab */ } finally {
    try { await browser.close(); } catch (_) {}
  }

  writeOut(outPath, {
    ok: true,
    brandName,
    sourceUrl,
    kols: normalizedKols,
    pageText,
    warnings,
  });
}

main().catch((e) => {
  const outPath = parseArgs()['output'];
  if (outPath) {
    fail(outPath, 'unhandled', `${e.name}: ${e.message}`, { stack: (e.stack || '').slice(0, 800) });
  } else {
    console.error(e); process.exit(1);
  }
});
