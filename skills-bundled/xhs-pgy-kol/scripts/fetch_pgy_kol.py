#!/usr/bin/env python3
"""xhs-pgy-kol：蒲公英拉 KOL 列表。"""
from __future__ import annotations

import argparse
import json
import os
import sys
import traceback
from pathlib import Path


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
        "raw": raw,
    }


def _price_note(raw: dict) -> str | None:
    price_info = raw.get("priceInfoList") or raw.get("price") or raw.get("priceInfo")
    if not price_info:
        return None
    try:
        parts: list[str] = []
        if isinstance(price_info, list):
            for p in price_info:
                t = p.get("noteType") or p.get("typeName") or p.get("type")
                price = p.get("price") or p.get("fee")
                if t and price is not None:
                    parts.append(f"{t}: {price}")
        return " / ".join(parts) or json.dumps(price_info, ensure_ascii=False)
    except Exception:
        return str(price_info)


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
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"Spider_XHS import 失败: {e}", "errorType": "bootstrap"})
        return 3

    try:
        cookies = trans_cookies(cookies_str)
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"cookie 解析失败: {e}", "errorType": "cookie_invalid"})
        return 2

    api = PuGongYingAPI()
    try:
        num = max(1, min(args.num or 20, 200))
        user_list = api.get_some_user(num, cookies, contentTag=args.content_tag)
    except Exception as e:
        low = str(e).lower()
        kind = "cookie_invalid" if "login" in low or "401" in low or "cookie" in low else "unknown"
        _write(out_path, {"ok": False, "error": f"蒲公英 API 异常: {e}",
                           "errorType": kind, "traceback": traceback.format_exc()})
        return 4

    accounts = [_map(u) for u in (user_list or [])]
    _write(out_path, {"ok": True, "total": len(accounts), "accounts": accounts})
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--num", type=int, default=20)
    ap.add_argument("--content-tag", dest="content_tag", default=None)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()
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
