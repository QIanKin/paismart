---
name: qiangua-brand-discover
description: 在千瓜（qian-gua.com）上按品牌名发现相关达人。通过业务员本机登录态拉取品牌详情页、相关达人列表，结构化输出达人数据。用于 MCN SOP Step1「品牌对标发现」。当用户说"在千瓜上搜 xx 品牌的达人"、"查一下品牌 yy 都合作过哪些博主"、"给 zz 做个对标品牌达人列表"时使用。
version: "0.1.0"
homepage: "https://www.qian-gua.com/"
metadata:
  bee:
    tags: [xhs, qiangua, brand-discover, kol-discovery, mcn, browser-automation]
    requires:
      bins: [node]
      env: [CDP_ENDPOINT, NODE_PATH]
---

# qiangua-brand-discover

## 何时使用

- MCN SOP Step1「品牌对标发现」：给定品牌名（通常是友商 / 竞品 / 合作品牌），看他们最近合作的达人
- Agent 收到"Dior 最近投的小红书博主都有谁"
- 项目 kickoff：一键拉 10 个对标品牌的达人全集，汇合做种子库

## 前置条件

1. 业务员本机 Chrome 以 `--remote-debugging-port=9222` 启动
2. 业务员已登录 [https://www.qian-gua.com](https://www.qian-gua.com)（需要有会员账号）
3. 千瓜的产品 UI/API 会随时更新，如抽取不到字段请联系运营更新 selector

## 使用方式

```bash
node scripts/search_qiangua.mjs \
    --brand-name "Dior" \
    --max-kols 30 \
    --output out.json
```

### 参数

| 参数 | 含义 | 默认 |
|---|---|---|
| `--brand-name` | 品牌名（中英皆可） | 必填 |
| `--max-kols` | 期望拿到的达人数 | 30 |
| `--timeout-ms` | 单步网络超时（毫秒） | 30000 |
| `--include-page-text` | out.json 里是否附带整页文本供 LLM 兜底分析 | true |

### 输出 out.json

```json
{
  "ok": true,
  "brandName": "Dior",
  "sourceUrl": "https://app.qian-gua.com/#/brand/brandDetail?brandName=dior",
  "kols": [
    {
      "nickname": "…",
      "xhsUserId": "…",
      "profileUrl": "https://www.xiaohongshu.com/user/profile/…",
      "followers": 123456,
      "category": "美妆",
      "engagementRate": 0.043,
      "avgLikes": 2345,
      "avgComments": 56,
      "cooperationScore": null
    }
  ],
  "pageText": "…",
  "warnings": ["selector v3 失效，只抽到 12/30"]
}
```

## 下游

- 把 `kols[]` 往 `creator_post_batch_upsert` 灌入，成为项目的候选池
- 交给 `creator_screen` 做真人 vs 商业号筛选
- 用 `xhs_search_notes` 拉他们近期爆款验证内容调性

## 来源

基于 openclaw 备份 `workspace/xiaohongshu-outreach-bridge/search_qiangua.mjs` 扩展：
加了参数化、结构化抽取（best-effort）、out.json 约定、页面文本 fallback。
