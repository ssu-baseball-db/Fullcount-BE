package fullcount.domain.game.repository;

import fullcount.domain.player.repository.StatType;
import fullcount.global.ApiErrors;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GameRepository {
  private static final String GAMES = """
      SELECT g.game_id AS gameId, g.game_date AS date, g.stadium,
             g.home_score AS homeScore, g.away_score AS awayScore, g.game_status AS status,
             g.home_team_id AS homeId, h.team_name AS homeName,
             g.away_team_id AS awayId, a.team_name AS awayName
      FROM game g
      JOIN team_season_name h ON h.team_id=g.home_team_id AND h.season_year=g.season_year
      JOIN team_season_name a ON a.team_id=g.away_team_id AND a.season_year=g.season_year
      """;
  private final JdbcClient jdbc;

  public GameRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> findGames(LocalDate date, String teamId) {
    return jdbc.sql(GAMES + """
        WHERE g.game_date=:date AND (:team IS NULL OR :team IN (g.home_team_id,g.away_team_id))
        ORDER BY g.game_no,g.game_id
        """).param("date", date.toString()).param("team", teamId)
        .query().listOfRows().stream().map(this::gameResponse).toList();
  }

  public Map<String, Object> findGame(String id) {
    return jdbc.sql(GAMES + " WHERE g.game_id=:id").param("id", id).query().listOfRows().stream().findFirst()
        .map(this::gameResponse).orElseThrow(() -> ApiErrors.missing("경기를 찾을 수 없습니다."));
  }

  public Map<String, Object> findDetail(String id) {
    Map<String, Object> result = findGame(id);
    result.put("inningScores", jdbc.sql("""
        SELECT inning_no AS inning, half, runs FROM inning_score WHERE game_id=:id
        ORDER BY inning_no, CASE half WHEN 'T' THEN 0 ELSE 1 END
        """).param("id", id).query().listOfRows());
    result.put("battingStats", findPlayerStats(id, StatType.BATTER));
    result.put("pitchingStats", findPlayerStats(id, StatType.PITCHER));
    return result;
  }

  public List<Map<String, Object>> findEvents(String id) {
    findGame(id);
    return jdbc.sql("""
        SELECT sequence, inning_no AS inning, half, description FROM game_event WHERE game_id=:id
        ORDER BY inning_no,sequence
        """).param("id", id).query().listOfRows();
  }

  public Optional<Map<String, Object>> findSummary(String id) {
    return jdbc.sql("""
        SELECT game_id AS gameId, summary_text AS summary,
               strftime('%Y-%m-%dT%H:%M:%SZ',generated_at) AS generatedAt
        FROM game_summary WHERE game_id=:id
        """).param("id", id).query().listOfRows().stream().findFirst();
  }

  public void saveSummary(String id, String summary) {
    jdbc.sql("""
        INSERT INTO game_summary(game_id,summary_text,model_name,generated_at)
        VALUES (:id,:summary,'configured-provider',:time) ON CONFLICT(game_id) DO NOTHING
        """).param("id", id).param("summary", summary).param("time", Instant.now().toString()).update();
  }

  @Transactional
  public void saveDetail(GameDetailSource detail) {
    String id = detail.gameId();
    for (GameDetailSource.Inning score : detail.inningScores()) {
      jdbc.sql("""
          INSERT INTO inning_score(game_id, inning_no, half, runs) VALUES (:id,:inning,:half,:runs)
          ON CONFLICT(game_id, inning_no, half) DO UPDATE SET runs=excluded.runs
          """).param("id", id).param("inning", score.inning()).param("half", score.half())
          .param("runs", score.runs()).update();
    }
    savePlayerLines(id, StatType.BATTER, detail.battingStats());
    savePlayerLines(id, StatType.PITCHER, detail.pitchingStats());
    for (GameDetailSource.Event event : detail.events()) {
      jdbc.sql("""
          INSERT INTO game_event(game_id, sequence, inning_no, half, description)
          VALUES (:id,:sequence,:inning,:half,:description)
          ON CONFLICT(game_id, sequence) DO UPDATE
          SET inning_no=excluded.inning_no, half=excluded.half, description=excluded.description
          """).param("id", id).param("sequence", event.sequence()).param("inning", event.inning())
          .param("half", event.half()).param("description", event.description()).update();
    }
  }

  private void savePlayerLines(String id, StatType type, List<GameDetailSource.PlayerLine> lines) {
    String table = type == StatType.BATTER ? "batter_game" : "pitcher_game";
    String columns = type.counts.stream().map(StatType::column).collect(Collectors.joining(", "));
    String bindings = type.counts.stream().map(field -> ":" + field).collect(Collectors.joining(", "));
    for (GameDetailSource.PlayerLine line : lines) {
      Map<String, Object> values = new LinkedHashMap<>();
      for (String field : type.counts) values.put(field, line.stats().get(field));
      jdbc.sql("INSERT INTO " + table + "(game_id, player_id, team_side, " + columns + ")"
          + " VALUES (:id,:playerId,:side," + bindings + ") ON CONFLICT(game_id, player_id) DO NOTHING")
          .params(values).param("id", id).param("playerId", line.playerId()).param("side", line.teamSide()).update();
    }
  }

  private List<Map<String, Object>> findPlayerStats(String id, StatType type) {
    String table = type == StatType.BATTER ? "batter_game" : "pitcher_game";
    String columns = type.counts.stream().map(field -> "r." + StatType.column(field) + " AS " + field)
        .collect(Collectors.joining(", "));
    return jdbc.sql("SELECT p.player_id AS playerId, p.player_name AS playerName, r.team_side AS teamSide, "
        + columns + " FROM " + table + " r JOIN player p USING(player_id) WHERE r.game_id=:id"
        + " ORDER BY r.team_side, p.player_id").param("id", id).query().listOfRows();
  }

  private Map<String, Object> gameResponse(Map<String, Object> row) {
    Map<String, Object> game = new LinkedHashMap<>(row);
    game.put("homeTeam", Map.of("teamId", game.remove("homeId"), "teamName", game.remove("homeName")));
    game.put("awayTeam", Map.of("teamId", game.remove("awayId"), "teamName", game.remove("awayName")));
    return game;
  }
}
