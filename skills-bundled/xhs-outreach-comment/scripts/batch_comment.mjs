#!/usr/bin/env node
/**
 * xhs-outreach-comment · batch_comment.mjs
 *
 * 简化自 openclaw-backup/.openclaw/workspace/xiaohongshu-outreach-bridge/batch_comment_v2.mjs：
 *  - 通过 CDP 连业务员本机 Chrome（已登录小红书）
 *  - 对 targets 列表逐个：搜索 / 打开主页 / 筛选 / 点第一条笔记 / 写评论 / 提交
 *  - 输出 out.json（由后端 XhsOutreachCommentTool 收去写 outreach_records）
 *
 * 不做：
 *  - 私信（合规）
 *  - 去重（交给 Java 侧 OutreachRecordRepository.findFirstByPlatformAndPlatformUserIdAndPostIdAndAction）
 *  - 截图落盘（后续可加）
 */

import fs from 'fs';
import path from 'path';
import { chromium } from 'playwright';

// ---- 常量 / 词库（和 Java 侧 CreatorScreenService 对齐） ----

const COMMERCIAL_KEYWORDS = [
  '柜姐', '柜哥', '专柜', '导购', '合作', '商务', '品牌', '官方', '旗舰',
  '代购', '跑腿', '推广', '种草', '接单', '报价', '询价', '私信下单',
  '店铺', '门店', '专卖', '经销', '批发', '零售', '采购',
  '新闻', '资讯', '媒体', '编辑', '记者', '杂志',
  '粉丝团', '后援会', '应援', '打call',
  '每日更新', '天天更新', '好物分享', '好物推荐',
  '活动', '随拍', '展示', '专业讲解',
  '欢迎留言', '感谢关注', '福利多多',
];

const PERSONAL_KEYWORDS = [
  '水瓶', '白羊', '金牛', '双子', '巨蟹', '狮子', '处女',
  '天秤', '天蝎', '射手', '摩羯', '双鱼',
  'INTP', 'INTJ', 'INFP', 'INFJ', 'ENTP', 'ENTJ', 'ENFP', 'ENFJ',
  'ISTP', 'ISTJ', 'ISFP', 'ISFJ', 'ESTP', 'ESTJ', 'ESFP', 'ESFJ',
  '大学', 'University', '学院',
  '吃不胖', '健身', '旅行', '潜水', '生活', '日常',
  '坐标', '一枚', '宝妈', '打工人',
];

const RATE_LIMIT_SIGNALS = [
  '操作过于频繁', '操作频繁', '请稍后再试',
  '系统繁忙', '访问受限',
  '滑块', '验证码', '人机验证', '安全验证',
  '账号异常', '账号被封',
];

// ---- argv 解析 ----

function parseArgs() {
  const a = process.argv.slice(2);
  const out = {};
  for (let i = 0; i < a.length; i++) {
    const k = a[i];
    if (!k.startsWith('--')) continue;
    const key = k.slice(2);
    const v = a[i + 1];
    if (v === undefined || v.startsWith('--')) {
      out[key] = 'true';
    } else {
      out[key] = v; i++;
    }
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

// ---- 小工具 ----

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function screenText(text) {
  const lower = (text || '').toLowerCase();
  const commercial = COMMERCIAL_KEYWORDS.filter((k) => lower.includes(k.toLowerCase()));
  const personal = PERSONAL_KEYWORDS.filter((k) => lower.includes(k.toLowerCase()));
  let verdict;
  if (commercial.length >= 2) verdict = 'N';
  else if (personal.length > 0 && commercial.length === 0) verdict = 'Y';
  else if (commercial.length <= 1) verdict = 'Y';
  else verdict = '?';
  return { verdict, commercialMatches: commercial, personalMatches: personal };
}

function detectRateLimit(text) {
  if (!text) return null;
  for (const s of RATE_LIMIT_SIGNALS) {
    if (text.includes(s)) return s;
  }
  return null;
}

function tmpl(s, ctx) {
  return s.replace(/\{(\w+)\}/g, (_, k) => (ctx[k] != null ? String(ctx[k]) : `{${k}}`));
}

// ---- 页面操作 ----

async function findProfileFromSearch(page, query) {
  const url = `https://www.xiaohongshu.com/search_result?keyword=${encodeURIComponent(query)}&source=web_search_result_users&type=user`;
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await sleep(2000);
  // 页面结构可能多变，尽量宽松找第一个 user 链接
  const profileUrl = await page.evaluate(() => {
    const a = document.querySelector('a[href*="/user/profile/"]');
    return a ? a.href : null;
  });
  return profileUrl;
}

async function getProfileInfo(page) {
  return await page.evaluate(() => {
    const grab = (sel) => (document.querySelector(sel)?.textContent || '').trim();
    const url = location.href;
    const m = url.match(/\/user\/profile\/([^?#/]+)/);
    const platformUserId = m ? m[1] : '';
    const nickname = grab('.user-name, .nickname, .user-nickname, h1');
    const bio = grab('.user-desc, .desc, .user-description, .description');
    const pageText = document.body ? document.body.innerText.slice(0, 3000) : '';
    // 粉丝数尝试抓
    let followers = null;
    const text = pageText;
    const fm = text.match(/粉丝\s*([0-9.wk万]+)/i);
    if (fm) {
      let s = fm[1].toLowerCase();
      let mul = 1;
      if (s.endsWith('w') || s.endsWith('万')) { mul = 10000; s = s.replace(/[w万]/, ''); }
      else if (s.endsWith('k')) { mul = 1000; s = s.replace('k', ''); }
      const n = parseFloat(s);
      if (!isNaN(n)) followers = Math.round(n * mul);
    }
    return { platformUserId, nickname, bio, pageText, followers };
  });
}

async function openFirstPost(page) {
  // 进笔记列表里第一条
  const postHref = await page.evaluate(() => {
    const candidates = [
      'a[href*="/explore/"]',
      'a[href*="/discovery/item/"]',
    ];
    for (const sel of candidates) {
      const list = document.querySelectorAll(sel);
      for (const a of list) {
        if (a.href && !a.href.includes('#')) return a.href;
      }
    }
    return null;
  });
  if (!postHref) return null;
  await page.goto(postHref, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await sleep(2500);
  return postHref;
}

async function commentOnCurrentPost(page, commentText) {
  // 1. 定位评论输入框
  const inputSelector = [
    'div[contenteditable="true"]',
    'textarea[placeholder*="评论"]',
    'input[placeholder*="评论"]',
  ];
  let input = null;
  for (const sel of inputSelector) {
    const el = await page.$(sel);
    if (el && await el.isVisible().catch(() => false)) { input = el; break; }
  }
  if (!input) {
    // 尝试点"说点什么"占位
    const placeholder = await page.$('text=说点什么');
    if (placeholder) {
      await placeholder.click().catch(() => {});
      await sleep(800);
      for (const sel of inputSelector) {
        const el = await page.$(sel);
        if (el && await el.isVisible().catch(() => false)) { input = el; break; }
      }
    }
  }
  if (!input) return { ok: false, reason: 'comment_box_not_found' };

  await input.click();
  await sleep(500);

  // 2. 拟人输入
  for (const ch of commentText) {
    await page.keyboard.type(ch, { delay: 40 + Math.random() * 80 });
  }
  await sleep(800 + Math.random() * 500);

  // 3. 提交：按 Enter 或点 "发送"
  const sendBtn = await page.$('button:has-text("发送"), button:has-text("发布")');
  if (sendBtn) {
    await sendBtn.click().catch(() => {});
  } else {
    await page.keyboard.press('Enter');
  }
  await sleep(2500);

  // 4. 反爬检测
  const bodyText = await page.evaluate(() => document.body ? document.body.innerText.slice(0, 2000) : '');
  const rl = detectRateLimit(bodyText);
  if (rl) return { ok: false, reason: 'rate_limit', signal: rl };
  return { ok: true };
}

// ---- 主流程 ----

async function main() {
  const args = parseArgs();
  const outPath = args['output'];
  if (!outPath) {
    console.error('--output 必填'); process.exit(2);
  }

  const targetsJson = args['targets'];
  const commentText = args['comment-text'];
  if (!targetsJson || !commentText) {
    fail(outPath, 'bad_args', '--targets 和 --comment-text 必填');
  }
  let targets;
  try {
    targets = JSON.parse(targetsJson);
    if (!Array.isArray(targets)) throw new Error('targets 不是数组');
  } catch (e) {
    fail(outPath, 'bad_args', 'targets JSON 解析失败: ' + e.message);
  }
  const maxTargets = parseInt(args['max-targets'] || '20', 10);
  const delayMs = parseInt(args['delay-ms'] || '15000', 10);
  const enableScreening = (args['enable-screening'] || 'true') === 'true';
  const stopOnRateLimit = (args['stop-on-rate-limit'] || 'true') === 'true';
  const cdpEndpoint = process.env.CDP_ENDPOINT || 'http://localhost:9222';

  targets = targets.slice(0, maxTargets);

  let browser;
  try {
    browser = await chromium.connectOverCDP(cdpEndpoint);
  } catch (e) {
    fail(outPath, 'cdp_unreachable',
      `无法连接 Chrome CDP (${cdpEndpoint})：${e.message}。请确认业务员本机 Chrome 已用 --remote-debugging-port=9222 启动。`);
  }

  const contexts = browser.contexts();
  const context = contexts[0] || (await browser.newContext());
  const page = context.pages()[0] || (await context.newPage());

  const results = [];
  let stoppedEarly = false;
  const summary = { total: targets.length, success: 0, screenedOut: 0, failed: 0, rateLimited: 0, stoppedEarly: false };

  for (const t of targets) {
    const r = {
      query: t.query || null,
      profileUrl: t.profileUrl || null,
      platformUserId: t.platformUserId || null,
      nickname: t.nickname || null,
      bio: null,
      followers: null,
      verdict: null,
      commercialMatches: [],
      personalMatches: [],
      postId: null,
      postUrl: null,
      commentedAt: null,
      status: 'PENDING',
      errorMessage: null,
    };

    try {
      // 1. 找到 profileUrl
      if (!r.profileUrl && r.query) {
        r.profileUrl = await findProfileFromSearch(page, r.query);
      }
      if (!r.profileUrl) {
        r.status = 'FAILED';
        r.errorMessage = 'profile_not_found';
        summary.failed++;
        results.push(r);
        continue;
      }

      await page.goto(r.profileUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
      await sleep(2500);

      // 2. 抓博主信息
      const info = await getProfileInfo(page);
      r.platformUserId = r.platformUserId || info.platformUserId;
      r.nickname = r.nickname || info.nickname;
      r.bio = info.bio;
      r.followers = info.followers;

      // 3. 反爬先检
      const rlProfile = detectRateLimit(info.pageText);
      if (rlProfile) {
        r.status = 'RATE_LIMITED';
        r.errorMessage = `反爬命中: ${rlProfile}`;
        summary.rateLimited++;
        results.push(r);
        if (stopOnRateLimit) { stoppedEarly = true; break; }
        continue;
      }

      // 4. 筛选
      if (enableScreening) {
        const screen = screenText((r.bio || '') + ' ' + (r.nickname || '') + ' ' + info.pageText);
        r.verdict = screen.verdict;
        r.commercialMatches = screen.commercialMatches;
        r.personalMatches = screen.personalMatches;
        if (screen.verdict === 'N') {
          r.status = 'SCREENED_OUT';
          summary.screenedOut++;
          results.push(r);
          continue;
        }
      }

      // 5. 进第一条笔记
      const postHref = await openFirstPost(page);
      if (!postHref) {
        r.status = 'FAILED';
        r.errorMessage = 'no_post_found';
        summary.failed++;
        results.push(r);
        continue;
      }
      r.postUrl = postHref;
      const pm = postHref.match(/\/(?:explore|discovery\/item)\/([^?#/]+)/);
      if (pm) r.postId = pm[1];

      // 6. 评论
      const ctx = { nickname: r.nickname || '', platformUserId: r.platformUserId || '' };
      const actualText = tmpl(commentText, ctx);
      const cr = await commentOnCurrentPost(page, actualText);
      r.commentedAt = new Date().toISOString();
      r.messageText = actualText;

      if (cr.ok) {
        r.status = 'SUCCESS';
        summary.success++;
      } else if (cr.reason === 'rate_limit') {
        r.status = 'RATE_LIMITED';
        r.errorMessage = `反爬命中: ${cr.signal}`;
        summary.rateLimited++;
        results.push(r);
        if (stopOnRateLimit) { stoppedEarly = true; break; }
        continue;
      } else {
        r.status = 'FAILED';
        r.errorMessage = cr.reason || 'comment_failed';
        summary.failed++;
      }

      results.push(r);
      await sleep(delayMs + Math.random() * 4000);
    } catch (e) {
      r.status = 'FAILED';
      r.errorMessage = `${e.name}: ${e.message}`.slice(0, 300);
      summary.failed++;
      results.push(r);
    }
  }

  summary.stoppedEarly = stoppedEarly;

  try { await browser.close(); } catch (_) {}

  writeOut(outPath, {
    ok: true,
    cdpEndpoint,
    summary,
    results,
  });
}

main().catch((e) => {
  const outPath = parseArgs()['output'];
  if (outPath) {
    fail(outPath, 'unhandled', `${e.name}: ${e.message}`, { stack: (e.stack || '').slice(0, 800) });
  } else {
    console.error(e);
    process.exit(1);
  }
});
