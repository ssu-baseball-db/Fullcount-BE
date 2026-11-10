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
