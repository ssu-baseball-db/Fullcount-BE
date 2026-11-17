package fullcount.domain.admin;

import fullcount.domain.player.repository.PlayerRepository;
import fullcount.domain.player.repository.StatType;
import fullcount.domain.player.service.PlayerStatsService;
import fullcount.global.ApiErrors;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {
  private static final List<String> PLAYER_FIELDS = List.of("playerName", "birthDate", "heightCm",
      "weightKg", "throwHand", "batHand");
  private final AdminRepository repository;
  private final PlayerRepository players;
  private final PlayerStatsService stats;

  public AdminService(AdminRepository repository, PlayerRepository players, PlayerStatsService stats) {
    this.repository = repository;
    this.players = players;
    this.stats = stats;
  }

  @Transactional
  public Map<String, Object> createPlayer(Map<String, Object> body) {
    String id = requiredText(body.get("playerId"), "playerId");
    Map<String, Object> fields = new LinkedHashMap<>(body);
    fields.remove("playerId");
    Map<String, Object> values = playerValues(fields);
    requiredText(fields.get("playerName"), "playerName");
    values.put("player_id", id);
    if (write(() -> repository.createPlayer(values, true)) == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 선수 ID입니다.");
    }
    return players.findPlayer(id);
  }

  @Transactional
  public Map<String, Object> updatePlayer(String id, Map<String, Object> body) {
    Map<String, Object> values = playerValues(body);
    if (write(() -> repository.updatePlayer(id, values)) == 0) throw ApiErrors.missing("선수를 찾을 수 없습니다.");
    return players.findPlayer(id);
  }

  @Transactional
  public Map<String, Object> updateRecord(StatType type, String id, int year, Map<String, Object> body) {
    if (body.isEmpty()) throw ApiErrors.bad("수정할 기록을 입력해 주세요.");
    Map<String, Object> fields = new LinkedHashMap<>();
    body.forEach((key, value) -> {
      if (!type.counts.contains(key)) throw ApiErrors.bad("수정할 수 없는 기록 항목입니다. " + key);
      fields.put(StatType.column(key), count(value, key));
    });
    List<Map<String, Object>> rows = players.findStats(type, year, null, id);
    if (rows.isEmpty()) throw ApiErrors.missing("선수의 시즌 기록을 찾을 수 없습니다.");
    Map<String, Object> current = rows.getFirst();
    current.putAll(body);
    validateCounts(type, current);
    if (write(() -> repository.updateRecord(type, id, year, fields)) == 0) {
      throw ApiErrors.missing("선수의 시즌 기록을 찾을 수 없습니다.");
    }
    Map<String, Object> updated = players.findStats(type, year, null, id).getFirst();
    stats.addSeasonMetrics(type, updated);
    return updated;
  }

  Map<String, Object> playerValues(Map<String, Object> body) {
    if (body.isEmpty()) throw ApiErrors.bad("선수 정보를 입력해 주세요.");
    Map<String, Object> result = new LinkedHashMap<>();
    body.forEach((key, value) -> {
      if (!PLAYER_FIELDS.contains(key)) throw ApiErrors.bad("수정할 수 없는 선수 정보입니다. " + key);
      Object checked = switch (key) {
        case "playerName" -> requiredText(value, key);
        case "birthDate" -> birthDate(value);
        case "heightCm", "weightKg" -> positive(value, key);
        case "throwHand" -> hand(value, List.of("R", "L"));
        case "batHand" -> hand(value, List.of("R", "L", "S"));
        default -> throw new IllegalStateException(key);
      };
      result.put(StatType.column(key), checked);
    });
    return result;
  }

  static String requiredText(Object value, String field) {
    if (value instanceof String text && !text.isBlank()) return text;
    throw ApiErrors.bad(field + " 값이 필요합니다.");
  }

  private static Object birthDate(Object value) {
    if (value == null) return null;
    String text = requiredText(value, "birthDate");
    try {
      if (text.length() == 10) return LocalDate.parse(text).toString();
    } catch (DateTimeParseException exception) {
      throw ApiErrors.bad("생년월일은 YYYY-MM-DD 형식이어야 합니다.");
    }
    throw ApiErrors.bad("생년월일은 YYYY-MM-DD 형식이어야 합니다.");
  }

  private static Object positive(Object value, String field) {
    if (value == null) return null;
    if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0) return n.doubleValue();
    throw ApiErrors.bad(field + " 값은 양수여야 합니다.");
  }

  private static Object hand(Object value, List<String> options) {
    if (value == null || options.contains(value)) return value;
    throw ApiErrors.bad("투구손 또는 타격 방향 값이 올바르지 않습니다.");
  }

  static Long count(Object value, String field) {
    if (value == null) return null;
    if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0) {
      return ((Number) value).longValue();
    }
    throw ApiErrors.bad(field + " 값은 0 이상의 정수여야 합니다.");
  }

  static void validateCounts(StatType type, Map<String, Object> row) {
    if (type == StatType.BATTER) {
      if (greater(row, "atBats", "plateAppearances")) throw ApiErrors.bad("타수는 타석 이하여야 합니다.");
      if (greater(row, "hits", "atBats")) throw ApiErrors.bad("안타는 타수 이하여야 합니다.");
      Number hits = (Number) row.get("hits");
      if (hits != null) {
        long remaining = hits.longValue();
        for (String field : List.of("doubles", "triples", "homeRuns")) {
          Number value = (Number) row.get(field);
          if (value != null) {
            if (value.longValue() > remaining) throw ApiErrors.bad("안타는 2루타·3루타·홈런 합계 이상이어야 합니다.");
            remaining -= value.longValue();
          }
        }
      }
    } else {
      if (greater(row, "earnedRuns", "runsAllowed")) throw ApiErrors.bad("자책점은 실점 이하여야 합니다.");
      if (greater(row, "homeRunsAllowed", "hitsAllowed")) throw ApiErrors.bad("피홈런은 피안타 이하여야 합니다.");
    }
  }

  private static boolean greater(Map<String, Object> row, String left, String right) {
    return row.get(left) instanceof Number a && row.get(right) instanceof Number b && a.longValue() > b.longValue();
  }

  static <T> T write(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DataAccessException exception) {
      if (exception.getMostSpecificCause() instanceof SQLException sql && (sql.getErrorCode() & 255) == 19) {
        throw ApiErrors.bad("기록이 데이터베이스 제약 조건을 위반합니다.");
      }
      throw exception;
    }
  }
}
