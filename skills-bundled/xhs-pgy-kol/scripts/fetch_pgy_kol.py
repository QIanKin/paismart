#!/usr/bin/env python3
"""xhs-pgy-kol：从小红书"蒲公英"接单后台拉取 KOL 列表。

底层走 ``PuGongYingAPI.get_user_by_page`` （``POST /api/solar/cooperator/blogger/v2``），
它支持 page/pageSize + 粉丝区间 + 性别 + contentTag + 地区 + 特征标签 等过滤。

与上一版差异：
- 放弃 ``get_some_user(num=…)`` 的粗糙抓取，改真正意义的分页；
- 参数和 Java tool ``XhsFetchPgyKolTool`` 对齐（--page / --page-size / --keyword /
  --followers-min / --followers-max / --gender / --content-tag）；
- 输出里保留 ``priceInfoList``、``priceNote`` —— 上游 agent 想看报价能看到。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any, Iterable


def _bootstrap() -> None:
    home = os.environ.get("SPIDER_XHS_HOME")
    if not home:
        raise SystemExit("SPIDER_XHS_HOME 未设置")
    p = Path(home).resolve()
    if not p.is_dir():
        raise SystemExit(f"SPIDER_XHS_HOME 不存在: {home}")
    sys.path.insert(0, str(p))
    os.chdir(str(p))


def _write(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _int(v):
    try:
        return int(str(v).replace(",", "").strip()) if v is not None else None
    except Exception:
        return None


def _float(v):
    try:
        return float(str(v).strip()) if v is not None else None
    except Exception:
        return None


def _price_note(raw: dict) -> str | None:
    """从 KOL item 的 priceInfoList / price / priceInfo 里揉出人类可读的报价字符串。

    蒲公英的 priceInfoList 常见 shape::

        [
          {"noteType": "图文", "price": 800, "fee": 800},
          {"noteType": "视频", "price": 1500, ...}
        ]
    """
    price_info = raw.get("priceInfoList") or raw.get("price") or raw.get("priceInfo")
    if not price_info:
        return None
    try:
        if isinstance(price_info, list):
            parts: list[str] = []
            for p in price_info:
                if not isinstance(p, dict):
                    continue
                t = p.get("noteType") or p.get("typeName") or p.get("type")
                price = p.get("price") if p.get("price") is not None else p.get("fee")
                if t and price is not None:
                    parts.append(f"{t}: {price}")
            if parts:
                return " / ".join(parts)
        return json.dumps(price_info, ensure_ascii=False)
    except Exception:
        return str(price_info)


def _map(raw: dict) -> dict:
    """把蒲公英 v2 接口的 KOL item 映射到 creator_account schema。"""
    uid = str(raw.get("userId") or raw.get("id") or "")
    followers = _int(raw.get("fansNum") or raw.get("fans") or raw.get("followers"))
    return {
        "platform": "xhs",
        "platformUserId": uid,
        "handle": raw.get("nickName") or raw.get("nickname"),
        "displayName": raw.get("nickName") or raw.get("name"),
        "avatarUrl": raw.get("avatar") or raw.get("headImg"),
        "bio": raw.get("desc") or raw.get("sign"),
        "followers": followers,
        "avgLikes": _int(raw.get("avgLikes") or raw.get("averageLikes")),
        "avgComments": _int(raw.get("avgComments") or raw.get("averageComments")),
        "engagementRate": _float(raw.get("engagementRate") or raw.get("interactionRate")),
        "categoryMain": raw.get("taxonomy1Tag") or raw.get("industry"),
        "categorySub": raw.get("taxonomy2Tag") or raw.get("subIndustry"),
        "region": raw.get("location") or raw.get("city"),
        "verified": bool(raw.get("authInfo") or raw.get("verified")),
        "homepageUrl": f"https://www.xiaohongshu.com/user/profile/{uid}" if uid else None,
        "priceNote": _price_note(raw),
        "priceInfoList": raw.get("priceInfoList"),
        "raw": raw,
    }


def _apply_filters(
    data: dict,
    keyword: str | None,
    followers_min: int | None,
    followers_max: int | None,
    gender: int | None,
    content_tag: Any | None,
) -> None:
    """把 CLI 过滤条件塞到蒲公英 bozhu_data 请求体里。

    ``get_user_by_page`` 内部会先经过 ``get_pugongying_bozhu_data`` 拼 body，但那个 helper
    并不接受所有可用字段，我们在这里做"后修正"：直接改 body 里的可选 key。
    """
    if followers_min is not None:
        data["fansNumberLower"] = followers_min
    if followers_max is not None:
        data["fansNumberUpper"] = followers_max
    if gender is not None:
        # 蒲公英：0=全部 1=女 2=男
        data["gender"] = gender
    if keyword:
        # v2 接口没有专门的关键词字段；把它塞到"特征标签"里做宽匹配
        tags = data.get("featureTags") or []
        if isinstance(tags, list):
            tags.append(keyword)
        else:
            tags = [keyword]
        data["featureTags"] = tags
    if content_tag:
        data["contentTag"] = content_tag


def run(args: argparse.Namespace) -> int:
    out_path = Path(args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cookies_str = os.environ.get("COOKIES", "").strip()
    if not cookies_str:
        _write(out_path, {"ok": False, "error": "COOKIES 未设置", "errorType": "cookie_invalid"})
        return 2

    try:
        _bootstrap()
        from apis.xhs_pugongying_apis import PuGongYingAPI  # type: ignore
        from xhs_utils.cookie_util import trans_cookies  # type: ignore
        from xhs_utils.xhs_pugongying_util import (  # type: ignore
            generate_pugongying_headers,
            get_pugongying_bozhu_data,
        )
        import requests  # type: ignore
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"Spider_XHS import 失败: {e}", "errorType": "bootstrap"})
        return 3

    try:
        cookies = trans_cookies(cookies_str)
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"cookie 解析失败: {e}", "errorType": "cookie_invalid"})
        return 2

    api = PuGongYingAPI()
    page = max(1, args.page or 1)
    page_size = max(1, min(args.page_size or 20, 50))

    try:
        # 复用官方 get_user_by_page 的鉴权链路，但自己接管请求 body：
        # 1) 拿 brandUserId
        self_info = api.get_self_info(cookies)
        brand_user_id = self_info["data"]["userId"]
        # 2) 拼 body 并覆盖分页 & 过滤
        body = get_pugongying_bozhu_data(page, brand_user_id, args.content_tag)
        body["pageSize"] = page_size
        _apply_filters(
            body,
            args.keyword,
            args.followers_min,
            args.followers_max,
            args.gender,
            args.content_tag,
        )
        # 3) 走 track → 拿 trackId → 再真正请求列表
        track_resp = api.get_track(body, cookies)
        track_id = (((track_resp or {}).get("data") or {}).get("trackId")) or ""
        body["trackId"] = track_id
        endpoint = "/api/solar/cooperator/blogger/v2"
        payload = json.dumps(body, separators=(",", ":"), ensure_ascii=False)
        headers = generate_pugongying_headers(cookies["a1"], endpoint, payload)
        resp = requests.post(
            api.base_url + endpoint,
            headers=headers,
            cookies=cookies,
            data=payload,
            timeout=20,
        )
        res_json = resp.json()
        if resp.status_code != 200 or not isinstance(res_json, dict):
            _write(out_path, {"ok": False, "error": f"蒲公英 HTTP={resp.status_code} body={resp.text[:300]}",
                              "errorType": "remote"})
            return 4
        data = res_json.get("data") or {}
        user_list = data.get("kols") or []
        total = data.get("total") or len(user_list)
    except Exception as e:
        low = str(e).lower()
        kind = "cookie_invalid" if ("login" in low or "401" in low or "cookie" in low) else "unknown"
        _write(out_path, {"ok": False, "error": f"蒲公英 API 异常: {e}",
                          "errorType": kind, "traceback": traceback.format_exc()})
        return 4

    kols = [_map(u) for u in user_list]
    _write(out_path, {
        "ok": True,
        "page": page,
        "pageSize": page_size,
        "total": total,
        "kols": kols,
        # 同时用旧 key 暴露一次，兼容上一版消费者
        "accounts": kols,
    })
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    # 对齐 XhsFetchPgyKolTool 透传的参数
    ap.add_argument("--page", type=int, default=1)
    ap.add_argument("--page-size", type=int, default=20, dest="page_size")
    ap.add_argument("--keyword", default=None)
    ap.add_argument("--followers-min", type=int, default=None, dest="followers_min")
    ap.add_argument("--followers-max", type=int, default=None, dest="followers_max")
    ap.add_argument("--gender", type=int, default=None, help="0=全部 1=女 2=男")
    ap.add_argument("--content-tag", default=None, dest="content_tag",
                    help="可选：类目编码（JSON 字符串或简单值），详见蒲公英 taxonomy")
    ap.add_argument("--output", required=True)
    # 兼容旧调用：--num（老签名，以前给 get_some_user 用）
    ap.add_argument("--num", type=int, default=None)
    args = ap.parse_args()
    if args.num and not args.page_size:
        args.page_size = args.num
    try:
        return run(args)
    except SystemExit:
        raise
    except Exception as e:
        out_path = Path(args.output).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        _write(out_path, {"ok": False, "error": f"未捕获异常: {e}",
                          "errorType": "unknown", "traceback": traceback.format_exc()})
        return 5


if __name__ == "__main__":
    sys.exit(main())
