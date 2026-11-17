package fullcount.domain.admin;

import fullcount.domain.player.repository.StatType;
import fullcount.global.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminService admin;
  private final SeasonImportService importer;
  private final AdminAccess access;

  public AdminController(AdminService admin, SeasonImportService importer,
      AdminAccess access) {
    this.admin = admin;
    this.importer = importer;
    this.access = access;
  }

  @PostMapping("/imports")
  public Map<String, Object> importFiles(HttpServletRequest servletRequest,
      @RequestParam("battingCsv") MultipartFile battingCsv, @RequestParam("pitchingCsv") MultipartFile pitchingCsv) {
    access.requireWrite(servletRequest);
    return importer.importFiles(battingCsv, pitchingCsv);
  }

  @PostMapping("/players")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createPlayer(HttpServletRequest servletRequest,
      @RequestBody Map<String, Object> request) {
    access.requireWrite(servletRequest);
    return admin.createPlayer(request);
  }

  @PatchMapping("/players/{playerId}")
  public Map<String, Object> updatePlayer(HttpServletRequest servletRequest,
      @PathVariable("playerId") String id, @RequestBody Map<String, Object> request) {
    access.requireWrite(servletRequest);
    return admin.updatePlayer(id, request);
  }

  @PatchMapping("/players/{playerId}/{kind:batting-stats|pitching-stats}/{year}")
  public Map<String, Object> updateRecord(HttpServletRequest servletRequest,
      @PathVariable("playerId") String id, @PathVariable("kind") String kind, @PathVariable("year") int year,
      @RequestBody Map<String, Object> request) {
    access.requireWrite(servletRequest);
    StatType type = "pitching-stats".equals(kind) ? StatType.PITCHER : StatType.BATTER;
    return admin.updateRecord(type, id, ApiErrors.season(year), request);
  }

}
