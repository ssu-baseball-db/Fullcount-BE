package fullcount.domain.game.service;

import fullcount.domain.game.repository.GameRepository;
import fullcount.domain.game.repository.GameDetailSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
  private final String detailUrl;
  private final RestClient detailClient;
  private final Set<String> generating = ConcurrentHashMap.newKeySet();
  private final RestClient provider;

  public GameSummaryService(GameRepository repository, @Value("${fullcount.summary.url:}") String providerUrl,
      @Value("${fullcount.summary.token:}") String token,
      @Value("${fullcount.game-detail.url:}") String detailUrl) {
    this.repository = repository;
    this.providerUrl = providerUrl;
    this.detailUrl = detailUrl;
    SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(Duration.ofSeconds(3));
    requests.setReadTimeout(Duration.ofSeconds(15));
    RestClient.Builder builder = RestClient.builder().requestFactory(requests);
    this.detailClient = builder.build();
    if (!token.isBlank()) builder.defaultHeaders(headers -> headers.setBearerAuth(token));
    this.provider = builder.build();
  }

  public Map<String, Object> findSummary(String gameId) {
    return repository.findSummary(gameId)
        .orElseThrow(() -> fullcount.global.ApiErrors.missing("저장된 경기 요약을 찾을 수 없습니다."));
  }

  public Map<String, Object> generate(String gameId) {
    var saved = repository.findSummary(gameId);
    if (saved.isPresent()) return saved.get();
    if (!generating.add(gameId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "이 경기의 요약을 생성하고 있습니다.");
    }
    try {
      saved = repository.findSummary(gameId);
      if (saved.isPresent()) return saved.get();
      Map<String, Object> detail = repository.findDetail(gameId);
      if (!"COMPLETED".equals(detail.get("status"))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "종료된 경기만 요약할 수 있습니다.");
      }
      if (providerUrl.isBlank()) throw unavailable();
      if (((List<?>) detail.get("inningScores")).isEmpty()
          || ((List<?>) detail.get("battingStats")).isEmpty() || ((List<?>) detail.get("pitchingStats")).isEmpty()) {
        importDetail(gameId, detail);
        detail = repository.findDetail(gameId);
      }
      detail.put("events", repository.findEvents(gameId));
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
    } finally {
      generating.remove(gameId);
    }
  }

  private void importDetail(String gameId, Map<String, Object> existing) {
    if (detailUrl.isBlank()) throw missingDetail();
    GameDetailSource detail;
    try {
      detail = detailClient.get().uri(detailUrl, gameId).retrieve().body(GameDetailSource.class);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
      throw missingDetail();
    } catch (RestClientException exception) {
      throw detailUnavailable();
    }
    if (detail == null || !detail.matches(existing)) throw detailUnavailable();
    try {
      repository.saveDetail(detail);
    } catch (org.springframework.dao.DataAccessException exception) {
      throw detailUnavailable();
    }
  }

  private ResponseStatusException missingDetail() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "요약에 필요한 상세 경기 기록을 구할 수 없습니다.");
  }

  private ResponseStatusException detailUnavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "경기 상세 기록을 가져오거나 저장할 수 없습니다.");
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "경기 요약 생성 서비스를 사용할 수 없습니다.");
  }

  public record SummaryResult(String summary) {}
}
