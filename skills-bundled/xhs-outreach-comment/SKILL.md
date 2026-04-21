---
name: xhs-outreach-comment
description: 小红书批量评论外联。给一批博主（按关键词搜或直接给主页 URL），进他们的第一条笔记发一条评论；自动跳过商业号，记录每条触达到外联台账。用于 MCN SOP Step5「种子触达」。当用户说"给 xx 博主发评论"、"外联一批小红书博主"、"帮我触达这批 KOL"时使用。
version: "0.1.0"
metadata:
  bee:
    tags: [xhs, outreach, comment, mcn, browser-automation]
    requires:
      bins: [node]
      env: [CDP_ENDPOINT, NODE_PATH]
---

# xhs-outreach-comment

## 何时使用

- MCN SOP Step5「种子触达」：项目确定好一批种子博主后，走一遍评论触达
- Agent 收到"给这 20 个博主发评论：'您的内容很棒，我是 xxx 的小蜜蜂，方便细聊吗？'"
- 触达结束后，Java 侧会把每个 target 的结果写入 `outreach_records` 表

## 前置条件（重要！）

1. 业务员本机 Chrome 已用 `--remote-debugging-port=9222` 启动，且登录了小红书
2. 容器能访问 `CDP_ENDPOINT`（默认 `http://host.docker.internal:9222`）
3. 已装 Playwright npm 包（在 `_shared/playwright-runtime/node_modules` 下）

## 使用方式

```bash
node scripts/batch_comment.mjs \
    --targets "$TARGETS_JSON" \
    --comment-text "您的内容很棒，想约一下合作" \
    --max-targets 20 \
    --delay-ms 15000 \
    --enable-screening true \
    --output out.json
```

### 参数

| 参数 | 含义 | 默认 |
|---|---|---|
| `--targets` | target 列表 JSON：`[{query?, profileUrl?, platformUserId?, nickname?}, ...]` | 必填 |
| `--comment-text` | 评论文本（支持 `{nickname}` / `{platformUserId}` 占位符） | 必填 |
| `--max-targets` | 上限，多余的丢弃 | 20 |
| `--delay-ms` | 每条触达间隔毫秒（建议 >=15000） | 15000 |
| `--enable-screening` | 是否本地做"商业号 vs 真人"筛选，商业号跳过 | true |
| `--stop-on-rate-limit` | 命中反爬信号立刻停 | true |

### 输出 out.json

```json
{
  "ok": true,
  "summary": {
    "total": 20,
    "success": 12,
    "screenedOut": 3,
    "failed": 2,
    "rateLimited": 3,
    "stoppedEarly": true
  },
  "results": [
    {
      "query": "敏感肌粉丝",
      "profileUrl": "https://www.xiaohongshu.com/user/profile/xxx",
      "platformUserId": "xxx",
      "nickname": "…",
      "bio": "…",
      "followers": 12345,
      "verdict": "Y",
      "commercialMatches": [],
      "personalMatches": ["水瓶", "INTP"],
      "postId": "abc",
      "postUrl": "https://www.xiaohongshu.com/explore/abc",
      "commentedAt": "2026-04-17T18:20:00",
      "status": "SUCCESS",
      "errorMessage": null
    }
  ]
}
```

## 风险 / 成本

- 每条触达约 20~40 秒（含搜索 + 打开主页 + 进笔记 + 输入评论 + 提交）
- 触达频率：建议 `--delay-ms 15000` 以上；超过 30 条/小时有被限风险
- 反爬硬信号（"操作频繁"、"滑块"、"账号异常"）会立即停止并标记 `rateLimited`
- 不支持私信（合规风险高，后续走独立 skill）

## 来源

基于 openclaw 备份 `workspace/xiaohongshu-outreach-bridge/batch_comment_v2.mjs`，
简化了 SQLite 历史查重和 screenshot 环节（改由后端 `outreach_records` 表做查重），
保留了词库筛选和反爬信号识别，适配到 CDP 外接模式。
