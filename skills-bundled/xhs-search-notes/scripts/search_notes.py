#!/usr/bin/env python3
"""xhs-search-notes 执行体：关键词搜索笔记清单，输出扁平 JSON。"""
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


def _int(v) -> int | None:
    if v is None:
        return None
    try:
        return int(str(v).replace(",", "").strip())
    except Exception:
        return None


def _map(item: dict) -> dict:
    card = item.get("note_card") or {}
    user = card.get("user") or {}
    interact = card.get("interact_info") or {}
    return {
        "platform": "xhs",
        "platformPostId": item.get("id") or card.get("note_id"),
        "title": card.get("display_title") or card.get("title"),
        "authorUserId": user.get("user_id"),
        "authorName": user.get("nick_name") or user.get("nickname"),
        "authorAvatar": user.get("avatar"),
        "likes": _int(interact.get("liked_count")),
        "comments": _int(interact.get("comment_count")),
        "collects": _int(interact.get("collected_count")),
        "shares": _int(interact.get("share_count")),
        "noteType": card.get("type"),
        "link": f"https://www.xiaohongshu.com/explore/{item.get('id', '')}",
    }


def run(args: argparse.Namespace) -> int:
    out_path = Path(args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cookies = os.environ.get("COOKIES", "").strip()
    if not cookies:
        _write(out_path, {"ok": False, "error": "环境变量 COOKIES 未设置", "errorType": "cookie_invalid"})
        return 2

    if not args.query or not args.query.strip():
        _write(out_path, {"ok": False, "error": "--query 必填", "errorType": "bad_input"})
        return 2

    try:
        _bootstrap()
        from apis.xhs_pc_apis import XHS_Apis  # type: ignore
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"Spider_XHS import 失败: {e}", "errorType": "bootstrap"})
        return 3

    require = max(1, min(args.require_num or 20, 200))
    api = XHS_Apis()
    try:
        ok, msg, raw_notes = api.search_some_note(
            args.query, require, cookies,
            sort_type_choice=args.sort or 0,
            note_type=args.type or 0,
            note_time=args.time or 0,
            note_range=0,
            pos_distance=0,
            geo="",
            proxies=None,
        )
        if not ok:
            low = str(msg).lower()
            kind = "cookie_invalid" if "login" in low or "cookie" in low else "unknown"
            _write(out_path, {"ok": False, "error": f"search_some_note: {msg}", "errorType": kind})
            return 4
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"API 异常: {e}", "errorType": "unknown",
                           "traceback": traceback.format_exc()})
        return 4

    posts = [_map(n) for n in (raw_notes or [])]
    _write(out_path, {"ok": True, "query": args.query, "total": len(posts), "posts": posts})
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--query", required=True)
    ap.add_argument("--require-num", dest="require_num", type=int, default=20)
    ap.add_argument("--sort", type=int, default=0)
    ap.add_argument("--type", type=int, default=0)
    ap.add_argument("--time", type=int, default=0)
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
