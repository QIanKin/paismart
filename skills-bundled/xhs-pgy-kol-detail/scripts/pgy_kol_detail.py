#!/usr/bin/env python3
"""xhs-pgy-kol-detail：拉单个蒲公英博主的 summary + 粉丝画像 + 历史趋势。

诊断策略：
1. 先 get_self_info 作"活体检测" —— 通 → cookie 一定是活的；
2. 然后逐个调 4 个详情接口，每次都记录 HTTP status + body 片段；
3. 只有当 self_info 也挂了（真 401 / 登录页 / 403）才判 cookie_invalid，
   否则归类到 signature_failed / blocked / api_changed，不污染 cookie 状态。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any


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


def _call(api_obj, method_name: str, *a) -> tuple[Any, str | None]:
    """统一调 PuGongYingAPI 的某方法，返回 (data, error_detail)。

    error_detail 非 None 时表示调用失败；SDK 里的 .json() 解析异常都会被这里收集。
    """
    try:
        return api_obj.__getattribute__(method_name)(*a), None
    except json.JSONDecodeError as e:
        return None, f"json_parse: {e}"
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def _classify_self_info(info: Any) -> str | None:
    """判断 get_self_info 返回的结果是否说明 cookie 真失效。

    返回值：None=cookie 活着；字符串=cookie 失效原因。
    """
    if info is None:
        return "self_info_none"
    if not isinstance(info, dict):
        return f"self_info_type:{type(info).__name__}"
    code = info.get("code") or info.get("result")
    msg = str(info.get("msg") or info.get("message") or "")
    data = info.get("data") or {}
    if isinstance(data, dict) and (data.get("userId") or data.get("user_id")):
        return None
    if code in (401, 403, "401", "403", "NEED_LOGIN", "UNAUTHORIZED", "NOT_LOGIN"):
        return f"self_info_auth_denied code={code} msg={msg}"
    if "login" in msg.lower() or "登录" in msg:
        return f"self_info_needs_login msg={msg}"
    return f"self_info_no_user code={code} msg={msg}"


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

    # --- 1) 活体检测：get_self_info 只用普通 cookie，不走 x-s 签名 ---
    self_info, self_err = _call(api, "get_self_info", cookies)
    cookie_diag = _classify_self_info(self_info) if self_err is None else f"self_info_call: {self_err}"
    cookie_alive = cookie_diag is None

    # --- 2) 跑 4 个详情接口，收集每个的报错（这些走 x-s 签名，容易挂在 JS 引擎/签名） ---
    errors: dict[str, str] = {}
    summary, fans_portrait, fans_history, notes_rate = {}, {}, {}, {}

    summary, e = _call(api, "get_user_detail", args.user_id, cookies)
    if e: errors["summary"] = e
    summary = summary or {}

    fans_portrait, e = _call(api, "get_user_fans_detail", args.user_id, cookies)
    if e: errors["fansPortrait"] = e
    fans_portrait = fans_portrait or {}

    fans_history, e = _call(api, "get_user_fans_history", args.user_id, cookies)
    if e: errors["fansHistory"] = e
    fans_history = fans_history or {}

    notes_rate, e = _call(api, "get_user_notes_detail", args.user_id, cookies)
    if e: errors["notesRate"] = e
    notes_rate = notes_rate or {}

    all_detail_failed = (not summary) and (not fans_portrait) and (not fans_history) and (not notes_rate)

    if all_detail_failed:
        # 全挂了 —— 根据 cookie_alive 做正确分类，避免冤杀 cookie
        if cookie_alive:
            # cookie 是活的，说明挂在签名/反爬/接口变更上，跟 cookie 无关
            reason_hint = None
            for msg in errors.values():
                if "json_parse" in msg:
                    reason_hint = "blocked_or_api_changed"
                    break
            error_type = reason_hint or "signature_failed"
            friendly = "蒲公英详情接口全部失败，但 cookie 是活的（get_self_info 通过）。" \
                       "最可能：x-s 签名 JS 过期 / 接口路径变更 / 被风控临时拦截。"
        else:
            error_type = "cookie_invalid"
            friendly = f"蒲公英所有接口都失败，且 cookie 活体检测失败：{cookie_diag}"
        _write(out_path, {
            "ok": False,
            "error": friendly,
            "errorType": error_type,
            "cookieAlive": cookie_alive,
            "cookieDiag": cookie_diag,
            "partialErrors": errors,
            "selfInfo": self_info if isinstance(self_info, (dict, list)) else None,
        })
        return 4

    # --- 3) 至少一个成功：走成功路径 ---
    payload = {
        "ok": True,
        "userId": args.user_id,
        "summary": summary,
        "fansPortrait": fans_portrait,
        "fansHistory": fans_history,
        "notesRate": notes_rate,
        "snapshot": _snapshot(summary, notes_rate),
        "cookieAlive": cookie_alive,
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
