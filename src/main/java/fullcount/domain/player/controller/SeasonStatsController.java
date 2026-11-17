package fullcount.domain.player.controller;

import fullcount.domain.player.repository.StatType;
import fullcount.domain.player.service.PlayerStatsService;
import fullcount.global.ApiErrors;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seasons/{year}")
public class SeasonStatsController {
  private final PlayerStatsService stats;

  public SeasonStatsController(PlayerStatsService stats) {
    this.stats = stats;
  }

  @GetMapping("/{kind:batting-stats|pitching-stats}")
  public List<Map<String, Object>> findSeasonStats(@PathVariable("year") int year,
      @RequestParam(name = "teamId", required = false) String teamId,
      @RequestParam(name = "sort", required = false) String sort,
      @RequestParam(name = "qualified", required = false) Boolean qualified,
      @PathVariable("kind") String kind) {
    return stats.findSeasonStats(statType(kind), ApiErrors.season(year), teamId, sort, qualified, true);
  }

  @GetMapping("/team-stats")
  public List<Map<String, Object>> findTeams(@PathVariable("year") int year,
      @RequestParam(name = "sort", required = false) String sort) {
    return stats.findTeamStats(ApiErrors.season(year), sort);
  }

  @GetMapping("/teams/{teamId}/{kind:batting-stats|pitching-stats}")
  public Map<String, Object> findTeamPlayers(@PathVariable("year") int year,
      @PathVariable("teamId") String teamId, @RequestParam(name = "sort", required = false) String sort,
      @PathVariable("kind") String kind) {
    return stats.findTeamPlayers(statType(kind), ApiErrors.season(year), teamId, sort);
  }

  @GetMapping("/matchup-predictions")
  public Map<String, Object> predict(@PathVariable("year") int year,
      @RequestParam("teamA") String teamA, @RequestParam("teamB") String teamB) {
    return stats.predict(ApiErrors.season(year), teamA, teamB);
  }

  private StatType statType(String kind) {
    return "pitching-stats".equals(kind) ? StatType.PITCHER : StatType.BATTER;
  }
}
