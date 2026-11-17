package fullcount.domain.standings.controller;

import fullcount.domain.standings.dto.StandingResponse;
import fullcount.domain.standings.repository.StandingRepository;
import fullcount.global.ApiErrors;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StandingController {
  private final StandingRepository repository;

  public StandingController(StandingRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/api/seasons/{year}/standings")
  public List<StandingResponse> findStandings(@PathVariable("year") int year) {
    return repository.findLatestStandingsBySeason(ApiErrors.season(year));
  }
}
