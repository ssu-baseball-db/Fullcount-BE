package fullcount.domain.admin;

import fullcount.domain.player.repository.StatType;
import fullcount.global.ApiErrors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminService admin;
  private final SeasonImportService importer;
  private final String token;

  public AdminController(AdminService admin, SeasonImportService importer,
      @Value("${fullcount.admin.token:}") String token) {
    this.admin = admin;
    this.importer = importer;
    this.token = token;
  }

  @PostMapping("/imports")
  public Map<String, Object> importFiles(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestParam("battingCsv") MultipartFile battingCsv, @RequestParam("pitchingCsv") MultipartFile pitchingCsv) {
    authorize(authorization);
    return importer.importFiles(battingCsv, pitchingCsv);
  }

  @PostMapping("/players")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createPlayer(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> request) {
    authorize(authorization);
    return admin.createPlayer(request);
  }

  @PatchMapping("/players/{playerId}")
  public Map<String, Object> updatePlayer(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable("playerId") String id, @RequestBody Map<String, Object> request) {
    authorize(authorization);
    return admin.updatePlayer(id, request);
  }

  @PatchMapping("/players/{playerId}/{kind:batting-stats|pitching-stats}/{year}")
  public Map<String, Object> updateRecord(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable("playerId") String id, @PathVariable("kind") String kind, @PathVariable("year") int year,
      @RequestBody Map<String, Object> request) {
    authorize(authorization);
    StatType type = "pitching-stats".equals(kind) ? StatType.PITCHER : StatType.BATTER;
    return admin.updateRecord(type, id, ApiErrors.season(year), request);
  }

  private void authorize(String authorization) {
    if (token.isBlank() || authorization == null || !MessageDigest.isEqual(
        ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 접근 권한이 필요합니다.");
    }
  }
}
