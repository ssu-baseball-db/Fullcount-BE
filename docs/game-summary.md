# 경기 AI 요약 구현

## 요약 생성 흐름

경기 이닝별 점수, 선수별 기록, 이벤트는 초기 시드에 없다. 종료 경기의 요약을 요청하면 저장된 요약을 먼저 확인하고, 이닝별 점수나 타격·투구 기록이 비어 있으면 `FULLCOUNT_GAME_DETAIL_URL`에 설정한 제공처에서 해당 경기만 조회한다. 예를 들어 `http://localhost:18085/games/{gameId}`를 지정하면 백엔드가 해당 주소로 GET을 보낸다. 사용 권한이 있는 데이터를 반환하는 제공처를 별도로 운영해야 한다. 경기 ID, 날짜, 홈·원정 구단 ID, 최종 점수와 이닝 합계를 검증하고 상세 기록을 한 트랜잭션에서 저장한다.

## 경기 원본과 매핑

경기 기록 제공처는 공개 연구 아카이브를 사용한다. 첫 요청에 압축 아카이브를 내려받아 메모리에 보관하며, 경기별 요청에는 해당 경기 원본만 파싱한다. SQLite에는 요청된 경기만 저장된다. 아카이브는 2019년과 2021~2026년 정규시즌을 수록하며, 2026년은 10월 6일까지다. 미수록 경기에는 제공처가 404를 반환한다. 선수는 시즌·구단·이름으로 유일하게 매칭되는 행만 제공한다. 이벤트는 박스스코어의 결승타와 홈런에서 만든다. [원본과 수록 범위](https://datapopcorn.ai/blog/kbo-pitch-clock-game-time-analysis)

## 경기 상세 제공처 응답

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

## AI 요약 서비스 요청과 응답

`FULLCOUNT_SUMMARY_URL`의 요약 서비스는 `{ "instructions": "...", "game": {...} }`를 받아 `{ "summary": "..." }`를 반환해야 한다. `game.events`에는 SQLite에 저장된 주요 이벤트를 포함한다. 인증이 필요한 경우 `FULLCOUNT_SUMMARY_TOKEN`을 설정한다.
