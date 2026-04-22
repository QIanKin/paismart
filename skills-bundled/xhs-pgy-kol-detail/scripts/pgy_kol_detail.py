#!/usr/bin/env python3
"""xhs-pgy-kol-detail：拉单个蒲公英博主的 summary + 粉丝画像 + 历史趋势。"""
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


def _price_note(price_info) -> str | None:
    """把蒲公英 priceInfoList 转成人类可读的 '图文: 800 / 视频: 1500'。"""
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


def _snapshot(summary: dict, notes_rate: dict) -> dict:
    """从 dataSummary 和 notesRate 里抠出几个关键指标当 snapshot。含报价。"""
    data = (summary or {}).get("data") or {}
    nr = (notes_rate or {}).get("data") or {}
    price_info = data.get("priceInfoList") or data.get("price") or data.get("priceInfo")
    return {
        "followers": _int(data.get("fansNum") or data.get("fans")),
        "engagementRate": _float(data.get("interactionRate") or data.get("engagementRate")),
        "avgLikes": _int(nr.get("averageLikes") or nr.get("avgLikes")),
        "avgComments": _int(nr.get("averageComments") or nr.get("avgComments")),
        "avgCollects": _int(nr.get("averageCollects") or nr.get("avgCollects")),
        "avgShares": _int(nr.get("averageShares") or nr.get("avgShares")),
        "avgViews": _int(nr.get("averageViews") or nr.get("avgViews")),
        "hitRatio": _float(nr.get("hitRatio") or nr.get("popularRate")),
        "priceNote": _price_note(price_info),
        "priceInfoList": price_info,
    }


def run(args: argparse.Namespace) -> int:
    out_path = Path(args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cookies_str = os.environ.get("COOKIES", "").strip()
    if not cookies_str:
        _write(out_path, {"ok": False, "error": "COOKIES 未设置", "errorType": "cookie_invalid"})
        return 2
    if not args.user_id:
        _write(out_path, {"ok": False, "error": "--user-id 必填", "errorType": "bad_input"})
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
    errors: dict[str, str] = {}
    summary = {}
    fans_portrait = {}
    fans_history = {}
    notes_rate = {}
    try:
        summary = api.get_user_detail(args.user_id, cookies) or {}
    except Exception as e:
        errors["summary"] = str(e)
    try:
        fans_portrait = api.get_user_fans_detail(args.user_id, cookies) or {}
    except Exception as e:
        errors["fansPortrait"] = str(e)
    try:
        fans_history = api.get_user_fans_history(args.user_id, cookies) or {}
    except Exception as e:
        errors["fansHistory"] = str(e)
    try:
        notes_rate = api.get_user_notes_detail(args.user_id, cookies) or {}
    except Exception as e:
        errors["notesRate"] = str(e)

    if not summary and errors:
        _write(out_path, {"ok": False, "error": "蒲公英所有接口都失败",
                           "errorType": "cookie_invalid", "partialErrors": errors})
        return 4

    payload = {
        "ok": True,
        "userId": args.user_id,
        "summary": summary,
        "fansPortrait": fans_portrait,
        "fansHistory": fans_history,
        "notesRate": notes_rate,
        "snapshot": _snapshot(summary, notes_rate),
    }
    if errors:
        payload["partialErrors"] = errors
    _write(out_path, payload)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--user-id", dest="user_id", required=True)
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
