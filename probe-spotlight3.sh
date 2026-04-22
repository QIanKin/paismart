#!/usr/bin/env bash
TOKEN='225f18c94840cc9f1e7c41f27610bb94'
ADV=974949
BASE='https://adapi.xiaohongshu.com/api/open'

call() {
  local path="$1"; shift
  local body="$1"; shift
  printf '\n=== POST %s\n' "$path"
  printf 'body: %s\n' "$body"
  curl -sS -m 15 -X POST \
    -H "Access-Token: $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -w '\nHTTP=%{http_code}\n' \
    "$BASE$path" | head -c 800
  echo ''
}

# 1. account.balance（已证明能通）
call "/jg/account/balance/info" "{\"advertiser_id\":$ADV}"

# 2. white_list
call "/jg/account/white_list"   "{\"advertiser_id\":$ADV}"

# 3. campaign.list
call "/jg/campaign/list"        "{\"advertiser_id\":$ADV,\"page\":1,\"page_size\":10}"

# 4. unit.list
call "/jg/unit/list"            "{\"advertiser_id\":$ADV,\"page\":1,\"page_size\":10}"

# 5. advertiser realtime report
call "/jg/data/report/realtime/account" "{\"advertiser_id\":$ADV}"

# 6. advertiser offline report
call "/jg/data/report/offline/account"  "{\"advertiser_id\":$ADV,\"start_date\":\"2026-04-15\",\"end_date\":\"2026-04-21\"}"
