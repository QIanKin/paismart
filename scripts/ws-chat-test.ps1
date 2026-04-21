param(
    [string]$Token = "",
    [string]$Message = "请使用 creator_get 工具查询 creatorId=1 这个博主的基本信息，并结合 xhs-note-methodology skill 给出她的人设画像评分",
    [int]$ReadSeconds = 60,
    [int]$SessionId = 1,
    [int]$ProjectId = 2
)

if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = Get-Content -Raw "$PSScriptRoot\..\.admin-token.txt"
}

Add-Type -AssemblyName System.Net.Http
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cts = New-Object System.Threading.CancellationTokenSource
$uri = [Uri]"ws://127.0.0.1:8081/chat/$Token"
Write-Host "[ws] connecting to $uri"
$ws.ConnectAsync($uri, $cts.Token).Wait(15000) | Out-Null
if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
    Write-Host "[ws] FAILED to open, state=$($ws.State)"
    exit 1
}
Write-Host "[ws] OPEN"

$payload = @{ type="chat"; content=$Message; sessionId=$SessionId.ToString(); projectId=$ProjectId.ToString() } | ConvertTo-Json -Compress
$bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$bytes)
$ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).Wait()
Write-Host "[ws] sent: $payload"

$buf = New-Object byte[] 16384
$segRecv = New-Object System.ArraySegment[byte] -ArgumentList @(,$buf)
$deadline = (Get-Date).AddSeconds($ReadSeconds)
$acc = New-Object System.Text.StringBuilder
$msgCount = 0
while ((Get-Date) -lt $deadline -and $ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
    try {
        $task = $ws.ReceiveAsync($segRecv, $cts.Token)
        while (-not $task.IsCompleted -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
        if (-not $task.IsCompleted) { break }
        $res = $task.Result
        if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            Write-Host "[ws] server closed: $($res.CloseStatus) - $($res.CloseStatusDescription)"
            break
        }
        $piece = [System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
        $acc.Append($piece) | Out-Null
        if ($res.EndOfMessage) {
            $full = $acc.ToString()
            $acc.Clear() | Out-Null
            $msgCount++
            $preview = if ($full.Length -gt 240) { $full.Substring(0,240) + "..." } else { $full }
            Write-Host "[ws][#$msgCount] $preview"
        }
    } catch {
        Write-Host "[ws] recv error: $($_.Exception.Message)"
        break
    }
}
try { $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", $cts.Token).Wait(5000) | Out-Null } catch {}
Write-Host "[ws] done, total messages: $msgCount"
