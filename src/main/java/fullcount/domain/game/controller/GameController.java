package fullcount.domain.game.controller;

import fullcount.domain.game.repository.GameRepository;
import fullcount.domain.game.service.GameSummaryService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {
  private final GameRepository repository;
  private final GameSummaryService summaries;

  public GameController(GameRepository repository, GameSummaryService summaries) {
    this.repository = repository;
    this.summaries = summaries;
  }

  @GetMapping
  public List<Map<String, Object>> findGames(
      @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(name = "teamId", required = false) String teamId) {
    return repository.findGames(date, teamId);
  }

  @GetMapping("/{gameId}")
  public Map<String, Object> findGame(@PathVariable("gameId") String gameId) {
    return repository.findDetail(gameId);
  }

  @GetMapping("/{gameId}/summary")
  public Map<String, Object> findSummary(@PathVariable("gameId") String gameId) {
    return summaries.findSummary(gameId);
  }

  @PostMapping("/{gameId}/summary")
  public Map<String, Object> createSummary(@PathVariable("gameId") String gameId) {
    return summaries.generate(gameId);
  }

  @GetMapping("/{gameId}/events")
  public List<Map<String, Object>> findEvents(@PathVariable("gameId") String gameId) {
    return repository.findEvents(gameId);
  }
}
