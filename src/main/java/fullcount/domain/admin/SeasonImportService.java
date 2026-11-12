package fullcount.domain.admin;

import fullcount.domain.player.repository.StatType;
import fullcount.global.ApiErrors;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SeasonImportService {
  private static final Map<String, String> BATTING_ALIASES = Map.ofEntries(
      Map.entry("games","G"), Map.entry("plateAppearances","PA"), Map.entry("atBats","AB"),
      Map.entry("runs","R"), Map.entry("hits","H"), Map.entry("doubles","2B"), Map.entry("triples","3B"),
      Map.entry("homeRuns","HR"), Map.entry("runsBattedIn","RBI"), Map.entry("stolenBases","SB"),
      Map.entry("caughtStealing","CS"), Map.entry("walks","BB"), Map.entry("hitByPitch","HBP"),
      Map.entry("strikeouts","SO"), Map.entry("sacrificeHits","SH"), Map.entry("sacrificeFlies","SF"),
      Map.entry("groundedIntoDoublePlays","GDP"));
  private static final Map<String, String> PITCHING_ALIASES = Map.ofEntries(
      Map.entry("games","G"), Map.entry("wins","W"), Map.entry("losses","L"), Map.entry("saves","SV"),
      Map.entry("holds","HLD"), Map.entry("hitsAllowed","H"), Map.entry("homeRunsAllowed","HR"),
      Map.entry("walksAllowed","BB"), Map.entry("hitBatters","HBP"), Map.entry("strikeouts","SO"),
      Map.entry("runsAllowed","R"), Map.entry("earnedRuns","ER"), Map.entry("battersFaced","TBF"));

  private final AdminRepository repository;
  private final AdminService admin;
  private final TransactionTemplate transactions;

  public SeasonImportService(AdminRepository repository, AdminService admin, PlatformTransactionManager manager) {
    this.repository = repository;
    this.admin = admin;
    this.transactions = new TransactionTemplate(manager);
  }

  public Map<String, Object> importFiles(MultipartFile battingCsv, MultipartFile pitchingCsv) {
    Source batting = read(battingCsv, StatType.BATTER, "battingCsv");
    Source pitching = read(pitchingCsv, StatType.PITCHER, "pitchingCsv");
    List<Map<String, Object>> errors = new ArrayList<>();
    int battingRows = importSource(batting, errors);
    int pitchingRows = importSource(pitching, errors);
    return Map.of("battingRowsImported", battingRows, "pitchingRowsImported", pitchingRows, "errors", errors);
  }

  private Source read(MultipartFile file, StatType type, String part) {
    List<List<String>> records;
    try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8.newDecoder())) {
      StringBuilder text = new StringBuilder();
      char[] buffer = new char[8192];
      int length;
      while ((length = reader.read(buffer)) != -1) text.append(buffer, 0, length);
      records = CsvRecords.parse(text.toString());
    } catch (IOException exception) {
      throw ApiErrors.bad(part + "는 UTF-8 CSV여야 합니다.");
    }
    if (records.isEmpty()) throw ApiErrors.bad(part + "에 CSV 헤더가 없습니다.");
    Map<String, Integer> headers = new HashMap<>();
    List<String> header = records.getFirst();
    for (int index = 0; index < header.size(); index++) {
      String field = normalize(header.get(index));
      if (field.isEmpty()) continue;
      if (headers.putIfAbsent(field, index) != null) throw ApiErrors.bad(part + "에 중복 헤더가 있습니다.");
    }
    Source source = new Source(type, part, file.getOriginalFilename(), headers, records);
    if (!source.has("playerId", "id", "playerCode", "선수ID")
        || !source.has("playerName", "name", "선수명")
        || !source.has("year", "season", "seasonYear", "연도")
        || !source.has("teamId", "teamName", "team", "구단", "팀명")) {
      throw ApiErrors.bad(part + "에 선수 ID, 이름, 시즌 또는 구단 헤더가 없습니다.");
    }
    boolean hasStats = type.counts.stream().anyMatch(field -> source.has(field, aliases(type).getOrDefault(field, field)));
    if (!hasStats && (type != StatType.PITCHER || !source.has("IP", "inningsPitched", "innings"))) {
      throw ApiErrors.bad(part + "에 인식할 수 있는 기록 헤더가 없습니다.");
    }
    return source;
  }

  private int importSource(Source source, List<Map<String, Object>> errors) {
    long sourceId = repository.createSource(source.filename == null || source.filename.isBlank() ? source.part : source.filename);
    int imported = 0;
    for (int index = 1; index < source.records.size(); index++) {
      List<String> record = source.records.get(index);
      if (record.stream().allMatch(String::isBlank)) continue;
      try {
        if (record.size() != source.records.getFirst().size()) throw ApiErrors.bad("CSV 열 개수가 헤더와 다릅니다.");
        int year = Integer.parseInt(AdminService.requiredText(source.value(record, "year", "season", "seasonYear", "연도"), "season"));
        if (year < 2015 || year > 2026) continue;
        transactions.executeWithoutResult(status -> insertRow(source, record, year, sourceId));
        imported++;
      } catch (ResponseStatusException exception) {
        errors.add(Map.of("file", source.part, "row", index + 1, "message", exception.getReason()));
      } catch (NumberFormatException | ArithmeticException exception) {
        errors.add(Map.of("file", source.part, "row", index + 1, "message", "시즌, 기록 또는 신체 정보의 숫자 형식이 올바르지 않습니다."));
      }
    }
    return imported;
  }

  private void insertRow(Source source, List<String> row, int year, long sourceId) {
    String id = AdminService.requiredText(source.value(row, "playerId", "id", "playerCode", "선수ID"), "playerId");
    String name = AdminService.requiredText(source.value(row, "playerName", "name", "선수명"), "playerName");
    String team = AdminService.requiredText(source.value(row, "teamId", "teamName", "team", "구단", "팀명"), "team");
    String position = source.value(row, "seasonPosition", "position", "POS", "포지션");
    if (List.of("LF", "CF", "RF").contains(position == null ? "" : position)) position = "OF";
    if (position == null && source.type == StatType.PITCHER) position = "P";
    Map<String, Object> stats = new LinkedHashMap<>();
    Map<String, Object> values = new LinkedHashMap<>();
    for (String field : source.type.counts) {
      String value = source.value(row, field, aliases(source.type).getOrDefault(field, field));
      Long count = value == null ? null : AdminService.count(Long.parseLong(value), field);
      if (count == null && field.equals("outsPitched")) {
        count = parseInnings(source.value(row, "IP", "inningsPitched", "innings"));
      }
      stats.put(field, count);
      values.put(StatType.column(field), count);
    }
    AdminService.validateCounts(source.type, stats);
    values.put("player_id", id);
    values.put("season_year", year);
    values.put("team_id", repository.resolveTeam(year, team));
    values.put("source_id", sourceId);
    values.put("season_position", position);
    Map<String, Object> player = new LinkedHashMap<>();
    player.put("playerName", name);
    player.put("birthDate", source.value(row, "birthDate", "birthday", "birth", "생년월일"));
    String height = source.value(row, "heightCm", "height");
    String weight = source.value(row, "weightKg", "weight");
    String body = source.value(row, "heightWeight", "heightAndWeight", "height/weight", "신체");
    if (body == null && height != null && height.contains("/")) {
      body = height;
      height = null;
    }
    if (body != null && body.contains("/")) {
      String[] parts = body.split("/", 2);
      if (height == null) height = parts[0];
      if (weight == null) weight = parts[1];
    }
    player.put("heightCm", dimension(height, "cm"));
    player.put("weightKg", dimension(weight, "kg"));
    String throwHand = source.value(row, "throwHand", "throws");
    String batHand = source.value(row, "batHand", "bats");
    String hands = source.value(row, "batThrow", "throwBat", "투타");
    if (hands != null && hands.contains("/")) {
      String[] parts = hands.toUpperCase(Locale.ROOT).split("/", 2);
      // BatThrow denotes batting hand first. ThrowBat denotes throwing hand first.
      boolean battingFirst = source.has("batThrow");
      if (batHand == null) batHand = parts[battingFirst ? 0 : 1].strip();
      if (throwHand == null) throwHand = parts[battingFirst ? 1 : 0].strip();
    }
    player.put("throwHand", throwHand);
    player.put("batHand", batHand);
    Map<String, Object> playerValues = admin.playerValues(player);
    playerValues.put("player_id", id);
    AdminService.write(() -> repository.createPlayer(playerValues, true));
    AdminService.write(() -> {
      repository.insertRecord(source.type, values);
      return null;
    });
  }

  static Long parseInnings(String value) {
    if (value == null) return null;
    if (value.matches("\\d+")) return Math.multiplyExact(Long.parseLong(value), 3L);
    if (value.matches("\\d+\\.[012]")) {
      String[] parts = value.split("\\.");
      return Math.addExact(Math.multiplyExact(Long.parseLong(parts[0]), 3L), Long.parseLong(parts[1]));
    }
    if (value.matches("(\\d+\\s+)?[012]/3")) {
      String[] parts = value.split("\\s+");
      long full = parts.length == 2 ? Long.parseLong(parts[0]) : 0;
      return Math.addExact(Math.multiplyExact(full, 3L), parts[parts.length - 1].charAt(0) - '0');
    }
    throw ApiErrors.bad("이닝은 정수, 9.1 또는 9 1/3 형태로 입력해 주세요.");
  }

  private Double dimension(String value, String unit) {
    return value == null ? null : Double.parseDouble(value.toLowerCase(Locale.ROOT).replace(unit, "").strip());
  }

  private static Map<String, String> aliases(StatType type) {
    return type == StatType.BATTER ? BATTING_ALIASES : PITCHING_ALIASES;
  }

  private static String normalize(String header) {
    return header.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  private record Source(StatType type, String part, String filename, Map<String, Integer> headers,
                        List<List<String>> records) {
    boolean has(String... names) {
      for (String name : names) if (headers.containsKey(normalize(name))) return true;
      return false;
    }

    String value(List<String> row, String... names) {
      for (String name : names) {
        Integer index = headers.get(normalize(name));
        if (index == null) continue;
        String value = row.get(index).strip();
        if (!value.isEmpty() && !"-".equals(value)) return value;
      }
      return null;
    }
  }
}
