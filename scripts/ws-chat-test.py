#!/usr/bin/env python3
"""Quick WebSocket smoke test for ChatWebSocketHandler.

Usage inside the backend container:
    python3 /app/scripts/ws-chat-test.py <JWT> <projectId> <sessionId> "user message"
"""
from __future__ import annotations

import asyncio
import json
import sys
import time

import websockets


async def run(jwt: str, project_id: str, session_id: str, content: str, timeout_s: int = 45) -> int:
    uri = f"ws://127.0.0.1:8081/chat/{jwt}"
    print(f"[ws] connecting {uri}")
    async with websockets.connect(uri, max_size=4 * 1024 * 1024) as ws:
        payload = json.dumps({
            "type": "chat",
            "content": content,
            "sessionId": session_id,
            "projectId": project_id,
        })
        await ws.send(payload)
        print(f"[ws] sent: {payload}")

        got_any = 0
        tool_calls = []
        final_tail = ""
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
            except asyncio.TimeoutError:
                print("[ws] recv timeout (no msg in 5s)")
                continue
            got_any += 1
            preview = raw if len(raw) < 400 else raw[:400] + "..."
            print(f"[ws][#{got_any}] {preview}")
            # Try to spot tool invocation markers and finish signal
            if "tool_start" in raw or "tool_call" in raw or "使用工具" in raw or "tool invoked" in raw:
                tool_calls.append(raw[:200])
            if '"type":"done"' in raw or '"finish":true' in raw or '"type":"complete"' in raw or "[stream_done]" in raw:
                final_tail = raw
                break
        print(f"[ws] done; messages={got_any} tool_markers={len(tool_calls)}")
        return 0 if got_any > 0 else 2


def main() -> int:
    if len(sys.argv) < 5:
        print("usage: ws-chat-test.py <JWT> <projectId> <sessionId> <content>", file=sys.stderr)
        return 1
    jwt, project_id, session_id, content = sys.argv[1:5]
    timeout_s = int(sys.argv[5]) if len(sys.argv) > 5 else 45
    return asyncio.run(run(jwt, project_id, session_id, content, timeout_s))


if __name__ == "__main__":
    sys.exit(main())
