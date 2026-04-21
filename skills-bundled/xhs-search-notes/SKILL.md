---
name: xhs-search-notes
description: 按关键词搜索小红书笔记，支持排序（综合/热门/最新）、类型（视频/图集）、时间窗，用于 MCN SOP Step2 爆款库生成。
version: "0.1.0"
homepage: "https://github.com/cv-cat/Spider_XHS"
metadata:
  bee:
    tags: [xhs, search, hit-notes, mcn]
    requires:
      bins: [python, python3, node]
      env: [COOKIES, SPIDER_XHS_HOME]
---

# xhs-search-notes

## 何时使用

- MCN SOP Step2「爆款共识对齐 & 选题库生成」：给定品牌/产品关键词，拉一批高互动笔记，LLM 分析爆款结构
- Agent 用户问 "小红书上 '敏感肌面霜' 最近一周的爆款都有哪些？"
- 定时任务：每天给某赛道关键词做热度榜单，写入项目日报

## 前置条件

同 xhs-user-notes：需要有效 `COOKIES` + `SPIDER_XHS_HOME`。

## 使用方式

### CLI

```bash
python scripts/search_notes.py \
    --query "敏感肌面霜" \
    --require-num 40 \
    --sort 1 \
    --type 0 \
    --time 0 \
    --output out.json
```

参数：

| 参数 | 含义 | 可选值 |
|---|---|---|
| `--query` | 关键词（必填） | |
| `--require-num` | 需要的笔记数（自动分页直到凑够），上限 200 | 默认 20 |
| `--sort` | 排序 | 0 综合 / 1 最新 / 2 热门（点赞）/ 3 热门（评论）/ 4 热门（收藏） |
| `--type` | 笔记类型 | 0 不限 / 1 视频 / 2 图文 |
| `--time` | 时间范围 | 0 不限 / 1 一天 / 2 一周 / 3 半年 |
| `--output` | 输出路径 | 必填 |

### 输出

```json
{
  "ok": true,
  "query": "敏感肌面霜",
  "total": 40,
  "posts": [
    {
      "platform": "xhs",
      "platformPostId": "6abc...",
      "title": "...",
      "authorUserId": "5xxxxx",
      "authorName": "...",
      "likes": 1234,
      "comments": 56,
      "collects": 78,
      "publishedAt": "2026-03-20T10:00:00",
      "link": "https://www.xiaohongshu.com/explore/..."
    }
  ]
}
```

## 下游

- 这个 skill **只产出搜索清单**（含 note_id），不拉详情。如需详情/评论，agent 再用 `xhs-note-detail` 对单条展开
- 爆款共识建模：agent 拿到清单后用 LLM 抽取「爆款结构标签」（开头类型、高潮位置、结尾 CTA 等），写入内部 `HitStructure` 字典（Phase 4A 未来工作）

## 成本

- 平均 10 条笔记 ≈ 1 个 API call + 若干签名计算
- `--require-num 40` 通常 5-8 秒
