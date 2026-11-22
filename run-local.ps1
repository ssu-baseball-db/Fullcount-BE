# 로컬 일괄 실행 스크립트 (Windows PowerShell)
# run-local.sh 와 같은 순서로 Ollama 확인 → 경기 상세 원천(18085)·요약 서비스(18086)·SQL 시연(18087) → 백엔드(8080) 를 켠다.
# 실행: Fullcount-BE 폴더에서  powershell -ExecutionPolicy Bypass -File .\run-local.ps1
# 관리자 계정은 config\application.properties 또는 .env 의 FULLCOUNT_ADMIN_USERNAME, FULLCOUNT_ADMIN_PASSWORD_HASH 를 읽는다.
# 종료: Ctrl+C. "일괄 작업을 끝내시겠습니까 (Y/N)?" 가 나오면 Y. 이 스크립트가 켠 프로세스를 모두 종료한다.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$logDir = $env:TEMP

function Write-Step($message) { Write-Host "[run-local] $message" }

function Test-PortInUse([int]$port) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $async = $client.BeginConnect('127.0.0.1', $port, $null, $null)
    return ($async.AsyncWaitHandle.WaitOne(300) -and $client.Connected)
  } catch {
    return $false
  } finally {
    $client.Close()
  }
}

# Windows 에는 python3 가 없거나 Microsoft Store 안내용 가짜 python 이 있을 수 있어 실제로 실행해 확인한다
function Find-Python {
  foreach ($candidate in @(@('py', '-3'), @('python'), @('python3'))) {
    $exe = $candidate[0]
    $prefix = @($candidate | Select-Object -Skip 1)
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { continue }
    try {
      $version = & $exe @prefix --version 2>&1 | Out-String
      # SQL 시연(db\sql_preview.py)이 Python 3.11 부터 있는 sqlite3 기능을 쓴다
      if ($version -match 'Python 3\.(\d+)' -and [int]$Matches[1] -ge 11) { return @{ Exe = $exe; Prefix = $prefix } }
    } catch { }
  }
  return $null
}

$started = New-Object System.Collections.Generic.List[System.Diagnostics.Process]

function Start-Background($name, $exe, [string[]]$arguments) {
  $out = Join-Path $logDir "fullcount-$name.log"
  $err = Join-Path $logDir "fullcount-$name.err.log"
  $process = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput $out -RedirectStandardError $err -NoNewWindow -PassThru
  $started.Add($process)
  return $process
}

if (Test-PortInUse 8080) {
  Write-Step "8080 포트를 이미 다른 프로세스가 사용 중입니다. 먼저 종료해 주세요: Get-NetTCPConnection -LocalPort 8080"
  exit 1
}
foreach ($port in 18085, 18086, 18087) {
  if (Test-PortInUse $port) {
    Write-Step "$port 포트를 이미 사용 중입니다. 이전에 실행한 python 프로세스를 종료해 주세요."
    exit 1
  }
}

# .env 를 읽어 환경 변수로 넣는다 (KEY=VALUE, # 주석 무시)
if (Test-Path -LiteralPath '.env') {
  foreach ($line in Get-Content -LiteralPath '.env' -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
      Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2].Trim().Trim('"').Trim("'")
    }
  }
}
$hasConfigAdmin = (Test-Path -LiteralPath 'config\application.properties') -and
  (Select-String -LiteralPath 'config\application.properties' -Pattern '^fullcount\.admin\.username=.+' -Quiet)
if (-not $hasConfigAdmin -and (-not $env:FULLCOUNT_ADMIN_USERNAME -or -not $env:FULLCOUNT_ADMIN_PASSWORD_HASH)) {
  Write-Step "경고: 관리자 계정 설정이 없어 관리자 로그인이 되지 않습니다 (config\application.properties 또는 .env)."
}
# 로컬은 HTTP 이므로 세션 쿠키의 Secure 속성을 끈다 (EC2 HTTPS 에서는 설정하지 않는다)
if (-not $env:SERVER_SERVLET_SESSION_COOKIE_SECURE) { $env:SERVER_SERVLET_SESSION_COOKIE_SECURE = 'false' }

$python = Find-Python
if (-not $python) {
  Write-Step "Python 3.11 이상을 찾지 못했습니다. https://www.python.org/downloads/ 에서 설치해 주세요."
  exit 1
}
$py = $python.Exe
$pyPrefix = [string[]]$python.Prefix

try {
  if (-not (Test-PortInUse 11434)) {
    if (Get-Command ollama -ErrorAction SilentlyContinue) {
      Write-Step "Ollama 시작 (로그: $logDir\fullcount-ollama.log)"
      Start-Background 'ollama' 'ollama' @('serve') | Out-Null
      Start-Sleep -Seconds 2
    } else {
      Write-Step "경고: Ollama 가 설치되어 있지 않아 AI 요약 생성은 실패합니다. 나머지 기능은 정상 동작합니다."
    }
  }

  Write-Step "경기 상세 원천 시작: 127.0.0.1:18085 (로그: $logDir\fullcount-detail.log)"
  Start-Background 'detail' $py ($pyPrefix + @('db\game_detail_source.py', 'data\fullcount.db')) | Out-Null

  Write-Step "요약 서비스 시작: 127.0.0.1:18086 (로그: $logDir\fullcount-summary.log)"
  Start-Background 'summary' $py ($pyPrefix + @('db\ollama_summary.py')) | Out-Null

  # SQL 시연은 원본이 아닌 스냅샷(data\fullcount-demo.db)을 읽는다. 없으면 원본을 읽기 전용으로 백업해 만든다
  if (-not (Test-Path -LiteralPath 'data\fullcount-demo.db')) {
    Write-Step "SQL 시연용 스냅샷 생성: data\fullcount-demo.db"
    $backup = "import sqlite3; s=sqlite3.connect('file:data/fullcount.db?mode=ro', uri=True); d=sqlite3.connect('data/fullcount-demo.db'); s.backup(d); d.close(); s.close()"
    & $py @pyPrefix -c $backup
    if ($LASTEXITCODE -ne 0) { throw "SQL 시연용 스냅샷을 만들지 못했습니다." }
  }
  Write-Step "SQL 시연 서비스 시작: 18087 (로그: $logDir\fullcount-sql-preview.log)"
  Start-Background 'sql-preview' $py ($pyPrefix + @('db\sql_preview.py', 'data\fullcount-demo.db')) | Out-Null

  Start-Sleep -Seconds 1
  foreach ($process in $started) {
    if ($process.HasExited) {
      Write-Step "경고: 시작 직후 종료된 프로세스가 있습니다. $logDir 의 fullcount-*.err.log 를 확인해 주세요."
      break
    }
  }

  Write-Step "백엔드 시작: http://localhost:8080/swagger-ui/index.html"
  $env:FULLCOUNT_GAME_DETAIL_URL = 'http://127.0.0.1:18085/games/{gameId}'
  $env:FULLCOUNT_SUMMARY_URL = 'http://127.0.0.1:18086/summary'
  $env:FULLCOUNT_SQL_PREVIEW_URL = 'http://127.0.0.1:18087/preview'
  & .\gradlew.bat bootRun
} finally {
  foreach ($process in $started) {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
  }
  Write-Step "이 스크립트가 켠 프로세스를 종료했습니다."
}
