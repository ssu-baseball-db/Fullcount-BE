package fullcount.domain.game.service;

import fullcount.domain.game.repository.GameRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GameSummaryService {
  private final GameRepository repository;
  private final String providerUrl;
  private final RestClient provider;

  public GameSummaryService(GameRepository repository, @Value("${fullcount.summary.url:}") String providerUrl,
      @Value("${fullcount.summary.token:}") String token) {
    this.repository = repository;
    this.providerUrl = providerUrl;
    SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(Duration.ofSeconds(3));
    requests.setReadTimeout(Duration.ofSeconds(15));
    RestClient.Builder builder = RestClient.builder().requestFactory(requests);
    if (!token.isBlank()) builder.defaultHeaders(headers -> headers.setBearerAuth(token));
    this.provider = builder.build();
  }

  public Map<String, Object> findSummary(String gameId) {
    return repository.findSummary(gameId)
        .orElseThrow(() -> fullcount.global.ApiErrors.missing("저장된 경기 요약을 찾을 수 없습니다."));
  }

  public Map<String, Object> generate(String gameId) {
    return repository.findSummary(gameId).orElseGet(() -> {
      Map<String, Object> detail = repository.findDetail(gameId);
      if (((List<?>) detail.get("inningScores")).isEmpty()
          || ((List<?>) detail.get("battingStats")).isEmpty() || ((List<?>) detail.get("pitchingStats")).isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "요약에 필요한 상세 경기 기록이 없습니다.");
      }
      if (providerUrl.isBlank()) throw unavailable();
      SummaryResult result;
      try {
        result = provider.post().uri(providerUrl)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(Map.of("instructions", "제공된 경기 기록에 있는 사실만 한국어로 요약하세요.", "game", detail))
            .retrieve().body(SummaryResult.class);
      } catch (RestClientException exception) {
        throw unavailable();
      }
      if (result == null || result.summary() == null || result.summary().isBlank()) throw unavailable();
      repository.saveSummary(gameId, result.summary().strip());
      return findSummary(gameId);
    });
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "경기 요약 생성 서비스를 사용할 수 없습니다.");
  }

  public record SummaryResult(String summary) {}
}
