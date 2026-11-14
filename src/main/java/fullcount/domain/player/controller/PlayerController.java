package fullcount.domain.player.controller;

import fullcount.domain.player.repository.PlayerRepository;
import fullcount.domain.player.repository.StatType;
import fullcount.domain.player.service.PlayerStatsService;
import fullcount.global.ApiErrors;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlayerController {
  private final PlayerRepository repository;
  private final PlayerStatsService stats;

  public PlayerController(PlayerRepository repository, PlayerStatsService stats) {
    this.repository = repository;
    this.stats = stats;
  }

  @GetMapping("/api/players")
  public List<Map<String, Object>> search(@RequestParam("name") String name,
      @RequestParam(name = "season", required = false) Integer season,
      @RequestParam(name = "teamId", required = false) String teamId,
      @RequestParam(name = "position", required = false) String position) {
    if (name.isBlank()) throw ApiErrors.bad("선수 이름을 입력해 주세요.");
    if (season != null) ApiErrors.season(season);
    if (position != null && !List.of("P", "C", "1B", "2B", "3B", "SS", "OF", "DH").contains(position)) {
      throw ApiErrors.bad("지원하지 않는 포지션입니다.");
    }
    return repository.search(name.strip(), season, teamId, position);
  }

  @GetMapping("/api/players/{playerId}/season-stats")
  public Map<String, Object> findPlayerStats(@PathVariable("playerId") String playerId,
      @RequestParam("type") String type) {
    return stats.findPlayerStats(playerId, StatType.parse(type), type);
  }

  @GetMapping("/api/players/compare")
  public Map<String, Object> compare(@RequestParam("playerA") String playerA,
      @RequestParam("playerB") String playerB, @RequestParam("type") String type,
      @RequestParam(name = "season", required = false) Integer season) {
    if (season != null) ApiErrors.season(season);
    return stats.compare(playerA, playerB, StatType.parse(type), type, season);
  }

  @GetMapping("/api/players/{playerId}/similar-players")
  public List<Map<String, Object>> similar(@PathVariable("playerId") String playerId,
      @RequestParam("type") String type, @RequestParam("season") int season) {
    return stats.similar(playerId, StatType.parse(type), ApiErrors.season(season));
  }
}
