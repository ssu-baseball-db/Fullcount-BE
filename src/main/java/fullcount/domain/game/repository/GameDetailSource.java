package fullcount.domain.game.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record GameDetailSource(String gameId, String date, String homeTeamId, String awayTeamId,
    Integer homeScore, Integer awayScore, List<Inning> inningScores,
    List<PlayerLine> battingStats, List<PlayerLine> pitchingStats, List<Event> events) {

  public boolean matches(Map<String, Object> game) {
    if (!game.get("gameId").equals(gameId) || !game.get("date").equals(date)
        || !((Map<?, ?>) game.get("homeTeam")).get("teamId").equals(homeTeamId)
        || !((Map<?, ?>) game.get("awayTeam")).get("teamId").equals(awayTeamId)
        || !game.get("homeScore").equals(homeScore) || !game.get("awayScore").equals(awayScore)
        || inningScores == null || inningScores.isEmpty()
        || !validPlayers(battingStats) || !validPlayers(pitchingStats) || events == null) return false;
    long homeRuns = 0;
    long awayRuns = 0;
    Set<String> innings = new HashSet<>();
    for (Inning score : inningScores) {
      if (score == null || score.inning() == null || score.inning() < 1
          || score.runs() == null || score.runs() < 0
          || !("T".equals(score.half()) || "B".equals(score.half()))
          || !innings.add(score.inning() + score.half())) return false;
      if ("T".equals(score.half())) awayRuns += score.runs();
      else homeRuns += score.runs();
    }
    Set<Integer> sequences = new HashSet<>();
    for (Event event : events) {
      if (event == null || event.sequence() == null || event.sequence() < 1
          || !sequences.add(event.sequence()) || !innings.contains(event.inning() + event.half())
          || event.description() == null || event.description().isBlank()) return false;
    }
    return homeRuns == homeScore && awayRuns == awayScore;
  }

  private static boolean validPlayers(List<PlayerLine> lines) {
    if (lines == null || lines.isEmpty()) return false;
    Set<String> ids = new HashSet<>();
    return lines.stream().allMatch(line -> line != null && line.playerId() != null && !line.playerId().isBlank()
        && ids.add(line.playerId()) && ("H".equals(line.teamSide()) || "A".equals(line.teamSide()))
        && line.stats() != null && !line.stats().isEmpty());
  }

  public record Inning(Integer inning, String half, Integer runs) {}

  public record PlayerLine(String playerId, String teamSide, Map<String, Long> stats) {}

  public record Event(Integer sequence, Integer inning, String half, String description) {}
}
