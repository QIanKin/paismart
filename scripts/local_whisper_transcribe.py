#!/usr/bin/env python3
"""
本地 ASR：faster-whisper（CPU int8），与 Java 后端同容器运行，无需额外 Docker 服务。

用法：
  python3 local_whisper_transcribe.py <音频路径> --model base [--language zh] [--task transcribe]

成功：向 stdout 打印一行 JSON：{"text":"...","segments":[{"start":0.0,"end":1.0,"text":"..."}],"language":"zh"}
失败：非零退出码，错误信息在 stderr。
"""
from __future__ import annotations

import argparse
import json
import sys
import traceback


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input_path", help="wav/mp3/m4a 等音频文件路径")
    ap.add_argument("--model", default="base", help="tiny|base|small|medium|large-v3 或 HF 模型 id")
    ap.add_argument("--language", default="", help="留空则自动检测")
    ap.add_argument("--task", default="transcribe", choices=["transcribe", "translate"])
    args = ap.parse_args()

    try:
        from faster_whisper import WhisperModel
    except ImportError as e:
        print("faster_whisper 未安装: " + str(e), file=sys.stderr)
        return 2

    model_id = (args.model or "base").strip()
    lang = (args.language or "").strip() or None
    task = args.task or "transcribe"

    try:
        wm = WhisperModel(model_id, device="cpu", compute_type="int8")
        segments_iter, info = wm.transcribe(
            args.input_path,
            language=lang,
            task=task,
            beam_size=5,
        )
        text_parts: list[str] = []
        seg_list: list[dict] = []
        for seg in segments_iter:
            t = seg.text or ""
            text_parts.append(t)
            seg_list.append({"start": float(seg.start), "end": float(seg.end), "text": t.strip()})
        full_text = "".join(text_parts).strip()
        detected = getattr(info, "language", None) or (args.language or "unknown")
        out = {"text": full_text, "segments": seg_list, "language": detected}
        sys.stdout.write(json.dumps(out, ensure_ascii=False))
        sys.stdout.flush()
        return 0
    except Exception:
        traceback.print_exc(file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
