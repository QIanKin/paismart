#!/usr/bin/env bash
set -e
TOKEN='225f18c94840cc9f1e7c41f27610bb94'
ADV='974949'

echo "=== 1. balance_info (advertiser_id=$ADV) ==="
curl -sS -m 15 \
  -H "Access-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  "https://adapi.xiaohongshu.com/api/open/jg/account/balance/info?advertiser_id=$ADV" \
  -w "\nHTTP=%{http_code} time=%{time_total}s\n"

echo ""
echo "=== 2. whitelist (also accessToken-only) ==="
curl -sS -m 15 \
  -H "Access-Token: $TOKEN" \
  "https://adapi.xiaohongshu.com/api/open/jg/account/white_list?advertiser_id=$ADV" \
  -w "\nHTTP=%{http_code}\n"

echo ""
echo "=== 3. 校验 token 有效性 (advertiser.access_token get) ==="
curl -sS -m 15 \
  -H "Access-Token: $TOKEN" \
  "https://adapi.xiaohongshu.com/api/open/jg/oauth2/advertiser/access_token?advertiser_id=$ADV" \
  -w "\nHTTP=%{http_code}\n"
