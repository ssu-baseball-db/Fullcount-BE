# Fullcount-BE

데이터베이스 응용 팀 프로젝트 **풀카운트**의 백엔드 저장소. Spring Boot + JPA + SQLite로 구성한다. React + Vite 프론트엔드는 [Fullcount-FE](https://github.com/ssu-baseball-db/Fullcount-FE)에서 관리한다.

---

## 1. 레포 구조

```text
.
├── src/            # Spring Boot 애플리케이션과 JPA 설정
├── db/             # SQLite 스키마, 시드 SQL, 초기화 스크립트
├── gradle/         # Gradle Wrapper 설정
├── build.gradle    # 의존성과 빌드 설정
└── README.md       # 실행 방법과 협업 컨벤션
```

---

## 2. 시작하기

백엔드 저장소를 내려받고 `build.gradle`이 있는 디렉터리에서 실행한다. JDK 21과 SQLite 3.37 이상이 포함된 Python 3가 필요하다.

```bash
python3 db/init.py
./gradlew bootRun
```

DB 초기화는 최초 한 번 실행한다. 초기화 시 공식 팀 순위 120행, 종료 경기 8,640행과 외래 키를 확인한다. Spring Boot는 `data/fullcount.db`에 연결하고 스키마는 `db/`의 SQL로 관리한다. `ddl-auto=none`과 연결별 외래 키 검사를 적용한다.

서버 실행 후 [Swagger UI](http://localhost:8080/swagger-ui/index.html)에서 API를 확인한다. 명세 원본은 [openapi.yaml](src/main/resources/static/openapi.yaml)에 있다.

### 선수 기록과 경기 상세 데이터

시즌 기록은 `FULLCOUNT_ADMIN_TOKEN`을 설정한 뒤 Swagger의 관리자 인증을 통해 `POST /api/admin/imports`로 넣는다. UTF-8 타자·투수 CSV를 각각 `battingCsv`, `pitchingCsv`로 보낸다. 필수 헤더는 `playerId,playerName,season,team`이며, 기록 헤더는 명세의 원천 필드 이름 또는 `PA`, `HR`, `IP`처럼 지원되는 약어를 사용한다. 오류 행은 롤백되고 파일별 성공 건수와 오류 행 번호가 반환된다.

경기 이닝별 점수, 선수별 기록, 이벤트는 초기 시드에 없다. 종료 경기의 요약을 요청하면 저장된 요약을 먼저 확인하고, 이닝별 점수나 타격·투구 기록이 비어 있으면 `FULLCOUNT_GAME_DETAIL_URL`에 설정한 제공처에서 해당 경기만 조회한다. 예를 들어 `http://localhost:18085/games/{gameId}`를 지정하면 백엔드가 해당 주소로 GET을 보낸다. 사용 권한이 있는 데이터를 반환하는 제공처를 별도로 운영해야 한다. 경기 ID, 날짜, 홈·원정 구단 ID, 최종 점수와 이닝 합계를 검증하고 상세 기록을 한 트랜잭션에서 저장한다.

로컬에서 공개 연구 아카이브를 조회하려면 `python3 db/game_detail_source.py data/fullcount.db`를 실행하고 `FULLCOUNT_GAME_DETAIL_URL=http://127.0.0.1:18085/games/{gameId}`를 설정한다. 첫 요청에 압축 아카이브를 내려받아 메모리에 보관하며, 경기별 요청에는 해당 경기 원본만 파싱한다. SQLite에는 요청된 경기만 저장된다. 공백 시즌(2020년 등)과 미수록 경기는 404를 반환한다. 선수는 시즌·구단·이름으로 유일하게 매칭되는 행만 제공한다. 이벤트는 박스스코어의 결승타와 홈런에서 만든다. [원본과 수록 범위](https://datapopcorn.ai/blog/kbo-pitch-clock-game-time-analysis)

제공처는 다음 형식의 JSON을 반환한다. 배열의 예시는 필드 형식을 보여 주며, 실제 응답에는 경기의 이닝별 점수와 타격·투구 기록이 들어가야 한다. 선수 ID는 DB에 등록된 ID를 쓰고, 투구 이닝은 아웃카운트로 전달한다. 이벤트가 없으면 빈 배열을 사용한다. 제공처에 해당 경기가 없으면 404를 반환한다.

```json
{
  "gameId": "20240323SSKT0",
  "date": "2024-03-23",
  "homeTeamId": "KT",
  "awayTeamId": "SAMSUNG",
  "homeScore": 2,
  "awayScore": 6,
  "inningScores": [{"inning": 10, "half": "T", "runs": 4}],
  "battingStats": [{"playerId": "51417", "teamSide": "A", "stats": {"atBats": 1, "hits": 1}}],
  "pitchingStats": [{"playerId": "54452", "teamSide": "A", "stats": {"outsPitched": 18, "strikeouts": 8}}],
  "events": [{"sequence": 1, "inning": 10, "half": "T", "description": "김현준 결승 적시타"}]
}
```

`FULLCOUNT_SUMMARY_URL`의 요약 서비스는 `{ "instructions": "...", "game": {...} }`를 받아 `{ "summary": "..." }`를 반환해야 한다. `game.events`에는 SQLite에 저장된 주요 이벤트를 포함한다. 인증이 필요한 경우 `FULLCOUNT_SUMMARY_TOKEN`을 설정한다.

#### 경기 요약 로컬 테스트

팀 노션에서 받은 `fullcount.db`를 백엔드 루트의 `data/fullcount.db`에 배치한다. JDK 21, Python 3, Ollama가 필요하다. Ollama를 기본 주소 `127.0.0.1:11434`에서 실행하고 `ollama pull qwen2.5:1.5b`로 모델을 받는다. 첫 실행 전에 `ollama run qwen2.5:1.5b "안녕"`으로 모델을 준비하면 생성 타임아웃을 줄일 수 있다.

터미널을 각각 열어 백엔드 루트에서 실행한다.

```bash
python3 db/game_detail_source.py data/fullcount.db
python3 db/ollama_summary.py
FULLCOUNT_GAME_DETAIL_URL='http://127.0.0.1:18085/games/{gameId}' \
FULLCOUNT_SUMMARY_URL=http://127.0.0.1:18086/summary ./gradlew bootRun
```

프론트 `feat/3-games-pages`에서 `VITE_USE_MOCK=false npm run dev`로 실행하고 `http://localhost:5173/games/20240323LTSK0`을 연다. **AI 요약 만들기**를 누르면 이닝 점수, 선수 기록, 주요 이벤트와 요약이 표시된다. 새로고침 후 저장된 요약을 다시 확인한다. 이 경기의 확인 기준은 SSG 5–3 승리와 3회말 최정의 결승 홈런이다. 로컬 소형 모델의 출력은 원본 경기 기록과 대조한다.

---

## 3. 브랜치 전략

- `main`은 발표·제출용 안정 버전이다. 검증된 `develop`을 병합한다.
- `develop`은 개발 통합 브랜치다. 최신 `develop`에서 작업 브랜치를 만들고, PR도 `develop`으로 보낸다.

### 브랜치명

```text
<타입>/<이슈번호>-<영문 설명>
```

```text
feat/12-season-standings
fix/15-season-validation
chore/18-github-templates
```

브랜치명은 영어 소문자와 하이픈을 사용한다. 이슈 번호는 해당 저장소의 번호를 사용한다.

---

## 4. 커밋 컨벤션

```text
<타입>: <한국어 설명>
```

```text
feat: 시즌별 공식 팀 순위 API 구현
fix: 시즌 범위 검증 오류 수정
docs: 선수 비교 응답 예시 추가
```

| 타입 | 용도 |
| --- | --- |
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작을 유지하는 코드 구조 변경 |
| `docs` | 문서 수정 |
| `test` | 테스트 코드 추가·수정 |
| `chore` | 빌드·설정 등 기타 작업 |

타입은 영어 소문자로 쓰고 제목 끝에 마침표를 붙이지 않는다. 한 커밋에는 한 작업을 담는다. 본문이 필요하면 제목 다음에 빈 줄을 둔다.

---

## 5. 이슈 컨벤션

제목은 `[타입] 한국어 설명` 형식으로 쓰고 타입은 대문자로 표기한다.

```text
[FEAT] 시즌별 공식 팀 순위 조회
[FIX] 지원 범위 밖 시즌 요청 처리
[CHORE] SQLite 초기화 환경 구성
```

이슈에는 작업 내용과 완료 기준을 적는다. 프론트와 함께 작업하는 기능은 각 저장소에 이슈를 만들고 서로 링크한다.

---

## 6. PR 컨벤션

제목은 이슈와 같은 `[타입] 한국어 설명` 형식을 사용한다.

```text
[FEAT] 시즌별 공식 팀 순위 조회
[REFACTOR] 공식 팀 순위 조회 SQL을 Repository로 이동
```

- PR 본문에 `Closes #이슈번호`, 변경 사항, 확인 방법과 결과를 적는다.
- 팀원 한 명 이상이 승인하면 `develop`에 병합한다.
- API의 경로·요청값·응답이 바뀌면 OpenAPI 명세도 같은 PR에서 갱신한다. 프론트 담당자에게 변경 내용을 공유하고 관련 프론트 이슈나 PR을 링크한다.

---

## 7. 코드 컨벤션

| 영역 | 규칙 |
| --- | --- |
| 패키지 | `fullcount.domain` 아래에서 `player`, `standings`, `game`처럼 기능별로 묶는다 |
| 네이밍 | Java 필드와 JSON은 `camelCase`, 요청·응답 DTO는 `Request`·`Response` 접미사를 사용한다 |
| DB | 기존 ERD의 `snake_case` 테이블·컬럼명을 유지하고 스키마 변경은 SQL에 반영한다 |
| API | `/api` 아래에 두고 요청·응답 변경은 OpenAPI에 함께 반영한다 |
| 테스트 | 테스트 클래스는 대상 이름에 `Test`를 붙이고, DB 조회는 SQLite에서 확인한다 |

세부 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고한다.
