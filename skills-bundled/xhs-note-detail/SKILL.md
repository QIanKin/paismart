---
name: xhs-note-detail
description: 拉取单条小红书笔记的详情（标题/正文/封面/视频/标签/互动数）与一二级评论。用于爆款结构分析与对标参考。
version: "0.1.0"
homepage: "https://github.com/cv-cat/Spider_XHS"
metadata:
  bee:
    tags: [xhs, note, detail, comments]
    requires:
      bins: [python, python3, node]
      env: [COOKIES, SPIDER_XHS_HOME]
---

# xhs-note-detail

## 何时使用

- Agent 需要分析某条对标爆款笔记的结构（标题套路、开头 hook、关键词密度、评论口碑）
- `xhs-search-notes` 拿到 note_id 清单后，挑 Top 5 爆款展开做详情
- 内容初稿校验阶段（SOP Step5），拿竞品笔记做反例参考

## CLI

```bash
python scripts/note_detail.py \
    --note-url "https://www.xiaohongshu.com/explore/6abc...?xsec_token=..." \
    --with-comments \
    --output out.json
```

## 输出

```json
{
  "ok": true,
  "note": {
    "platformPostId": "6abc...",
    "title": "...",
    "contentText": "...",
    "likes": 1234,
    ...
  },
  "comments": [
    { "id": "...", "userName": "...", "content": "...", "likeCount": 10, "publishedAt": "...", "children": [...] }
  ]
}
```

- `--with-comments` 默认开（--no-with-comments 关）
- 评论树已展平两层（主评论 + 回复）
