# -*- coding: utf-8 -*-
# PaiSmart Acceptance Smoke Test - covers every subsystem before client delivery.
# Usage:  pwsh -File acceptance/smoke.ps1
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$BASE      = 'http://127.0.0.1:8081'
$USERNAME  = 'admin'
$PASSWORD  = 'Yyanyyan@666'
$results   = New-Object System.Collections.Generic.List[object]

function Record {
    param([string]$Area,[string]$Case,[string]$Status,[string]$Detail='')
    $results.Add([pscustomobject]@{ Area=$Area; Case=$Case; Status=$Status; Detail=$Detail })
    $color = switch ($Status) { 'PASS' {'Green'} 'FAIL' {'Red'} 'WARN' {'Yellow'} default {'Gray'} }
    Write-Host ("  {0,-30} {1,-6} {2}" -f $Case,$Status,$Detail) -ForegroundColor $color
}
function Call {
    param([string]$Method,[string]$Path,$Body=$null,[hashtable]$Extra=$null)
    $hdr = @{ Authorization = "Bearer $TOKEN" }
    if ($Extra) { foreach ($k in $Extra.Keys) { $hdr[$k]=$Extra[$k] } }
    $p = @{ Uri="$BASE$Path"; Method=$Method; Headers=$hdr; TimeoutSec=30 }
    if ($null -ne $Body) { $p.ContentType='application/json'; $p.Body = ($Body | ConvertTo-Json -Depth 10 -Compress) }
    try { return Invoke-RestMethod @p } catch {
        $msg = $_.Exception.Message
        try { $r = $_.ErrorDetails.Message } catch {}
        throw ("HTTP {0} -> {1} {2}" -f $Method,$Path,($msg+' '+$r).Trim())
    }
}

# ------------------------------------------------------------------
Write-Host "== 1. Auth & Core =========================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try {
    $login = Invoke-RestMethod -Uri "$BASE/api/v1/users/login" -Method Post -ContentType 'application/json' `
             -Body (@{username=$USERNAME; password=$PASSWORD}|ConvertTo-Json) -TimeoutSec 10
    $script:TOKEN = $login.data.token
    Record 'Auth' 'admin_login' 'PASS' "token_len=$($TOKEN.Length)"
} catch { Record 'Auth' 'admin_login' 'FAIL' $_.Exception.Message; exit 1 }

try { $me = Call GET '/api/v1/users/me'; Record 'Auth' 'users_me' 'PASS' "role=$($me.data.role) org=$($me.data.primaryOrg)" } catch { Record 'Auth' 'users_me' 'FAIL' $_.Exception.Message }
try { $ws = Call GET '/api/v1/chat/websocket-token'; if ($ws.data) { Record 'Auth' 'ws_token' 'PASS' "len=$([string]$ws.data.token.Length)" } else { Record 'Auth' 'ws_token' 'WARN' 'no data' } } catch { Record 'Auth' 'ws_token' 'FAIL' $_.Exception.Message }
try { $sys = Call GET '/api/v1/admin/system/status'; Record 'Auth' 'system_status' 'PASS' ($sys | ConvertTo-Json -Depth 2 -Compress).Substring(0,[Math]::Min(80,($sys|ConvertTo-Json -Depth 2 -Compress).Length)) } catch { Record 'Auth' 'system_status' 'WARN' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 2. Agent core ==========================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try {
    $tools = Call GET '/api/v1/agent/tools'
    $n = ($tools.data | Measure-Object).Count
    Record 'Agent' 'tools_list' $(if($n -ge 40){'PASS'}else{'WARN'}) "count=$n"
} catch { Record 'Agent' 'tools_list' 'FAIL' $_.Exception.Message }

try {
    $cat = Call GET '/api/v1/agent/tools/catalog'
    $grp = ($cat.data.groups | Measure-Object).Count
    $tot = [int]$cat.data.total
    Record 'Agent' 'tools_catalog' $(if($grp -ge 5 -and $tot -ge 40){'PASS'}else{'WARN'}) "groups=$grp total=$tot"
} catch { Record 'Agent' 'tools_catalog' 'FAIL' $_.Exception.Message }

try {
    $schema = Call GET '/api/v1/agent/tools/schema'
    $mf = ($schema.data | Measure-Object).Count
    Record 'Agent' 'tools_schema' $(if($mf -ge 40){'PASS'}else{'WARN'}) "manifest=$mf"
} catch { Record 'Agent' 'tools_schema' 'FAIL' $_.Exception.Message }

try {
    $ses = Call GET '/api/v1/agent/sessions?page=1&pageSize=5'
    Record 'Agent' 'sessions_list' 'PASS' "records=$(($ses.data.records|Measure-Object).Count)"
} catch { Record 'Agent' 'sessions_list' 'FAIL' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 3. XHS cookies =========================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try {
    $cookies = Call GET '/api/v1/admin/xhs-cookies'
    $all = @($cookies.data.items)
    Record 'XHS' 'cookies_list' 'PASS' "total=$($all.Count)"
    foreach ($c in $all) {
        try {
            $ping = Call POST "/api/v1/admin/xhs-cookies/$($c.id)/ping"
            $pd = $ping.data
            if ($pd.ok) {
                Record 'XHS' ("ping_{0}_{1}" -f $c.platform,$c.id) 'PASS' "latency=$($pd.latencyMs)ms"
            } else {
                $msg = "$($pd.errorType) :: $($pd.message)"
                if ($msg.Length -gt 120) { $msg = $msg.Substring(0,120) }
                Record 'XHS' ("ping_{0}_{1}" -f $c.platform,$c.id) 'WARN' $msg
            }
        } catch { Record 'XHS' ("ping_{0}_{1}" -f $c.platform,$c.id) 'FAIL' $_.Exception.Message }
    }
} catch { Record 'XHS' 'cookies_list' 'FAIL' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 4. Projects & Roster ===================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try {
    $projs = Call GET '/api/v1/agent/projects?page=1&pageSize=5'
    Record 'Proj' 'project_list' 'PASS' "records=$(($projs.data.records|Measure-Object).Count)"
    $proj1 = $projs.data.records | Select-Object -First 1
    if ($proj1) {
        try {
            $detail = Call GET "/api/v1/agent/projects/$($proj1.id)"
            Record 'Proj' 'project_detail' 'PASS' "id=$($proj1.id) name=$($detail.data.name)"
        } catch { Record 'Proj' 'project_detail' 'FAIL' $_.Exception.Message }
        try {
            $roster = Call GET "/api/v1/agent/projects/$($proj1.id)/creators?page=1&pageSize=5"
            Record 'Proj' 'roster_list' 'PASS' "records=$(($roster.data.records|Measure-Object).Count)"
        } catch { Record 'Proj' 'roster_list' 'FAIL' $_.Exception.Message }
    } else { Record 'Proj' 'project_detail' 'SKIP' 'no project' }
    try {
        $tpl = Call GET '/api/v1/agent/projects/templates'
        Record 'Proj' 'project_templates' 'PASS' "count=$(($tpl.data|Measure-Object).Count)"
    } catch { Record 'Proj' 'project_templates' 'WARN' $_.Exception.Message }
} catch { Record 'Proj' 'project_list' 'FAIL' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 5. Creator DB ==========================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try {
    $cs = Call GET '/api/v1/creators?page=1&pageSize=5'
    Record 'Creator' 'creator_list' 'PASS' "records=$(($cs.data.records|Measure-Object).Count) total=$($cs.data.total)"
} catch { Record 'Creator' 'creator_list' 'FAIL' $_.Exception.Message }

try {
    $as = Call GET '/api/v1/creators/accounts?page=1&pageSize=5'
    $total = $as.data.total
    Record 'Creator' 'account_list' 'PASS' "records=$(($as.data.records|Measure-Object).Count) total=$total"
    $first = $as.data.records | Select-Object -First 1
    if ($first) {
        try { $det = Call GET "/api/v1/creators/accounts/$($first.id)"; Record 'Creator' 'account_detail' 'PASS' "id=$($first.id) handle=$($det.data.handle)" } catch { Record 'Creator' 'account_detail' 'FAIL' $_.Exception.Message }
        try { $posts = Call GET "/api/v1/creators/accounts/$($first.id)/posts?page=1&pageSize=3"; Record 'Creator' 'account_posts' 'PASS' "posts=$(($posts.data.records|Measure-Object).Count)" } catch { Record 'Creator' 'account_posts' 'WARN' $_.Exception.Message }
    }
} catch { Record 'Creator' 'account_list' 'FAIL' $_.Exception.Message }

try {
    $cf = Call GET '/api/v1/creators/custom-fields'
    Record 'Creator' 'custom_fields' 'PASS' "count=$(($cf.data|Measure-Object).Count)"
} catch { Record 'Creator' 'custom_fields' 'WARN' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 6. Skills & tasks ======================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try {
    $sk = Call GET '/api/v1/agent/skills'
    Record 'Skill' 'skill_list' 'PASS' "count=$(($sk.data|Measure-Object).Count)"
} catch { Record 'Skill' 'skill_list' 'FAIL' $_.Exception.Message }

try {
    $tk = Call GET '/api/v1/agent/skills/tasks?page=1&pageSize=5'
    Record 'Skill' 'skill_tasks' 'PASS' "records=$(($tk.data.records|Measure-Object).Count)"
} catch { Record 'Skill' 'skill_tasks' 'WARN' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 7. Admin side =========================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
foreach ($p in @(
    @{n='admin_users';         path='/api/v1/admin/users?page=1&pageSize=5'},
    @{n='admin_activities';    path='/api/v1/admin/user-activities?page=1&pageSize=3'},
    @{n='admin_overview';      path='/api/v1/admin/usage/overview'},
    @{n='admin_rate_limits';   path='/api/v1/admin/rate-limits'},
    @{n='admin_model_provs';   path='/api/v1/admin/model-providers'},
    @{n='admin_orgtags';       path='/api/v1/admin/org-tags'},
    @{n='admin_orgtag_tree';   path='/api/v1/admin/org-tags/tree'},
    @{n='recharge_pkgs';       path='/api/v1/admin/recharge-packages'}
)) {
    try { $r=Call GET $p.path; Record 'Admin' $p.n 'PASS' '' } catch { Record 'Admin' $p.n 'WARN' $_.Exception.Message }
}

# ------------------------------------------------------------------
Write-Host "`n== 8. Spotlight direct =====================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
# Direct MAPI call from backend container to confirm Spotlight OAuth still lives
$probe = @'
set -e
AT=225f18c94840cc9f1e7c41f27610bb94
curl -s -o /tmp/bal.json -w "HTTP=%{http_code}\n" -X POST https://mapi.xiaohongshu.com/api/open/jg/account/balance/info \
    -H "Content-Type: application/json" -H "Access-Token: ${AT}" \
    -d "{\"advertiser_id\":974949}"
head -c 280 /tmp/bal.json; echo
'@
try {
    # 用 LF 行尾，避免 bash 把 CRLF 里的 \r 当成 `set -e\r` 的无效参数
    $probeLf = ($probe -replace "`r`n","`n") -replace "`r","`n"
    [System.IO.File]::WriteAllText("$env:TEMP\sl_probe.sh", $probeLf, (New-Object System.Text.UTF8Encoding $false))
    docker cp "$env:TEMP\sl_probe.sh" paismart-backend:/tmp/sl_probe.sh | Out-Null
    $out = docker exec paismart-backend bash /tmp/sl_probe.sh 2>&1 | Out-String
    $ok = $out -match 'HTTP=200' -and $out -match '"code":\s*0'
    $snippet = ($out.Trim() -replace "`r?`n",' ')
    if ($snippet.Length -gt 200) { $snippet = $snippet.Substring(0,200) }
    Record 'Spotlight' 'balance_info_live' $(if($ok){'PASS'}else{'WARN'}) $snippet
} catch { Record 'Spotlight' 'balance_info_live' 'FAIL' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== 9. CDP / Qiangua =======================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
$cdp = docker exec paismart-backend bash -lc "curl -sS -o /dev/null -w 'HTTP=%{http_code}' --max-time 5 http://host.docker.internal:9223/json/version 2>&1 || echo 'NO_CDP'"
if ($cdp -match 'HTTP=200') {
    Record 'Qiangua' 'cdp_bridge' 'PASS' 'cdp-proxy 9223 reachable'
} else {
    Record 'Qiangua' 'cdp_bridge' 'WARN' '请双击 acceptance\start-qiangua-chrome.bat 启动业务员 Chrome + CDP 代理 (9222->9223)'
}

# ------------------------------------------------------------------
Write-Host "`n== 10. Frontend ===========================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
try { $fe = Invoke-WebRequest -Uri 'http://127.0.0.1/' -TimeoutSec 10 -UseBasicParsing; if($fe.StatusCode -eq 200){Record 'FE' 'index' 'PASS' "bytes=$($fe.RawContentLength)"}else{Record 'FE' 'index' 'WARN' "$($fe.StatusCode)"} } catch { Record 'FE' 'index' 'FAIL' $_.Exception.Message }

# ------------------------------------------------------------------
Write-Host "`n== Summary ===============================================" -ForegroundColor Cyan
# ------------------------------------------------------------------
$pass = ($results | Where-Object Status -EQ PASS).Count
$warn = ($results | Where-Object Status -EQ WARN).Count
$fail = ($results | Where-Object Status -EQ FAIL).Count
$skip = ($results | Where-Object Status -EQ SKIP).Count
Write-Host ("PASS={0}  WARN={1}  FAIL={2}  SKIP={3}  TOTAL={4}" -f $pass,$warn,$fail,$skip,$results.Count) -ForegroundColor Cyan
if ($fail -gt 0) { Write-Host "FAIL cases:" -ForegroundColor Red; $results | Where-Object Status -EQ FAIL | ForEach-Object { Write-Host ("  [{0}] {1} :: {2}" -f $_.Area,$_.Case,$_.Detail) -ForegroundColor Red } }
if ($warn -gt 0) { Write-Host "WARN cases:" -ForegroundColor Yellow; $results | Where-Object Status -EQ WARN | ForEach-Object { Write-Host ("  [{0}] {1} :: {2}" -f $_.Area,$_.Case,$_.Detail) -ForegroundColor Yellow } }
$results | Export-Csv -Path 'd:\Project\AI\PaiSmart\acceptance\smoke-result.csv' -Encoding UTF8 -NoTypeInformation
Write-Host "`n-> CSV saved to acceptance/smoke-result.csv" -ForegroundColor Green
