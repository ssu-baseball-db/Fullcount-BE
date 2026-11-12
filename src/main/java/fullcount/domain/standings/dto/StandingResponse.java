package fullcount.domain.standings.dto;

public record StandingResponse (
  int rank,
  String teamId,
  String teamName,
  int wins,
  int losses,
  int draws,
  Number winningPct,
  String asOfDate,
  int games,
  Number gamesBehind
){}
