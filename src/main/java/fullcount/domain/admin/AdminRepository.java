package fullcount.domain.admin;

import fullcount.domain.player.repository.StatType;
import fullcount.global.ApiErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRepository {
  private final JdbcClient jdbc;

  public AdminRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  int createPlayer(Map<String, Object> fields, boolean retainExisting) {
    return insert("player", fields, retainExisting ? " ON CONFLICT(player_id) DO NOTHING" : "");
  }

  int updatePlayer(String id, Map<String, Object> fields) {
    return update("player", fields, "player_id=:id", Map.of("id", id));
  }

  int updateRecord(StatType type, String id, int season, Map<String, Object> fields) {
    return update(type.table, fields, "player_id=:id AND season_year=:year", Map.of("id", id, "year", season));
  }

  void insertRecord(StatType type, Map<String, Object> fields) {
    if (insert(type.table, fields, " ON CONFLICT(player_id,season_year) DO NOTHING") == 0) {
      throw ApiErrors.bad("이미 등록된 선수·시즌 기록입니다.");
    }
  }

  long createSource(String filename) {
    return jdbc.sql("INSERT INTO data_source(source_name) VALUES (:name) RETURNING source_id")
        .param("name", filename).query(Long.class).single();
  }

  String resolveTeam(int year, String name) {
    return jdbc.sql("""
        SELECT team_id FROM team_season_name WHERE season_year=:year AND (team_name=:name OR team_id=:name)
        """).param("year", year).param("name", name).query(String.class).optional()
        .orElseThrow(() -> ApiErrors.bad("시즌 구단명 이력에서 구단을 찾을 수 없습니다."));
  }

  private int insert(String table, Map<String, Object> fields, String conflict) {
    String columns = String.join(", ", fields.keySet());
    String values = fields.keySet().stream().map(field -> ":" + field).collect(Collectors.joining(", "));
    return jdbc.sql("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")" + conflict)
        .params(fields).update();
  }

  private int update(String table, Map<String, Object> fields, String condition, Map<String, Object> keys) {
    String assignments = fields.keySet().stream().map(field -> field + "=:" + field)
        .collect(Collectors.joining(", "));
    Map<String, Object> parameters = new LinkedHashMap<>(fields);
    parameters.putAll(keys);
    return jdbc.sql("UPDATE " + table + " SET " + assignments + " WHERE " + condition).params(parameters).update();
  }
}
