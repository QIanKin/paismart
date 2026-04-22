#!/usr/bin/env bash
for host in qianfan.xiaohongshu.com ark.xiaohongshu.com seller.xiaohongshu.com; do
  printf '%s -> ' "$host"
  getent hosts "$host" 2>/dev/null | head -1 || echo NXDOMAIN
done
echo '---HTTP probes:'
for url in https://ark.xiaohongshu.com/ https://seller.xiaohongshu.com/; do
  curl -sS -o /dev/null -w "$url  status=%{http_code} final=%{url_effective}\n" -m 10 -L "$url"
done
