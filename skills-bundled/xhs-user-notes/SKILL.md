---
name: xhs-user-notes
description: 抓取小红书某个博主（user_id）的全部或最近 N 条笔记，输出结构化 JSON，可直接喂给 creator_post_batch_upsert 入库。
version: "0.1.0"
homepage: "https://github.com/cv-cat/Spider_XHS"
metadata:
  bee:
    tags: [xhs, creator, sync, mcn]
    requires:
      bins: [python, python3, node]
      env: [COOKIES, SPIDER_XHS_HOME]
---

# xhs-user-notes

## 何时使用

- 博主库里已经知道某位小红书博主的 `homepage_url` 或 `platformUserId`（小红书 user_id），需要把其全部/最近笔记同步进 `creator_posts` 表
- 定时任务：每周刷新签约博主数据
- 或 agent 用户问 "把 @xxx 最近一个月的笔记拉下来分析爆款"

## 前置条件

1. 环境变量 `COOKIES` 必须是有效的小红书 PC 端登录 cookie（xhs_pc 平台池挑一个）
2. 环境变量 `SPIDER_XHS_HOME` 指向 `skills-bundled/_shared/Spider_XHS`
3. Python 依赖：`pip install -r $SPIDER_XHS_HOME/requirements.txt`
4. Node.js 20+（PyExecJS 调签名 JS 需要）

## 使用方式

### 协调 tool 入口（推荐）

在 Java Agent 里直接调 `xhs_refresh_creator(accountId, limit)`，它会自动：
1. 查 DB 拿 handle → 组 `homepage_url`
2. 从 XhsCookie 池挑一个 xhs_pc cookie
3. 跑本 skill 的脚本
4. 读 `out.json` → 调 `creator_post_batch_upsert` 落库
5. 成功/失败时回写 cookie 健康度

### 手工命令行

```bash
export COOKIES='a1=xxx;web_session=yyy;...'
export SPIDER_XHS_HOME=/abs/skills-bundled/_shared/Spider_XHS
python scripts/fetch_user_notes.py \
    --user-url "https://www.xiaohongshu.com/user/profile/5xxxxx" \
    --limit 20 \
    --output out.json
```

## 输出格式（out.json）

```json
{
  "ok": true,
  "userId": "5xxxxx",
  "homeUrl": "https://www.xiaohongshu.com/user/profile/5xxxxx",
  "fetched": 18,
  "posts": [
    {
      "platform": "xhs",
      "platformPostId": "6abc...",
      "title": "笔记标题",
      "contentText": "desc...",
      "coverUrl": "https://...",
      "link": "https://www.xiaohongshu.com/explore/6abc...?xsec_token=...",
      "type": "video",
      "publishedAt": "2026-04-01T12:34:56",
      "likes": 1234,
      "comments": 56,
      "collects": 78,
      "shares": 9,
      "hashtags": ["护肤", "测评"],
      "videoUrl": "https://...",
      "imageUrls": ["https://..."]
    }
  ]
}
```

失败时：

```json
{ "ok": false, "error": "...人类可读原因...", "errorType": "cookie_invalid | network | not_found | ..." }
```

## 失败处理

- `cookie_invalid` → 调用方必须调 `XhsCookieService.reportFailure(id, ...)` 降权；失败 5 次后 cookie 自动标为 EXPIRED
- `not_found` → 博主主页可能被删了，调用方应该把 `CreatorAccount.status` 标成 INACTIVE
- `network` → 重试即可

## 数据回流字段映射

脚本输出直接符合 `creator_post_batch_upsert` 的 schema。字段映射关系：

| skill 输出 | 数据库 `creator_posts` 字段 |
|---|---|
| `platformPostId` | `platform_post_id` (unique key 的一半) |
| `title` | `title` |
| `contentText` | `content_text` |
| `coverUrl` | `cover_url` |
| `link` | `link` |
| `type` | `type` (normal=图集, video=视频) |
| `publishedAt` | `published_at` |
| `likes/comments/collects/shares` | `likes/comments/collects/shares` |
| `hashtags` | `hashtags_json` (序列化为 JSON 数组) |

## 安全约束

- 本 skill **只读**：不做任何修改/删除/发布
- sandbox 沙箱目录：`var/agent-sandbox/session-<id>/`，skill 产出的 `out.json` 和日志都落在这里
- 超时默认 90 秒（受 `skills.bash.timeout-seconds` 覆盖，用户可调）
- 禁用词清单（BashExecutor 层统一管）已覆盖 `rm/sudo/chmod` 等

## 相关方法论

- 笔记抓下来之后怎么解读、怎么给 PM 出 brief，见参考型 skill: [xhs-note-methodology](../xhs-note-methodology/SKILL.md)
