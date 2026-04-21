"""
Smoke test: open agent chat WS, send a message that forces tool calling,
then collect streaming events and dump a tool-call summary.

Usage:
  python scripts/_smoke_chat.py "<token>" "<prompt>"
"""
from __future__ import annotations

import asyncio
import json
import sys
import time

import websockets


async def run(token: str, prompt: str, project_id: int | None, session_id: int | None) -> None:
    url = f"ws://127.0.0.1/proxy-ws/chat/{token}"
    tool_events: list[dict] = []
    content_chunks: list[str] = []
    all_types: dict[str, int] = {}
    got_done = False
    start = time.time()

    async with websockets.connect(url, max_size=None) as ws:
        msg: dict = {"type": "chat", "content": prompt}
        if project_id is not None:
            msg["projectId"] = project_id
        if session_id is not None:
            msg["sessionId"] = session_id
        await ws.send(json.dumps(msg, ensure_ascii=False))
        print(f"[SEND] {msg}")

        try:
            while True:
                raw = await asyncio.wait_for(ws.recv(), timeout=90)
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8", errors="replace")
                if raw == "pong":
                    continue
                if not raw.startswith("{"):
                    content_chunks.append(raw)
                    continue
                try:
                    obj = json.loads(raw)
                except Exception:
                    content_chunks.append(raw)
                    continue
                t = obj.get("type") or obj.get("event") or "?"
                all_types[t] = all_types.get(t, 0) + 1
                if t in ("tool_call", "tool_result", "tool.start", "tool.end", "tool_start", "tool_end"):
                    tool_events.append(obj)
                    print(f"[{t}] {json.dumps(obj, ensure_ascii=False)[:400]}")
                elif t in ("assistant.delta", "assistant_delta", "delta", "token"):
                    chunk = obj.get("content") or obj.get("delta") or obj.get("text") or ""
                    content_chunks.append(chunk)
                elif t in ("done", "assistant.done", "final", "end"):
                    got_done = True
                    print(f"[{t}] {json.dumps(obj, ensure_ascii=False)[:400]}")
                    break
                elif t in ("error", "assistant.error"):
                    print(f"[ERROR] {json.dumps(obj, ensure_ascii=False)[:600]}")
                    break
                else:
                    sample = json.dumps(obj, ensure_ascii=False)
                    print(f"[{t}] {sample[:300]}")
                    # treat content-style frames loosely
                    for k in ("content", "delta", "text", "message"):
                        if isinstance(obj.get(k), str):
                            content_chunks.append(obj[k])
                            break
        except asyncio.TimeoutError:
            print("[TIMEOUT] no frame in 90s")
        except websockets.ConnectionClosed as e:
            print(f"[WS_CLOSED] code={e.code} reason={e.reason}")

    elapsed = time.time() - start
    text = "".join(content_chunks)
    print("\n================ SUMMARY ================")
    print(f"elapsed_sec = {elapsed:.1f}")
    print(f"got_done    = {got_done}")
    print(f"event_types = {all_types}")
    print(f"tool_events = {len(tool_events)}")
    for ev in tool_events:
        name = ev.get("name") or ev.get("tool") or ev.get("toolName") or ev.get("data", {}).get("name") if isinstance(ev.get("data"), dict) else None
        print(f"  - type={ev.get('type') or ev.get('event')} name={name}")
    print("---- assistant text (first 1200 chars) ----")
    print(text[:1200])


if __name__ == "__main__":
    token = sys.argv[1]
    prompt = sys.argv[2] if len(sys.argv) > 2 else "请调用 creator_search 工具帮我列出数据库里已有的博主，返回 name/platform/follower 字段。"
    def _maybe_int(v: str | None) -> int | None:
        if v is None: return None
        v = v.strip().lower()
        if v in ("", "none", "null", "-"): return None
        return int(v)
    project_id = _maybe_int(sys.argv[3]) if len(sys.argv) > 3 else None
    session_id = _maybe_int(sys.argv[4]) if len(sys.argv) > 4 else None
    asyncio.run(run(token, prompt, project_id, session_id))
