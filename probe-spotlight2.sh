#!/usr/bin/env bash
TOKEN='225f18c94840cc9f1e7c41f27610bb94'
ADV='974949'
BASE='https://adapi.xiaohongshu.com'

probe() {
  local label="$1"; local method="$2"; local path="$3"; shift 3
  printf '[%s] %-6s %s\n' "$label" "$method" "$path"
  resp=$(curl -sS -m 12 -X "$method" "$@" "$path" 2>&1)
  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | head -c 400)
  echo "  body_head: ${body:0:300}"
  echo ""
}

echo "========== 1. SDK stock path =========="
curl -sS -m 10 -H "Access-Token: $TOKEN" \
  -w '\nHTTP=%{http_code}\n' \
  "$BASE/api/open/jg/account/balance/info?advertiser_id=$ADV"
echo ""

echo "========== 2. 去掉 /api/open 前缀 =========="
curl -sS -m 10 -H "Access-Token: $TOKEN" \
  -w '\nHTTP=%{http_code}\n' \
  "$BASE/jg/account/balance/info?advertiser_id=$ADV"
echo ""

echo "========== 3. /api/ad/jg/... =========="
curl -sS -m 10 -H "Access-Token: $TOKEN" \
  -w '\nHTTP=%{http_code}\n' \
  "$BASE/api/ad/jg/account/balance/info?advertiser_id=$ADV"
echo ""

echo "========== 4. Bearer token 风格 =========="
curl -sS -m 10 -H "Authorization: Bearer $TOKEN" \
  -w '\nHTTP=%{http_code}\n' \
  "$BASE/api/open/jg/account/balance/info?advertiser_id=$ADV"
echo ""

echo "========== 5. access_token query string =========="
curl -sS -m 10 \
  -w '\nHTTP=%{http_code}\n' \
  "$BASE/api/open/jg/account/balance/info?advertiser_id=$ADV&access_token=$TOKEN"
echo ""

echo "========== 6. POST 方法 =========="
curl -sS -m 10 -X POST \
  -H "Access-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"advertiser_id\":$ADV}" \
  -w '\nHTTP=%{http_code}\n' \
  "$BASE/api/open/jg/account/balance/info"
echo ""

echo "========== 7. 根路径探测 =========="
curl -sS -m 10 -I "$BASE/" -w 'HTTP=%{http_code}\n' | head -5
echo ""

echo "========== 8. /api/open 根探测（看网关有没有反馈）=========="
curl -sS -m 10 "$BASE/api/open/" -w '\nHTTP=%{http_code}\n' | head -5
