# 풀카운트 백엔드 코드 컨벤션

Spring Boot · Java 21 · Spring Data JPA · SQLite

실행 방법과 브랜치·커밋·PR 규칙은 [README.md](README.md)를 참고한다.

---

## 1. 패키지 구조

```text
fullcount
├── FullcountApplication.java
└── domain/
    ├── player/
    ├── standings/
    └── game/
```

`FullcountApplication`과 기능 패키지를 `fullcount` 아래에 둔다. 각 기능에서 필요한 역할만 추가한다. 여러 기능이 공유하는 설정과 예외 처리가 생기면 `fullcount.global`에 둔다.

| 패키지 | 역할 |
| --- | --- |
| `controller` | HTTP 요청을 받고 입력 형식을 검증한 뒤 응답을 반환한다 |
| `service` | 업무 규칙과 여러 조회·저장을 묶는 흐름을 처리한다 |
| `repository` | JPA 조회·저장과 네이티브 SQL을 관리한다 |
| `entity` | 테이블과 키를 매핑한다 |
| `dto` | API 요청·응답에 필요한 필드를 정의한다 |

선수와 시즌 기록을 함께 저장하는 작업처럼 여러 쓰기가 한 단위로 성공해야 하면 서비스에서 트랜잭션을 관리한다.

---

## 2. 클래스 네이밍

| 계층 | 규칙 | 예시 |
| --- | --- | --- |
| Entity | 도메인 명사 | `Player`, `BatterSeason` |
| Controller | `{도메인}Controller` | `PlayerController` |
| Service | `{도메인}Service` | `PlayerService` |
| Repository | `{도메인}Repository` | `PlayerRepository` |
| DTO | `Request` 또는 `Response` 접미사 | `UpdatePlayerRequest`, `PlayerSearchResponse`, `StandingResponse` |

생성·수정 요청은 `Create{대상}Request`, `Update{대상}Request`로 이름을 짓는다. API 응답에는 DTO를 사용하고, 단순 응답 DTO는 Java `record`로 작성한다.

---

## 3. 메서드 네이밍

| 동작 | 동사 | 예시 |
| --- | --- | --- |
| 생성 | `create` | `createPlayer` |
| 조회 | `find` | `findLatestStandingsBySeason` |
| 수정 | `update` | `updatePlayer` |
| 삭제 | `delete` | `deletePlayer` |

Spring Data JPA가 제공하는 `save`, `findById`, `deleteById` 등은 제공된 이름을 사용한다.

---

## 4. DB 네이밍과 매핑

- SQLite 테이블과 컬럼은 기존 ERD의 `snake_case`를 따른다. `player`, `batter_season`, `team_standing_snapshot`처럼 초기화 SQL에 정의된 이름을 쓴다.
- Java 필드는 `camelCase`를 사용한다. 예를 들어 `season_year`는 `seasonYear`로 표현한다. 자동 매핑 결과와 DB 이름이 다르면 매핑을 명시한다.
- 선수 ID는 문자열로 다룬다. 선수 시즌 기록의 키는 `(playerId, seasonYear)`, 시즌별 구단명 참조 키는 `(teamId, seasonYear)`다. 조인할 때 복합 키 전체를 사용한다.
- 스키마는 SQL로 관리한다. Hibernate의 `ddl-auto`는 `none`을 유지하고 SQLite 연결마다 외래 키 검사를 켠다.
- 쿼리 입력값은 파라미터로 바인딩한다. 정렬 컬럼은 서버가 허용한 항목에서 선택한다.

---

## 5. REST API 엔드포인트 네이밍

- `/api` 아래에 리소스를 두고 복수형 명사를 기본으로 쓴다. 여러 단어로 된 경로는 `kebab-case`로 작성한다.
- 경로 변수는 대상 식별에, 쿼리 파라미터는 검색·필터·비교 조건에 쓴다.

아래는 구현 예정인 API 경로의 예시다.

```text
GET /api/seasons/{year}/standings
GET /api/players?name=김&season=2024
GET /api/players/compare?playerA=sample-a&playerB=sample-b&type=batter&season=2024
```

- 목록 결과가 비어 있으면 `200`과 빈 배열을 반환한다. 잘못된 입력은 `400`, 요청한 선수 ID가 존재하지 않으면 `404`를 사용한다.
- 공식 팀 순위는 시즌 마지막 기준일의 `rank`를 사용한다. 팀·통산 비율 지표는 원천 기록을 합산한 뒤 계산한다. 계산 불가나 미확인 값은 `null`, 실제 0은 `0`으로 반환한다.
- 경로, 요청값, 응답을 바꾸는 PR에는 OpenAPI 명세와 예시도 함께 반영한다.

---

## 6. Enum 네이밍

Java enum 값은 `UPPER_SNAKE_CASE`로 작성한다. 예시는 `GameStatus.COMPLETED`다. JPA로 저장할 때는 `@Enumerated(EnumType.STRING)`을 명시한다. `batter`, `pitcher` 같은 API 값은 명세의 표기를 따른다.

---

## 7. 상수 네이밍

`static final` 상수는 `UPPER_SNAKE_CASE`로 작성한다. 예시는 `MIN_SUPPORTED_SEASON`이다. 여러 곳에서 쓰는 범위나 정책값은 한 곳에서 관리한다.

---

## 8. 테스트 네이밍

- 테스트 클래스는 대상 이름에 `Test`를 붙인다. 예시는 `StandingRepositoryTest`다.
- 테스트 메서드는 상황과 기대 결과가 드러나게 짓는다. 예시는 `지원_범위_밖의_시즌은_400을_반환한다`다.
- SQLite에 의존하는 조회는 SQLite에서 검증한다. 팀 순위는 시즌별 구단명과 공식 순위 정렬을, 선수 비교는 동률·기록 없음·타자와 투수 혼합 선택을 확인한다.
- PR 작성자는 요청값이 어디서 검증되고, 어떤 데이터가 조회·저장되며, 성공과 실패에 어떤 응답이 나가는지 설명할 수 있어야 한다.
