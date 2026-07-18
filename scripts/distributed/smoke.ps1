[CmdletBinding()]
param(
    [string]$EnvironmentFile = "deploy/distributed/.env"
)

$ErrorActionPreference = "Stop"

function Write-Success {
    param([string]$Message)
    Write-Host "[通过] $Message" -ForegroundColor Green
}

function Read-EnvironmentFile {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "环境变量文件不存在：$Path。请先复制 deploy/distributed/.env.example。"
    }

    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $values[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    return $values
}

function Test-HttpEndpoint {
    param(
        [string]$Name,
        [string]$Uri,
        [switch]$RequireSpringHealth
    )

    $response = Invoke-WebRequest -UseBasicParsing -NoProxy -Uri $Uri -TimeoutSec 8
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
        throw "$Name 返回异常 HTTP 状态码：$($response.StatusCode)"
    }

    if ($RequireSpringHealth) {
        $content = if ($response.Content -is [byte[]]) {
            [System.Text.Encoding]::UTF8.GetString($response.Content)
        }
        else {
            [string]$response.Content
        }
        $health = $content | ConvertFrom-Json
        if ($health.status -ne "UP") {
            throw "$Name 健康状态不是 UP：$($health.status)"
        }
    }

    Write-Success "$Name 可访问"
}

function Test-TcpEndpoint {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        if (-not $task.Wait([TimeSpan]::FromSeconds(5))) {
            throw "$Name 连接超时：${HostName}:$Port"
        }
        Write-Success "$Name 端口可连接（${HostName}:$Port）"
    }
    finally {
        $client.Dispose()
    }
}

$environment = Read-EnvironmentFile -Path $EnvironmentFile
foreach ($requiredName in @("NACOS_USERNAME", "NACOS_PASSWORD")) {
    if (-not $environment.ContainsKey($requiredName) -or [string]::IsNullOrWhiteSpace($environment[$requiredName])) {
        throw "环境变量文件缺少 $requiredName。"
    }
}

Test-HttpEndpoint -Name "Nacos 控制台" -Uri "http://localhost:8088/"
Test-HttpEndpoint -Name "Sentinel 控制台" -Uri "http://localhost:8858/"
Test-TcpEndpoint -Name "Seata 事务协调器" -HostName "localhost" -Port 8091

$serviceHealthEndpoints = [ordered]@{
    "auth-service"      = "http://localhost:8101/actuator/health"
    "resource-service"  = "http://localhost:8102/actuator/health"
    "detection-service" = "http://localhost:8103/actuator/health"
    "assistant-service" = "http://localhost:8104/actuator/health"
}

foreach ($entry in $serviceHealthEndpoints.GetEnumerator()) {
    Test-HttpEndpoint -Name $entry.Key -Uri $entry.Value -RequireSpringHealth
}

$loginResponse = Invoke-RestMethod -Method Post -NoProxy -Uri "http://localhost:8848/nacos/v3/auth/user/login" -Body @{
    username = $environment.NACOS_USERNAME
    password = $environment.NACOS_PASSWORD
} -TimeoutSec 8

if ([string]::IsNullOrWhiteSpace($loginResponse.accessToken)) {
    throw "Nacos 登录成功响应中缺少 accessToken。"
}

$serviceList = Invoke-RestMethod -Method Get -NoProxy -Uri "http://localhost:8848/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=100" -Headers @{
    Authorization = "Bearer $($loginResponse.accessToken)"
} -TimeoutSec 8

$registeredNames = @($serviceList.data.pageItems | ForEach-Object { $_.name })
foreach ($expectedName in $serviceHealthEndpoints.Keys) {
    if ($expectedName -notin $registeredNames) {
        throw "Nacos 中未找到服务：$expectedName。当前服务：$($registeredNames -join ', ')"
    }
    Write-Success "$expectedName 已注册到 Nacos"
}

Write-Host "分布式基础环境、四个服务及 Nacos 注册检查全部通过。" -ForegroundColor Cyan
