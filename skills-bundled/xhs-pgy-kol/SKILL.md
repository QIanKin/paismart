---
name: xhs-pgy-kol
description: 通过蒲公英平台搜索 KOL 博主列表（含粉丝量/赛道/报价），用于 MCN SOP Step3 流量筛选与新博主发掘。
version: "0.1.0"
homepage: "https://github.com/cv-cat/Spider_XHS"
metadata:
  bee:
    tags: [xhs, pgy, kol, discover, mcn]
    requires:
      bins: [python, python3, node]
      env: [COOKIES, SPIDER_XHS_HOME]
---

# xhs-pgy-kol

## 何时使用

- 发掘新博主：品牌/产品词 → 按赛道拉 KOL 清单 → 入库 + 人工/AI 筛选
- 定期扫描：给某赛道 KOL 建基线库，后续用 `xhs-pgy-kol-detail` 取单博主画像
- SOP Step3「流量筛选 & 优先级排序」里的初筛数据源

## 前置

- COOKIES 必须是 **蒲公英平台 cookie**（`xhs_pgy` 池）
- 蒲公英需要品牌方账号登录，普通创作者 cookie 无权限
- Agent 选 cookie 时必须显式指定 `platform="xhs_pgy"`

## CLI

```bash
python scripts/fetch_pgy_kol.py --num 50 --output out.json
```

参数：

| 参数 | 含义 |
|---|---|
| `--num` | 需要的博主数，上限 200 |
| `--content-tag` | 可选；蒲公英类目 ID（留空=不限）|
| `--output` | 输出路径 |

## 输出

```json
{
  "ok": true,
  "total": 50,
  "accounts": [
    {
      "platform": "xhs",
      "platformUserId": "5xxxxx",
      "handle": "nickname",
      "displayName": "...",
      "avatarUrl": "https://...",
      "followers": 123456,
      "engagementRate": 0.0234,
      "categoryMain": "美妆",
      "categorySub": "护肤",
      "priceNote": "图文 2000 / 视频 5000",
      "region": "上海",
      "verified": true,
      "raw": { ... 保留蒲公英原始 JSON ... }
    }
  ]
}
```

输出 shape 对齐 `creator_upsert` tool 的入参，agent 可批量调 `creator_upsert` 入库（或未来增加 `creator_account_batch_upsert`）。
