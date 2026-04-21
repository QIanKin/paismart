#!/usr/bin/env python3
"""xhs-user-notes 执行体。

契约：
  --user-url | --user-id   二选一
  --limit N                仅取最近 N 条（默认 20；0 = 全部）
  --output out.json        输出路径（必填）
  COOKIES env              cookie 明文
  SPIDER_XHS_HOME env      指向 vendored Spider_XHS

正常退出 0 并写 out.json；异常退出非 0 且 out.json 内含 {"ok":false,"error":...}。
日志走 stderr。
"""
from __future__ import annotations

import argparse
import json
import os
import re
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
    # Spider_XHS 的 apis/*.py 用相对路径读 static/*.js（xhs_main/xhs_xray 等签名 JS）
    # 因此必须把 cwd 切到 SPIDER_XHS_HOME，而不是 BashExecutor 分配的 sandbox 子目录。
    os.chdir(str(p))


def _write(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _extract_user_id(user_url: str | None, user_id: str | None) -> tuple[str, str]:
    if user_id:
        user_id = user_id.strip()
        return user_id, f"https://www.xiaohongshu.com/user/profile/{user_id}"
    if not user_url:
        raise ValueError("必须提供 --user-url 或 --user-id")
    m = re.search(r"/user/profile/([A-Za-z0-9]+)", user_url)
    if not m:
        raise ValueError(f"无法从 URL 提取 user_id: {user_url}")
    return m.group(1), user_url


def _map_note(info: dict) -> dict:
    """data_util.handle_note_info 的 dict 映射到 creator_post_batch_upsert 需要的 shape。"""
    note_id = info.get("note_id")
    note_type_cn = info.get("note_type")
    ntype = "video" if note_type_cn == "视频" else "image"
    upload_time = info.get("upload_time")
    published_at = None
    if upload_time:
        try:
            from datetime import datetime
            published_at = datetime.strptime(upload_time, "%Y-%m-%d %H:%M:%S").isoformat(timespec="seconds")
        except Exception:
            published_at = upload_time
    desc = info.get("desc") or ""
    hashtags: list[str] = []
    if desc:
        for m in re.finditer(r"#([^#\s]{1,30})", desc):
            tag = m.group(1).strip()
            if tag:
                hashtags.append(tag)
    tag_list = info.get("tags") or []
    if isinstance(tag_list, list):
        for t in tag_list:
            if t and t not in hashtags:
                hashtags.append(t)

    return {
        "platform": "xhs",
        "platformPostId": note_id,
        "title": info.get("title") or "",
        "contentText": desc,
        "coverUrl": info.get("video_cover") or (info.get("image_list") or [None])[0],
        "link": info.get("note_url"),
        "type": ntype,
        "publishedAt": published_at,
        "likes": _int(info.get("liked_count")),
        "comments": _int(info.get("comment_count")),
        "collects": _int(info.get("collected_count")),
        "shares": _int(info.get("share_count")),
        "hashtags": hashtags,
        "videoUrl": info.get("video_addr"),
        "imageUrls": info.get("image_list") or [],
    }


def _int(v) -> int | None:
    if v is None:
        return None
    try:
        return int(str(v).replace(",", "").strip())
    except Exception:
        return None


def run(args: argparse.Namespace) -> int:
    out_path = Path(args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cookies = os.environ.get("COOKIES", "").strip()
    if not cookies:
        _write(out_path, {"ok": False, "error": "环境变量 COOKIES 未设置", "errorType": "cookie_invalid"})
        return 2

    try:
        user_id, home_url = _extract_user_id(args.user_url, args.user_id)
    except Exception as e:
        _write(out_path, {"ok": False, "error": str(e), "errorType": "bad_input"})
        return 2

    try:
        _bootstrap()
        from apis.xhs_pc_apis import XHS_Apis  # type: ignore
        from xhs_utils.data_util import handle_note_info  # type: ignore
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"Spider_XHS import 失败: {e}", "errorType": "bootstrap"})
        return 3

    api = XHS_Apis()
    try:
        print(f"[fetch_user_notes] 拉取 user={user_id} limit={args.limit}", file=sys.stderr)
        # NOTE: 不走 Spider_XHS 的 get_user_all_notes —— 它在 user_url 不带 query string
        # 时会抛 IndexError（urlparse('').query.split('&') -> [''] -> ['']['=='][1] 越界）。
        # 我们自己按 user_id + 游标分页调 get_user_note_info，xsec_source=pc_search 足够通过反爬。
        # url 里若用户手动带了 xsec_token 就尊重，否则留空（当前版本 XHS 对 xsec_source=pc_search
        # 不强校验 xsec_token；若未来强校验，前端提示用户从浏览器复制完整 profile URL）。
        from urllib.parse import urlparse, parse_qs  # local import so bootstrap import errors still funnel through _bootstrap except
        q = parse_qs(urlparse(home_url).query)
        xsec_token = (q.get("xsec_token") or [""])[0]
        xsec_source = (q.get("xsec_source") or ["pc_search"])[0]
        all_note_meta: list[dict] = []
        cursor = ""
        hard_cap = args.limit if (args.limit and args.limit > 0) else 200
        pages = 0
        while True:
            ok, msg, res_json = api.get_user_note_info(user_id, cursor, cookies,
                                                      xsec_token=xsec_token,
                                                      xsec_source=xsec_source)
            if not ok:
                _write(out_path, {"ok": False,
                                  "error": f"get_user_note_info 失败: {msg}",
                                  "errorType": _classify_error(str(msg))})
                return 4
            data = (res_json or {}).get("data") or {}
            notes = data.get("notes") or []
            all_note_meta.extend(notes)
            pages += 1
            if len(all_note_meta) >= hard_cap:
                break
            if not data.get("has_more"):
                break
            nxt = data.get("cursor")
            if not nxt or nxt == cursor:
                break
            cursor = str(nxt)
            if pages >= 20:  # safety
                break
        print(f"[fetch_user_notes] 分页完成 pages={pages} total_meta={len(all_note_meta)}", file=sys.stderr)
    except Exception as e:
        _write(out_path, {"ok": False, "error": f"API 异常: {e}",
                           "errorType": _classify_error(str(e)),
                           "traceback": traceback.format_exc()})
        return 4

    # 顺带拉一次 /user/otherinfo 拿 粉丝 / 关注 / 获赞 / 认证 / 头像 / 性别 / ip_location 等
    user_stats = _try_fetch_user_info(api, user_id, cookies)

    if not all_note_meta:
        _write(out_path, {
            "ok": True,
            "userId": user_id,
            "homeUrl": home_url,
            "fetched": 0,
            "posts": [],
            "userStats": user_stats,
        })
        return 0

    if args.limit and args.limit > 0:
        all_note_meta = all_note_meta[: args.limit]

    posts: list[dict] = []
    errors: list[str] = []
    for meta in all_note_meta:
        nid = meta.get("note_id")
        xsec = meta.get("xsec_token", "")
        note_url = f"https://www.xiaohongshu.com/explore/{nid}?xsec_token={xsec}"
        try:
            ok, nmsg, note_info = api.get_note_info(note_url, cookies, None)
            if not ok:
                errors.append(f"{nid}: {nmsg}")
                continue
            raw = note_info["data"]["items"][0]
            raw["url"] = note_url
            handled = handle_note_info(raw)
            posts.append(_map_note(handled))
        except Exception as e:
            errors.append(f"{nid}: {e}")

    payload = {
        "ok": True,
        "userId": user_id,
        "homeUrl": home_url,
        "fetched": len(posts),
        "posts": posts,
        "userStats": user_stats,
    }
    if errors:
        payload["partialErrors"] = errors[:20]
    _write(out_path, payload)
    return 0


def _try_fetch_user_info(api, user_id: str, cookies: str) -> dict | None:
    """调 /api/sns/web/v1/user/otherinfo，宽松容错：失败返回 None。"""
    try:
        ok, msg, res_json = api.get_user_info(user_id, cookies)
    except Exception as e:
        print(f"[fetch_user_notes] get_user_info 异常: {e}", file=sys.stderr)
        return None
    if not ok:
        print(f"[fetch_user_notes] get_user_info 失败: {msg}", file=sys.stderr)
        return None
    data = ((res_json or {}).get("data") or {})
    # xhs 返回结构：data.basic_info + data.interactions (list of {type,count,name}) + data.tags
    basic = data.get("basic_info") or {}
    interactions = data.get("interactions") or []
    inter_map: dict = {}
    if isinstance(interactions, list):
        for it in interactions:
            if not isinstance(it, dict):
                continue
            key = (it.get("type") or it.get("name") or "").strip()
            if not key:
                continue
            inter_map[key] = _int(it.get("count"))
    tags = data.get("tags") or []
    verified = False
    verify_type: str | None = None
    if isinstance(tags, list):
        for t in tags:
            if isinstance(t, dict) and (t.get("tagType") in ("verified", "profession") or t.get("type") == "official"):
                verified = True
                verify_type = t.get("name") or t.get("tagType") or verify_type
    stats = {
        "userId": user_id,
        "nickname": basic.get("nickname") or basic.get("red_id"),
        "redId": basic.get("red_id"),
        "desc": basic.get("desc"),
        "avatarUrl": basic.get("imageb") or basic.get("image"),
        "gender": basic.get("gender"),
        "ipLocation": basic.get("ip_location"),
        "followers": inter_map.get("fans") or inter_map.get("follower") or inter_map.get("粉丝"),
        "following": inter_map.get("follows") or inter_map.get("following") or inter_map.get("关注"),
        "likesAndCollects": inter_map.get("interaction") or inter_map.get("getlikes") or inter_map.get("获赞与收藏"),
        "verified": verified,
        "verifyType": verify_type,
        "raw": {"basic_info": basic, "interactions": interactions, "tags": tags},
    }
    return stats


def _classify_error(msg: str) -> str:
    low = (msg or "").lower()
    if "cookie" in low or "login" in low or "461" in low or "token" in low:
        return "cookie_invalid"
    if "404" in low or "not found" in low or "不存在" in msg:
        return "not_found"
    if "timeout" in low or "connect" in low or "network" in low:
        return "network"
    return "unknown"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--user-url", dest="user_url", default=None)
    ap.add_argument("--user-id", dest="user_id", default=None)
    ap.add_argument("--limit", type=int, default=20)
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
                          "errorType": "unknown",
                          "traceback": traceback.format_exc()})
        return 5


if __name__ == "__main__":
    sys.exit(main())
