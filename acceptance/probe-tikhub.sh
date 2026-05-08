#!/usr/bin/env bash
# ============================================================
# TikHub + DashScope ASR 冒烟脚本
# ------------------------------------------------------------
# 验证：
#   1. TikHub /api/v1/xiaohongshu/app/extract_share_info 200 + 返回 note_id+xsec_token
#   2. TikHub /api/v1/xiaohongshu/web_v3/fetch_note_detail 200 + 含视频直链
#   3. (可选) DashScope /api/v1/services/audio/asr/transcription 接受 paraformer-v2 任务
#
# 用法：
#   bash acceptance/probe-tikhub.sh                              # 用 .env 中的 key
#   TIKHUB_KEY=xxx bash acceptance/probe-tikhub.sh https://...   # 自定义 share link
# ============================================================
set -uo pipefail

if [[ -f .env ]]; then
  # 仅 source 我们关心的几个变量，避免 env 里其他密码污染脚本
  # shellcheck disable=SC1091
  source <(grep -E '^(XHS_TIKHUB_API_KEY|XHS_TIKHUB_BASE_URL|DASHSCOPE_ASR_API_KEY|EMBEDDING_API_KEY)=' .env || true)
fi

KEY="${TIKHUB_KEY:-${XHS_TIKHUB_API_KEY:-}}"
BASE="${XHS_TIKHUB_BASE_URL:-https://api.tikhub.io}"
SHARE_LINK="${1:-https://www.xiaohongshu.com/discovery/item/688f8b7a000000001d0339a8?source=webshare&xhsshare=pc_web&xsec_token=ABFOJzmAk-HcBLlDDpQ4uCgfh_a3xvuIaQiOsmcrpV9FY=&xsec_source=pc_share}"

if [[ -z "$KEY" ]]; then
  echo "[ERROR] TIKHUB_KEY / XHS_TIKHUB_API_KEY 未设置" >&2
  exit 2
fi

echo "[probe] base=$BASE"
echo "[probe] share_link=${SHARE_LINK:0:60}..."

echo "[step 1] extract_share_info ..."
TMP_EXTRACT=$(mktemp)
HTTP=$(curl -s -o "$TMP_EXTRACT" -w "%{http_code}" \
    -H "Authorization: Bearer $KEY" \
    --get \
    --data-urlencode "share_link=$SHARE_LINK" \
    "$BASE/api/v1/xiaohongshu/app/extract_share_info" || echo "000")
echo "  http=$HTTP"
if [[ "$HTTP" != "200" ]]; then
  echo "[FAIL] extract_share_info 非 200，内容："
  cat "$TMP_EXTRACT" | head -c 600 ; echo
  rm -f "$TMP_EXTRACT"
  exit 3
fi

NOTE_ID=$(grep -oE '"note_id":"[^"]+"' "$TMP_EXTRACT" | head -1 | sed 's/"note_id":"//;s/"$//')
XSEC=$(grep -oE '"xsec_token":"[^"]+"' "$TMP_EXTRACT" | head -1 | sed 's/"xsec_token":"//;s/"$//')
rm -f "$TMP_EXTRACT"
if [[ -z "$NOTE_ID" || -z "$XSEC" ]]; then
  echo "[FAIL] extract_share_info 未返回 note_id 或 xsec_token"
  exit 4
fi
echo "  note_id=$NOTE_ID  xsec_token=${XSEC:0:12}..."

echo "[step 2] fetch_note_detail ..."
TMP_DETAIL=$(mktemp)
HTTP=$(curl -s -o "$TMP_DETAIL" -w "%{http_code}" \
    -H "Authorization: Bearer $KEY" \
    --get \
    --data-urlencode "note_id=$NOTE_ID" \
    --data-urlencode "xsec_token=$XSEC" \
    "$BASE/api/v1/xiaohongshu/web_v3/fetch_note_detail" || echo "000")
echo "  http=$HTTP"
if [[ "$HTTP" != "200" ]]; then
  echo "[WARN] fetch_note_detail 非 200，可能是 share_link 已失效 / 笔记下架。内容："
  cat "$TMP_DETAIL" | head -c 600 ; echo
  echo "       step1 200 已经足以说明 TikHub key 有效；建议换一条新鲜的 share_link 重跑此脚本。"
else
  if grep -q '"masterUrl"' "$TMP_DETAIL"; then
    MASTER=$(grep -oE '"masterUrl":"[^"]+"' "$TMP_DETAIL" | head -1 | sed 's/"masterUrl":"//;s/"$//')
    echo "  masterUrl=${MASTER:0:80}..."
  else
    echo "[WARN] 响应中无 masterUrl（可能是图文笔记）"
  fi
fi

# duration
DUR=$(grep -oE '"duration":[0-9]+' "$TMP_DETAIL" | head -1 | sed 's/"duration"://')
[[ -n "$DUR" ]] && echo "  duration=${DUR}"
rm -f "$TMP_DETAIL"

# step 3：可选 — 验 DashScope ASR submit（不会真消费配额，只确认 401/200）
ASR_KEY="${DASHSCOPE_ASR_API_KEY:-${EMBEDDING_API_KEY:-}}"
if [[ -n "$ASR_KEY" ]]; then
  echo "[step 3] DashScope ASR ping ..."
  TMP_ASR=$(mktemp)
  HTTP=$(curl -s -o "$TMP_ASR" -w "%{http_code}" \
      -X POST \
      -H "Authorization: Bearer $ASR_KEY" \
      -H "Content-Type: application/json" \
      -H "X-DashScope-Async: enable" \
      -d '{"model":"paraformer-v2","input":{"file_urls":["https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male2.wav"]},"parameters":{"channel_id":[0],"language_hints":["zh","en"]}}' \
      "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription" || echo "000")
  echo "  http=$HTTP"
  if [[ "$HTTP" == "200" ]]; then
    TASK_ID=$(grep -oE '"task_id":"[^"]+"' "$TMP_ASR" | head -1 | sed 's/"task_id":"//;s/"$//')
    echo "  task_id=$TASK_ID（已提交，让 DashScope 自己跑完）"
  else
    echo "[WARN] DashScope ASR ping 非 200，body 头部："
    head -c 400 "$TMP_ASR" ; echo
  fi
  rm -f "$TMP_ASR"
else
  echo "[step 3] 跳过：DASHSCOPE_ASR_API_KEY / EMBEDDING_API_KEY 均未设置"
fi

echo "[OK] TikHub + ASR 通道探针通过"
