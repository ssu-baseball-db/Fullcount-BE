#!/bin/bash
# 로컬 일괄 실행 스크립트
# Ollama 확인 → 경기 상세 원천(18085)·요약 서비스(18086)·SQL 시연(18087) → 백엔드(8080) 순서로 켠다.
# 관리자 계정은 백엔드 루트 .env 의 FULLCOUNT_ADMIN_USERNAME, FULLCOUNT_ADMIN_PASSWORD_HASH 를 읽는다.
# Ctrl+C 한 번으로 이 스크립트가 켠 프로세스를 모두 종료한다.
cd "$(dirname "$0")" || exit 1

for port in 8080 18085 18086 18087; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[run-local] $port 포트를 이미 다른 프로세스가 사용 중입니다. 먼저 종료해 주세요: lsof -nP -iTCP:$port -sTCP:LISTEN"
    exit 1
  fi
done

# SQL 시연(db/sql_preview.py)이 Python 3.11 부터 있는 sqlite3 기능을 쓴다
if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 11))' 2>/dev/null; then
  echo "[run-local] Python 3.11 이상이 필요합니다. 현재: $(python3 --version 2>&1 || echo '없음')"
  exit 1
fi

if [ -f .env ]; then
  set -a; source .env; set +a
fi
if ! grep -qs '^fullcount\.admin\.username=.' config/application.properties \
  && { [ -z "$FULLCOUNT_ADMIN_USERNAME" ] || [ -z "$FULLCOUNT_ADMIN_PASSWORD_HASH" ]; }; then
  echo "[run-local] 경고: 관리자 계정 설정이 없어 관리자 로그인이 되지 않습니다 (config/application.properties 또는 .env)."
fi
# 로컬은 HTTP 이므로 세션 쿠키의 Secure 속성을 끈다 (EC2 HTTPS 에서는 설정하지 않는다)
export SERVER_SERVLET_SESSION_COOKIE_SECURE="${SERVER_SERVLET_SESSION_COOKIE_SECURE:-false}"

PIDS=()
cleanup() { kill "${PIDS[@]}" 2>/dev/null; }
trap cleanup EXIT INT TERM

if ! curl -s http://127.0.0.1:11434 >/dev/null; then
  echo "[run-local] Ollama 시작 (로그: /tmp/fullcount-ollama.log)"
  ollama serve >/tmp/fullcount-ollama.log 2>&1 &
  PIDS+=($!)
  sleep 2
fi

echo "[run-local] 경기 상세 원천 시작: 127.0.0.1:18085 (로그: /tmp/fullcount-detail.log)"
python3 db/game_detail_source.py data/fullcount.db >/tmp/fullcount-detail.log 2>&1 &
PIDS+=($!)

echo "[run-local] 요약 서비스 시작: 127.0.0.1:18086 (로그: /tmp/fullcount-summary.log)"
python3 db/ollama_summary.py >/tmp/fullcount-summary.log 2>&1 &
PIDS+=($!)

# SQL 시연은 원본이 아닌 스냅샷(data/fullcount-demo.db)을 읽는다. 없으면 원본을 읽기 전용으로 백업해 만든다
if [ ! -f data/fullcount-demo.db ]; then
  echo "[run-local] SQL 시연용 스냅샷 생성: data/fullcount-demo.db"
  python3 -c "import sqlite3; s=sqlite3.connect('file:data/fullcount.db?mode=ro', uri=True); d=sqlite3.connect('data/fullcount-demo.db'); s.backup(d); d.close(); s.close()" || exit 1
fi
echo "[run-local] SQL 시연 서비스 시작: 18087 (로그: /tmp/fullcount-sql-preview.log)"
python3 db/sql_preview.py data/fullcount-demo.db >/tmp/fullcount-sql-preview.log 2>&1 &
PIDS+=($!)

echo "[run-local] 백엔드 시작: http://localhost:8080/swagger-ui/index.html"
FULLCOUNT_GAME_DETAIL_URL='http://127.0.0.1:18085/games/{gameId}' \
FULLCOUNT_SUMMARY_URL=http://127.0.0.1:18086/summary \
FULLCOUNT_SQL_PREVIEW_URL=http://127.0.0.1:18087/preview \
sh ./gradlew bootRun   # ZIP 해제로 실행 권한이 빠져도 동작하도록 sh 로 실행한다
