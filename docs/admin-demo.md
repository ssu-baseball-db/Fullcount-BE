# 관리자 화면과 SQL 시연

## AWS에서 준비

1. 백엔드 루트의 `data/fullcount.db`에 팀 공유 DB를 배치한다. SQLite 백업 명령으로 별도 시연 파일을 만든다. 시연에 공개해도 되는 데이터만 스냅샷에 포함한다.

   ```bash
   sqlite3 -readonly data/fullcount.db ".backup data/fullcount-demo.db"
   chmod 644 data/fullcount-demo.db db/sql_preview.py
   ```

2. Python 3로 비밀번호 해시를 생성한다. 관리자 비밀번호를 입력하면 해시만 출력된다.

   ```bash
   python3 - <<'PY'
   import base64, getpass, hashlib, secrets
   password = getpass.getpass("관리자 비밀번호: ").encode()
   salt = secrets.token_bytes(16)
   digest = hashlib.pbkdf2_hmac("sha256", password, salt, 600_000)
   print(":".join(base64.b64encode(value).decode() for value in (salt, digest)))
   PY
   ```

   백엔드 루트의 `.env`에 `FULLCOUNT_ADMIN_USERNAME`과 `FULLCOUNT_ADMIN_PASSWORD_HASH`를 설정하고 `chmod 600 .env`를 실행한다. Swagger·CLI로 기존 관리자 API를 실행할 때는 `FULLCOUNT_ADMIN_TOKEN`도 설정한다.
3. `docker compose up -d --build`로 API와 SQL 시연 서비스를 시작한다. API는 호스트의 `127.0.0.1:8080`으로만 열리고 SQL 서비스는 Compose 내부에서만 접근할 수 있다. 경기 요약 서비스와 AWS HTTPS 프록시 설정은 [EC2 배포 안내](ec2-deploy.md)를 따른다.
4. 프론트 담당자가 Vercel Preview 환경에 `VITE_USE_MOCK=false`와 `FULLCOUNT_API_ORIGIN=https://백엔드-주소`을 설정하고 재배포한다. 프론트 저장소의 `vercel.mjs`가 API rewrite와 화면 주소의 SPA fallback을 구성한다. 프록시가 `Set-Cookie`를 전달하는지 확인한다. 로컬에서 HTTP로 테스트할 때만 `SERVER_SERVLET_SESSION_COOKIE_SECURE=false`를 적용할 수 있다.

## 시연 순서

1. `/admin`을 열어 로그인 화면으로 이동하는지 확인한다.
2. 로그인한 뒤 초기 `SELECT`를 실행한다. 팀 시연 DB에서는 선수 ID `75847`의 이름이 `최정`이다.
3. `UPDATE player SET player_name = '최정 (시연)' WHERE player_id = '75847' RETURNING player_id, player_name;`을 실행해 1행과 변경된 이름을 확인한다. 초기 쿼리를 다시 실행하면 원래 이름이 표시된다.
4. `UPDATE batter_season SET home_runs = -1 WHERE player_id = '75847' AND season_year = 2024;`를 실행해 SQLite 제약조건 위반을 확인한다.
5. 로그아웃하고 `/admin`을 다시 열어 로그인 화면을 확인한다. 실제 DB에 값을 반영해야 할 때는 화면의 **서비스 데이터 관리** 영역을 사용한다.

시연 서비스는 요청마다 동일한 스냅샷을 메모리로 복제한다. 한 문장만 실행할 수 있으며 결과는 100행 및 64KB까지 표시한다. 스냅샷을 갱신할 때는 SQL 서비스를 중지한 뒤 백업 파일을 교체하고 다시 시작한다.
