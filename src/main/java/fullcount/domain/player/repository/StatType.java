package fullcount.domain.player.repository;

import fullcount.global.ApiErrors;
import java.util.List;

public enum StatType {
  BATTER("batter_season", "plateAppearances", 447,
      List.of("games", "plateAppearances", "atBats", "runs", "hits", "doubles", "triples",
          "homeRuns", "runsBattedIn", "stolenBases", "caughtStealing", "walks", "hitByPitch",
          "strikeouts", "sacrificeHits", "sacrificeFlies", "groundedIntoDoublePlays"),
      List.of("avg", "obp", "slg", "ops"),
      List.of("avg", "homeRuns", "runsBattedIn", "stolenBases", "ops")),
  PITCHER("pitcher_season", "outsPitched", 432,
      List.of("games", "wins", "losses", "saves", "holds", "outsPitched", "hitsAllowed",
          "homeRunsAllowed", "walksAllowed", "hitBatters", "strikeouts", "runsAllowed",
          "earnedRuns", "battersFaced"),
      List.of("era", "whip", "winningPct"),
      List.of("wins", "era", "strikeouts", "saves", "whip"));

  public final String table;
  public final String qualificationField;
  public final int minimum;
  public final List<String> counts;
  public final List<String> rates;
  public final List<String> metrics;

  StatType(String table, String qualificationField, int minimum, List<String> counts,
      List<String> rates, List<String> metrics) {
    this.table = table;
    this.qualificationField = qualificationField;
    this.minimum = minimum;
    this.counts = counts;
    this.rates = rates;
    this.metrics = metrics;
  }

  public static StatType parse(String type) {
    return switch (type) {
      case "batter" -> BATTER;
      case "pitcher" -> PITCHER;
      default -> throw ApiErrors.bad("type은 batter 또는 pitcher여야 합니다.");
    };
  }

  public static String column(String field) {
    return field.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
  }
}
