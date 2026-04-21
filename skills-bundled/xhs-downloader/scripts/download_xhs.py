#!/usr/bin/env python3
"""xhs-downloader 执行体：下载小红书视频 / 图文 / 音频，并可选产出资源包。

相对原 openclaw 版（workspace/xiaohongshu-downloader/scripts/download_xiaohongshu.py）的改造点：
  1. 不再强制 --cookies-from-browser chrome；改为从环境变量 COOKIES 生成临时 Netscape cookies.txt
  2. 输出目录默认从沙箱子目录推导，而不是 ~/Downloads
  3. 始终输出 out.json（ok / errorType / files / transcript_preview）供 XhsSkillRunner 收集
  4. 不强依赖 Whisper（whisper 未装时只降级为 "no_subtitle"，不中断流程）
"""

from __future__ import annotations

import argparse
import glob as globmod
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path
from typing import Optional

URL_PATTERNS = (
    re.compile(r"https?://www\.xiaohongshu\.com/(?:explore|discovery/item)/[\da-f]+"),
    re.compile(r"https?://xhslink\.com/"),
)

# 我们 COOKIES env 的格式："name=value; name=value" —— 和 Spider_XHS 约定一致
COOKIE_DOMAIN = ".xiaohongshu.com"
COOKIE_DEFAULT_EXPIRY = 2_000_000_000  # 2033-ish


def _write(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _check_bin(name: str) -> bool:
    return shutil.which(name) is not None


def _netscape_from_cookie_str(cookie_str: str, out_path: Path) -> bool:
    """把 'k=v; k=v' 转成 yt-dlp 能读的 Netscape cookies.txt。

    由于字符串里没有 domain/secure/expiry，这里用 .xiaohongshu.com + secure=FALSE + 远期过期兜底。
    Returns True if at least one cookie was written.
    """
    if not cookie_str:
        return False
    lines = ["# Netscape HTTP Cookie File"]
    count = 0
    for entry in cookie_str.split(";"):
        entry = entry.strip()
        if not entry or "=" not in entry:
            continue
        k, v = entry.split("=", 1)
        k = k.strip()
        v = v.strip()
        if not k:
            continue
        # domain / include_subdomains / path / secure / expiry / name / value
        lines.append(
            "\t".join([
                COOKIE_DOMAIN, "TRUE", "/", "FALSE",
                str(COOKIE_DEFAULT_EXPIRY), k, v,
            ])
        )
        count += 1
    if count == 0:
        return False
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return True


def _sanitize_title(title: str) -> str:
    sanitized = re.sub(r'[<>:"/\\|?*]', "", title or "")
    sanitized = re.sub(r"\s+", " ", sanitized).strip()
    if len(sanitized) > 80:
        sanitized = sanitized[:80].rstrip()
    return sanitized or "untitled"


def _get_video_info(url: str, cookies_file: Optional[Path]) -> Optional[dict]:
    cmd = ["yt-dlp", "--dump-json", "--no-playlist"]
    if cookies_file:
        cmd.extend(["--cookies", str(cookies_file)])
    cmd.append(url)
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=60)
        return json.loads(res.stdout)
    except subprocess.TimeoutExpired:
        print("[info] yt-dlp dump-json 超时", file=sys.stderr)
        return None
    except subprocess.CalledProcessError as e:
        print(f"[info] yt-dlp dump-json 失败: {(e.stderr or '').strip()[:200]}", file=sys.stderr)
        return None


def _download_video(url: str, quality: str, audio_only: bool,
                    out_template: Path, cookies_file: Optional[Path]) -> tuple[bool, str]:
    cmd = ["yt-dlp"]
    if cookies_file:
        cmd.extend(["--cookies", str(cookies_file)])
    if audio_only:
        cmd.extend(["-x", "--audio-format", "mp3", "--audio-quality", "0"])
    else:
        if quality == "best":
            fmt = "bestvideo+bestaudio/best"
        else:
            h = quality.replace("p", "")
            fmt = f"bestvideo[height<={h}]+bestaudio/best[height<={h}]"
        cmd.extend(["-f", fmt, "--merge-output-format", "mp4"])
    cmd.extend(["-o", str(out_template), "--no-playlist", url])
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=600)
        return True, res.stdout[-500:] if res.stdout else ""
    except subprocess.TimeoutExpired:
        return False, "yt-dlp 下载超时（>10min）"
    except subprocess.CalledProcessError as e:
        err = (e.stderr or e.stdout or "")[-800:]
        return False, err


def _list_formats(url: str, cookies_file: Optional[Path]) -> str:
    cmd = ["yt-dlp", "--list-formats", "--no-playlist"]
    if cookies_file:
        cmd.extend(["--cookies", str(cookies_file)])
    cmd.append(url)
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=60)
        return res.stdout
    except Exception as e:
        return f"[error] {e}"


def _extract_audio(video_path: Path, out_dir: Path) -> Optional[Path]:
    audio_path = out_dir / "audio.mp3"
    cmd = [
        "ffmpeg", "-i", str(video_path),
        "-vn", "-acodec", "libmp3lame", "-q:a", "2", "-y",
        str(audio_path),
    ]
    try:
        subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=300)
        return audio_path
    except Exception as e:
        print(f"[warn] ffmpeg 抽音频失败: {e}", file=sys.stderr)
        return None


def _find_rename_vtt(out_dir: Path, prefix: str, target: Path) -> bool:
    matches = globmod.glob(str(out_dir / f"{prefix}*.vtt"))
    if not matches:
        return False
    try:
        os.rename(matches[0], target)
    except OSError:
        return False
    for f in globmod.glob(str(out_dir / f"{prefix}*")):
        try:
            os.remove(f)
        except OSError:
            pass
    return True


def _acquire_subtitles(url: str, out_dir: Path,
                      cookies_file: Optional[Path]) -> Optional[Path]:
    vtt_target = out_dir / "subtitle.vtt"
    temp_prefix = "temp_sub"

    for tier_name, sub_flag in (("manual", "--write-subs"), ("auto", "--write-auto-subs")):
        cmd = [
            "yt-dlp", sub_flag,
            "--sub-lang", "zh,en,zh-Hans,zh-CN",
            "--sub-format", "vtt",
            "--skip-download", "--no-playlist",
            "-o", str(out_dir / f"{temp_prefix}.%(ext)s"),
        ]
        if cookies_file:
            cmd.extend(["--cookies", str(cookies_file)])
        cmd.append(url)
        try:
            subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        except Exception:
            continue
        if _find_rename_vtt(out_dir, temp_prefix, vtt_target):
            print(f"[info] 字幕源：{tier_name}")
            return vtt_target
    return None


def _generate_transcript(vtt_path: Path, out_dir: Path) -> Optional[Path]:
    tr_path = out_dir / "transcript.txt"
    try:
        text = vtt_path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return None
    cleaned = []
    seen = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("WEBVTT") or line.startswith("NOTE"):
            continue
        if re.match(r"^\d+$", line):
            continue
        if re.match(r"\d{2}:\d{2}[\.:]\d{2}", line):
            continue
        line = re.sub(r"<[^>]+>", "", line).strip()
        if line and line not in seen:
            seen.add(line)
            cleaned.append(line)
    if not cleaned:
        return None
    tr_path.write_text("\n".join(cleaned) + "\n", encoding="utf-8")
    return tr_path


def _validate_url(url: str) -> bool:
    return any(p.search(url) for p in URL_PATTERNS)


def _find_video_file(out_dir: Path) -> Optional[Path]:
    for ext in ("mp4", "mkv", "webm", "mov"):
        for p in out_dir.glob(f"video*.{ext}"):
            return p
    for ext in ("mp4", "mkv", "webm", "mov"):
        for p in out_dir.glob(f"*.{ext}"):
            return p
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="xhs-downloader")
    parser.add_argument("--url", required=True, help="小红书视频 / 图文链接")
    parser.add_argument("--output", required=True, help="机器可读 out.json 路径")
    parser.add_argument("--output-dir", default=None, help="资源文件落地目录（默认：<output>/../files）")
    parser.add_argument("--mode", choices=["basic", "full", "summary"], default="basic")
    parser.add_argument("--quality", default="best", choices=["best", "1080p", "720p", "480p"])
    parser.add_argument("--audio-only", action="store_true")
    parser.add_argument("--list-formats", action="store_true")
    args = parser.parse_args()

    out_json = Path(args.output).resolve()

    if args.list_formats:
        cookies_file = None
        cookie_str = os.environ.get("COOKIES", "").strip()
        tmp_cookie_path: Optional[Path] = None
        if cookie_str:
            tmp_cookie_path = Path(tempfile.mkdtemp()) / "cookies.txt"
            if _netscape_from_cookie_str(cookie_str, tmp_cookie_path):
                cookies_file = tmp_cookie_path
        fmts = _list_formats(args.url, cookies_file)
        _write(out_json, {"ok": True, "mode": "list-formats", "formats": fmts})
        return 0

    if not _validate_url(args.url):
        _write(out_json, {"ok": False, "errorType": "url_invalid",
                          "error": "URL 不匹配已知小红书格式（explore/discovery/xhslink）"})
        return 2

    if not _check_bin("yt-dlp"):
        _write(out_json, {"ok": False, "errorType": "yt_dlp_missing",
                          "error": "yt-dlp 未安装；请在容器里 `pip install yt-dlp`"})
        return 2

    if args.mode in ("full", "summary") and not _check_bin("ffmpeg"):
        _write(out_json, {"ok": False, "errorType": "ffmpeg_missing",
                          "error": "full/summary 模式需要 ffmpeg，但未安装"})
        return 2

    # 生成临时 cookies.txt
    cookies_file: Optional[Path] = None
    tmp_dir = Path(tempfile.mkdtemp(prefix="xhs-dl-"))
    try:
        cookie_str = os.environ.get("COOKIES", "").strip()
        if cookie_str:
            cookies_file = tmp_dir / "cookies.txt"
            if not _netscape_from_cookie_str(cookie_str, cookies_file):
                cookies_file = None

        # 拉 meta
        info = _get_video_info(args.url, cookies_file) or {}
        title = info.get("title") or "untitled"
        duration = int(info.get("duration") or 0)
        uploader = info.get("uploader") or "unknown"

        # 输出目录
        if args.output_dir:
            out_dir = Path(args.output_dir).resolve()
        else:
            out_dir = out_json.parent / "files"
        out_dir.mkdir(parents=True, exist_ok=True)

        full_mode = args.mode in ("full", "summary")
        if full_mode:
            out_template = out_dir / "video.%(ext)s"
        else:
            out_template = out_dir / "%(title)s_%(id)s.%(ext)s"

        ok, log_tail = _download_video(args.url, args.quality, args.audio_only,
                                       out_template, cookies_file)
        if not ok:
            errtype = "yt_dlp_failed"
            lower = log_tail.lower()
            if any(s in lower for s in ["login required", "cookies", "authorization", "captcha",
                                        "unable to extract", "operation not permitted", "登录", "验证"]):
                errtype = "cookie_invalid"
            _write(out_json, {
                "ok": False, "errorType": errtype,
                "error": log_tail.strip()[-500:] or "下载失败",
                "title": title, "uploader": uploader,
                "files": {},
            })
            return 1

        files: dict[str, str] = {}
        video_path = _find_video_file(out_dir)
        if video_path:
            files["video"] = str(video_path)

        if not full_mode:
            _write(out_json, {
                "ok": True, "mode": args.mode, "url": args.url,
                "title": title, "uploader": uploader, "duration": duration,
                "files": files, "errorType": None,
            })
            return 0

        # ---- full / summary 模式 ----
        if video_path:
            audio_path = _extract_audio(video_path, out_dir)
            if audio_path:
                files["audio"] = str(audio_path)

        vtt_path = _acquire_subtitles(args.url, out_dir, cookies_file)
        transcript_path = None
        transcript_preview = ""
        if vtt_path:
            files["subtitle"] = str(vtt_path)
            transcript_path = _generate_transcript(vtt_path, out_dir)
            if transcript_path:
                files["transcript"] = str(transcript_path)
                try:
                    transcript_preview = transcript_path.read_text(encoding="utf-8")[:500]
                except Exception:
                    transcript_preview = ""

        if args.mode == "summary":
            meta_path = out_dir / ".meta.json"
            meta = {
                "title": title, "url": args.url,
                "duration_sec": duration,
                "duration": f"{duration // 60}:{duration % 60:02d}" if duration else "unknown",
                "platform": "Xiaohongshu (小红书)",
                "uploader": uploader,
            }
            meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
            files["meta"] = str(meta_path)

        _write(out_json, {
            "ok": True, "mode": args.mode, "url": args.url,
            "title": title, "uploader": uploader, "duration": duration,
            "files": files,
            "transcript_preview": transcript_preview,
            "errorType": None if vtt_path else "no_subtitle",
        })
        return 0

    except Exception as e:
        _write(out_json, {
            "ok": False, "errorType": "unhandled",
            "error": f"{type(e).__name__}: {e}",
            "trace": traceback.format_exc()[-800:],
            "files": {},
        })
        return 1
    finally:
        try:
            if tmp_dir.exists():
                shutil.rmtree(tmp_dir, ignore_errors=True)
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())
