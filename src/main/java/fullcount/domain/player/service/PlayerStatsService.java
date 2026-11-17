package fullcount.domain.player.service;

import fullcount.domain.player.repository.PlayerRepository;
import fullcount.domain.player.repository.StatType;
import fullcount.global.ApiErrors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlayerStatsService {
  private static final List<String> TEAM_SORTS = List.of("wins", "losses", "runs", "homeRuns",
      "stolenBases", "avg", "ops", "runsAllowed", "era", "whip");
  private final PlayerRepository repository;

  public PlayerStatsService(PlayerRepository repository) {
    this.repository = repository;
  }

  public List<Map<String, Object>> findSeasonStats(StatType type, int year, String teamId, String sort,
      Boolean qualified, boolean leagueRanking) {
    if (sort != null && !type.metrics.contains(sort) && !List.of("obp", "slg").stream()
        .anyMatch(field -> type == StatType.BATTER && field.equals(sort))) {
      throw ApiErrors.bad("지원하지 않는 정렬 항목입니다.");
    }
    boolean battingRateRanking = leagueRanking && type == StatType.BATTER && type.rates.contains(sort == null ? "" : sort);
    if (battingRateRanking && Boolean.FALSE.equals(qualified)) {
      throw ApiErrors.bad("타격 비율 순위는 규정타석 충족 선수만 조회할 수 있습니다.");
    }
    Boolean filter = battingRateRanking ? Boolean.TRUE : qualified;
    List<Map<String, Object>> rows = repository.findStats(type, year, teamId, null);
    rows.forEach(row -> addSeasonMetrics(type, row));
    return rows.stream()
        .filter(row -> filter == null || filter.equals(row.get("qualified")))
        .filter(row -> !battingRateRanking || row.get(sort) != null)
        .sorted(order(sort, "playerId"))
        .toList();
  }

  public Map<String, Object> findPlayerStats(String playerId, StatType type, String typeName) {
    Map<String, Object> player = repository.findPlayer(playerId);
    List<Map<String, Object>> rows = repository.findStats(type, null, null, playerId);
    rows.forEach(row -> addSeasonMetrics(type, row));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("player", player);
    result.put("type", typeName);
    result.put("seasons", rows);
    result.put("career", totals(type, rows));
    return result;
  }

  public Map<String, Object> findTeamPlayers(StatType type, int year, String teamId, String sort) {
    requireTeam(repository.findTeams(year), teamId);
    List<Map<String, Object>> players = findSeasonStats(type, year, teamId, sort, null, false);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("players", players);
    result.put("totals", totals(type, players));
    return result;
  }

  public List<Map<String, Object>> findTeamStats(int year, String sort) {
    if (sort != null && !TEAM_SORTS.contains(sort)) throw ApiErrors.bad("지원하지 않는 정렬 항목입니다.");
    List<Map<String, Object>> batters = repository.findStats(StatType.BATTER, year, null, null);
    List<Map<String, Object>> pitchers = repository.findStats(StatType.PITCHER, year, null, null);
    return repository.findTeams(year).stream().map(team -> {
      Map<String, Object> batting = totals(StatType.BATTER, byTeam(batters, team));
      Map<String, Object> pitching = totals(StatType.PITCHER, byTeam(pitchers, team));
      Map<String, Object> result = new LinkedHashMap<>(team);
      for (String key : TEAM_SORTS) {
        Map<String, Object> values = List.of("wins", "losses", "runsAllowed", "era", "whip").contains(key)
            ? pitching : batting;
        result.put(key, values == null ? null : values.get(key));
      }
      return result;
    }).sorted(order(sort, "teamId")).toList();
  }

  public Map<String, Object> compare(String playerA, String playerB, StatType type, String typeName, Integer season) {
    if (playerA.isBlank() || playerB.isBlank() || playerA.equals(playerB)) {
      throw ApiErrors.bad("서로 다른 선수 두 명을 선택해 주세요.");
    }
    Map<String, Object> first = repository.findPlayer(playerA);
    Map<String, Object> second = repository.findPlayer(playerB);
    List<Map<String, Object>> firstRows = repository.findStats(type, null, null, playerA);
    List<Map<String, Object>> secondRows = repository.findStats(type, null, null, playerB);
    if (firstRows.isEmpty() || secondRows.isEmpty()) throw ApiErrors.bad("같은 기록 유형의 선수를 선택해 주세요.");
    Map<String, Object> a = totals(type, forSeason(firstRows, season));
    Map<String, Object> b = totals(type, forSeason(secondRows, season));
    List<Map<String, Object>> metrics = new ArrayList<>();
    for (String key : type.metrics) {
      Number left = a == null ? null : number(a, key);
      Number right = b == null ? null : number(b, key);
      Map<String, Object> metric = new LinkedHashMap<>();
      metric.put("key", key);
      metric.put("playerA", left);
      metric.put("playerB", right);
      metric.put("better", better(key, left, right));
      metrics.add(metric);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", typeName);
    result.put("season", season);
    result.put("playerA", comparedPlayer(first, a != null));
    result.put("playerB", comparedPlayer(second, b != null));
    result.put("metrics", metrics);
    return result;
  }

  public List<Map<String, Object>> similar(String id, StatType type, int year) {
    repository.findPlayer(id);
    List<Map<String, Object>> rows = repository.findStats(type, year, null, null);
    rows.forEach(row -> addSeasonMetrics(type, row));
    List<Map<String, Object>> eligible = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("qualified")))
        .filter(row -> type.metrics.stream().allMatch(field -> row.get(field) != null)).toList();
    Map<String, Object> target = eligible.stream().filter(row -> id.equals(row.get("playerId"))).findFirst().orElse(null);
    if (target == null) return List.of();
    Map<String, Double> deviations = new LinkedHashMap<>();
    for (String key : type.metrics) {
      double mean = eligible.stream().mapToDouble(row -> number(row, key).doubleValue()).average().orElseThrow();
      double variance = eligible.stream().mapToDouble(row -> Math.pow(number(row, key).doubleValue() - mean, 2))
          .average().orElseThrow();
      deviations.put(key, Math.sqrt(variance));
    }
    return eligible.stream().filter(row -> !id.equals(row.get("playerId"))).map(row -> {
      double distance = Math.sqrt(type.metrics.stream().mapToDouble(key -> {
        double deviation = deviations.get(key);
        return deviation == 0 ? 0 : Math.pow((number(row, key).doubleValue() - number(target, key).doubleValue()) / deviation, 2);
      }).average().orElseThrow());
      return Map.<String, Object>of("player", playerFields(row), "similarityPct", 100 / (1 + distance));
    }).sorted(order("similarityPct", null)).limit(5).toList();
  }

  public Map<String, Object> predict(int year, String teamA, String teamB) {
    if (teamA.equals(teamB)) throw ApiErrors.bad("서로 다른 두 구단을 선택해 주세요.");
    List<Map<String, Object>> teams = findTeamStats(year, null);
    Map<String, Object> first = requireTeam(teams, teamA);
    Map<String, Object> second = requireTeam(teams, teamB);
    String offense = better("ops", number(first, "ops"), number(second, "ops"));
    String defense = better("era", number(first, "era"), number(second, "era"));
    String favored = null;
    if (offense != null && defense != null && (offense.equals(defense) || "tie".equals(offense) || "tie".equals(defense))) {
      String winner = "tie".equals(offense) ? defense : offense;
      favored = switch (winner) {
        case "A" -> teamA;
        case "B" -> teamB;
        default -> null;
      };
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("favoredTeamId", favored);
    result.put("teams", List.of(first, second));
    return result;
  }

  public void addSeasonMetrics(StatType type, Map<String, Object> row) {
    addRates(type, row);
    Number amount = number(row, type.qualificationField);
    row.put("qualified", amount == null ? null : amount.longValue() >= type.minimum);
  }

  private Map<String, Object> totals(StatType type, List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return null;
    Map<String, Object> totals = new LinkedHashMap<>();
    for (String field : type.counts) {
      long sum = 0;
      boolean hasValue = false;
      for (Map<String, Object> row : rows) {
        Number value = number(row, field);
        if (value != null) {
          sum += value.longValue();
          hasValue = true;
        }
      }
      totals.put(field, hasValue ? sum : null);
    }
    addRates(type, totals);
    return totals;
  }

  private void addRates(StatType type, Map<String, Object> row) {
    if (type == StatType.BATTER) {
      row.put("avg", ratio(row, 1, List.of("hits"), List.of("atBats")));
      row.put("obp", ratio(row, 1, List.of("hits", "walks", "hitByPitch"),
          List.of("atBats", "walks", "hitByPitch", "sacrificeFlies")));
      row.put("slg", ratio(row, 1, List.of("hits", "doubles", "triples", "triples",
          "homeRuns", "homeRuns", "homeRuns"), List.of("atBats")));
      row.put("ops", sum(row, List.of("obp", "slg")));
    } else {
      row.put("era", ratio(row, 27, List.of("earnedRuns"), List.of("outsPitched")));
      row.put("whip", ratio(row, 3, List.of("hitsAllowed", "walksAllowed"), List.of("outsPitched")));
      row.put("winningPct", ratio(row, 1, List.of("wins"), List.of("wins", "losses")));
    }
  }

  private Double ratio(Map<String, Object> row, int multiplier, List<String> top, List<String> bottom) {
    Double numerator = sum(row, top);
    Double denominator = sum(row, bottom);
    return numerator == null || denominator == null || denominator == 0 ? null : numerator * multiplier / denominator;
  }

  private Double sum(Map<String, Object> row, List<String> fields) {
    double total = 0;
    for (String field : fields) {
      Number value = number(row, field);
      if (value == null) return null;
      total += value.doubleValue();
    }
    return total;
  }

  private static Number number(Map<String, Object> row, String key) {
    return (Number) row.get(key);
  }

  private static boolean ascending(String key) {
    return List.of("era", "whip", "losses", "runsAllowed").contains(key);
  }

  private static String better(String key, Number first, Number second) {
    if (first == null || second == null) return null;
    int comparison = Double.compare(first.doubleValue(), second.doubleValue());
    if (comparison == 0) return "tie";
    return (ascending(key) ? comparison < 0 : comparison > 0) ? "A" : "B";
  }

  private Comparator<Map<String, Object>> order(String key, String tieField) {
    Comparator<Map<String, Object>> order = (first, second) -> 0;
    if (key != null) {
      Comparator<Double> direction = ascending(key) ? Comparator.naturalOrder() : Comparator.reverseOrder();
      order = Comparator.comparing(row -> row.get(key) instanceof Number value ? value.doubleValue() : null,
          Comparator.nullsLast(direction));
    }
    return tieField == null ? order : order.thenComparing(row -> (String) row.get(tieField));
  }

  private Map<String, Object> requireTeam(List<Map<String, Object>> teams, String id) {
    return teams.stream().filter(team -> id.equals(team.get("teamId"))).findFirst()
        .orElseThrow(() -> ApiErrors.missing("구단을 찾을 수 없습니다."));
  }

  private List<Map<String, Object>> byTeam(List<Map<String, Object>> rows, Map<String, Object> team) {
    return rows.stream().filter(row -> team.get("teamId").equals(row.get("teamId"))).toList();
  }

  private List<Map<String, Object>> forSeason(List<Map<String, Object>> rows, Integer season) {
    return season == null ? rows : rows.stream().filter(row -> number(row, "season").intValue() == season).toList();
  }

  private Map<String, Object> comparedPlayer(Map<String, Object> player, boolean hasRecord) {
    return Map.of("playerId", player.get("playerId"), "playerName", player.get("playerName"), "hasRecord", hasRecord);
  }

  private Map<String, Object> playerFields(Map<String, Object> row) {
    Map<String, Object> player = new LinkedHashMap<>();
    for (String field : List.of("playerId", "playerName", "birthDate", "heightCm", "weightKg", "throwHand", "batHand")) {
      player.put(field, row.get(field));
    }
    return player;
  }
}
