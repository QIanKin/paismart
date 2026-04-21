#!/usr/bin/env python3
"""xhs-note-detail：拉单条笔记详情 + 评论树。"""
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


def _shape_note(handled: dict) -> dict:
    return {
        "platformPostId": handled.get("note_id"),
        "link": handled.get("note_url"),
        "type": "video" if handled.get("note_type") == "视频" else "image",
        "title": handled.get("title"),
        "contentText": handled.get("desc"),
        "authorUserId": handled.get("user_id"),
        "authorName": handled.get("nickname"),
        "likes": _int(handled.get("liked_count")),
        "comments": _int(handled.get("comment_count")),
        "collects": _int(handled.get("collected_count")),
        "shares": _int(handled.get("share_count")),
        "coverUrl": handled.get("video_cover") or (handled.get("image_list") or [None])[0],
        "videoUrl": handled.get("video_addr"),
        "imageUrls": handled.get("image_list") or [],
        "tags": handled.get("tags") or [],
        "publishedAt": handled.get("upload_time"),
        "ipLocation": handled.get("ip_location"),
    }


def _shape_comment(c: dict) -> dict:
    return {
        "id": c.get("comment_id") or c.get("id"),
        "userName": c.get("nickname") or (c.get("user_info") or {}).get("nickname"),
        "userId": c.get("user_id") or (c.get("user_info") or {}).get("user_id"),
        "content": c.get("content"),
        "likeCount": _int(c.get("like_count")),
        "publishedAt": c.get("upload_time") or c.get("create_time"),
        "ipLocation": c.get("ip_location"),
    }


def run(args: argparse.Namespace) -> int:
    out_path = Path(args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cookies = os.environ.get("COOKIES", "").strip()
    if not cookies:
        _write(out_path, {"ok": False, "error": "COOKIES 未设置", "errorType": "cookie_invalid"})
        return 2
    if not args.note_url:
        _write(out_path, {"ok": False, "error": "--note-url 必填", "errorType": "bad_input"})
        return 2

    try:
        _bootstrap()
        from apis.xhs_pc_apis import XHS_Apis  # type: ignore
        from xhs_utils.data_util import handle_note_info, handle_comment_info  # type: ignore
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"Spider_XHS import 失败: {e}", "errorType": "bootstrap"})
        return 3

    api = XHS_Apis()
    try:
        ok, msg, raw = api.get_note_info(args.note_url, cookies, None)
        if not ok:
            _write(out_path, {"ok": False, "error": f"get_note_info: {msg}",
                               "errorType": "cookie_invalid" if "login" in str(msg).lower() else "unknown"})
            return 4
        note_item = raw["data"]["items"][0]
        note_item["url"] = args.note_url
        handled = handle_note_info(note_item)
        note = _shape_note(handled)
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"详情异常: {e}", "errorType": "unknown",
                           "traceback": traceback.format_exc()})
        return 4

    comments: list[dict] = []
    if args.with_comments:
        try:
            ok2, msg2, comment_list = api.get_note_all_comment(args.note_url, cookies, None)
            if ok2 and comment_list:
                note_id = note["platformPostId"]
                for raw_c in comment_list:
                    raw_c["note_id"] = note_id
                    raw_c["note_url"] = note["link"]
                    try:
                        c = handle_comment_info(raw_c)
                    except Exception:
                        c = raw_c
                    shaped = _shape_comment(c)
                    shaped["children"] = [
                        _shape_comment(sub) for sub in (raw_c.get("sub_comments") or [])
                    ]
                    comments.append(shaped)
        except Exception as e:
            print(f"[warn] 取评论失败: {e}", file=sys.stderr)

    _write(out_path, {"ok": True, "note": note, "comments": comments})
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--note-url", dest="note_url", required=True)
    ap.add_argument("--with-comments", dest="with_comments", action="store_true", default=True)
    ap.add_argument("--no-with-comments", dest="with_comments", action="store_false")
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
