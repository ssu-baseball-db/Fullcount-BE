package fullcount.domain.player.repository;

import fullcount.global.ApiErrors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlayerRepository {
  public static final String PLAYER_FIELDS = """
      p.player_id AS playerId, p.player_name AS playerName, p.birth_date AS birthDate,
      p.height_cm AS heightCm, p.weight_kg AS weightKg, p.throw_hand AS throwHand, p.bat_hand AS batHand
      """;
  private static final String SEASON_FIELDS = """
      , r.season_year AS season, r.team_id AS teamId, n.team_name AS teamName, r.season_position AS position
      """;
  private static final String SEASON_JOIN = """
      JOIN player p ON p.player_id=r.player_id
      JOIN team_season_name n ON n.team_id=r.team_id AND n.season_year=r.season_year
      """;
  private final JdbcClient jdbc;

  public PlayerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> findPlayer(String playerId) {
    return jdbc.sql("SELECT " + PLAYER_FIELDS + " FROM player p WHERE p.player_id=:id")
        .param("id", playerId).query().optionalRow()
        .orElseThrow(() -> ApiErrors.missing("선수를 찾을 수 없습니다."));
  }

  public List<Map<String, Object>> search(String name, Integer season, String teamId, String position) {
    return jdbc.sql("""
        WITH records AS (
          SELECT player_id, season_year, team_id, season_position FROM batter_season
          UNION
          SELECT player_id, season_year, team_id, COALESCE(season_position,'P') FROM pitcher_season
        )
        SELECT
        """ + PLAYER_FIELDS + SEASON_FIELDS + " FROM records r " + SEASON_JOIN + """
        WHERE instr(lower(p.player_name), lower(:name)) > 0
          AND (:season IS NULL OR r.season_year=:season)
          AND (:team IS NULL OR r.team_id=:team)
          AND (:position IS NULL OR r.season_position=:position)
        ORDER BY p.player_name, p.player_id, r.season_year, r.team_id, r.season_position
        """).param("name", name).param("season", season).param("team", teamId)
        .param("position", position).query().listOfRows();
  }

  public List<Map<String, Object>> findStats(StatType type, Integer season, String teamId, String playerId) {
    String columns = type.counts.stream()
        .map(field -> "r." + StatType.column(field) + " AS " + field)
        .collect(Collectors.joining(", "));
    String seasonFields = type == StatType.PITCHER
        ? SEASON_FIELDS.replace("r.season_position AS position", "COALESCE(r.season_position,'P') AS position")
        : SEASON_FIELDS;
    return jdbc.sql("SELECT " + PLAYER_FIELDS + seasonFields + ", " + columns + " FROM "
        + type.table + " r " + SEASON_JOIN + """
        WHERE (:season IS NULL OR r.season_year=:season)
          AND (:team IS NULL OR r.team_id=:team)
          AND (:id IS NULL OR r.player_id=:id)
        ORDER BY r.season_year, p.player_name, p.player_id
        """).param("season", season).param("team", teamId).param("id", playerId)
        .query().listOfRows().stream().map(row -> (Map<String, Object>) new LinkedHashMap<>(row)).toList();
  }

  public List<Map<String, Object>> findTeams(int season) {
    return jdbc.sql("""
        SELECT team_id AS teamId, team_name AS teamName
        FROM team_season_name WHERE season_year=:season ORDER BY team_id
        """).param("season", season).query().listOfRows();
  }
}
