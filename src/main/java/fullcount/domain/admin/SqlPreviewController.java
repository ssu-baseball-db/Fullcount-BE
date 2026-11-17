package fullcount.domain.admin;

import fullcount.global.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
class SqlPreviewController {
  private final AdminAccess access;
  private final String url;
  private final RestClient worker;
  private final JsonMapper json;

  SqlPreviewController(AdminAccess access, JsonMapper json, @Value("${fullcount.sql-preview.url:}") String url) {
    this.access = access;
    this.json = json;
    this.url = url;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(3));
    worker = RestClient.builder().requestFactory(factory).build();
  }

  @PostMapping("/api/admin/sql-preview")
  ResponseEntity<String> preview(HttpServletRequest request, @RequestBody Query query) {
    access.requireSession(request);
    if (query.sql() == null || query.sql().isBlank() || query.sql().getBytes(StandardCharsets.UTF_8).length > 8192) {
      throw ApiErrors.bad("SQL을 8KB 이내로 입력해 주세요.");
    }
    if (url.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SQL 시연 서비스가 설정되지 않았습니다.");
    }
    try {
      byte[] payload = json.writeValueAsBytes(query);
      return worker.post().uri(url).contentType(MediaType.APPLICATION_JSON).contentLength(payload.length).body(payload)
          .exchange((sent, response) -> {
            byte[] bytes = response.getBody().readNBytes(65537);
            if (bytes.length > 65536 || response.getStatusCode().is5xxServerError()) {
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SQL 시연 서비스 응답에 문제가 있습니다.");
            }
            return ResponseEntity.status(response.getStatusCode()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(new String(bytes, StandardCharsets.UTF_8));
          });
    } catch (RestClientException exception) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("SQL preview request failed", exception);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SQL 시연 서비스에 연결할 수 없습니다.");
    }
  }

  record Query(String sql) {}
}
