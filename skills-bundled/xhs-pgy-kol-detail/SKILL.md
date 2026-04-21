---
name: xhs-pgy-kol-detail
description: 通过蒲公英平台拉取单个 KOL 博主的详细画像（粉丝画像/历史趋势/笔记表现），用于博主详情页与合作决策。
version: "0.1.0"
homepage: "https://github.com/cv-cat/Spider_XHS"
metadata:
  bee:
    tags: [xhs, pgy, kol, profile]
    requires:
      bins: [python, python3, node]
      env: [COOKIES, SPIDER_XHS_HOME]
---

# xhs-pgy-kol-detail

## CLI

```bash
python scripts/pgy_kol_detail.py --user-id 5xxxx --output out.json
```

## 输出

```json
{
  "ok": true,
  "userId": "5xxxx",
  "summary": { ... dataSummary 原始 ... },
  "fansPortrait": { ... fansSummary 原始 ... },
  "fansHistory": [ ... 粉丝增长曲线 ... ],
  "notesRate": { ... 笔记表现 ... },
  "snapshot": {
    "followers": 123456,
    "engagementRate": 0.0234,
    "avgLikes": 3000
  }
}
```

字段 `snapshot` 直接符合 `CreatorSnapshot` 入库 shape。
