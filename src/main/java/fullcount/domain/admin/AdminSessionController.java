package fullcount.domain.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
class AdminSessionController {
  private final AdminAccess access;

  AdminSessionController(AdminAccess access) {
    this.access = access;
  }

  @GetMapping("/session")
  ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
    var session = request.getSession();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(Map.of("authenticated", access.loggedIn(session), "csrfToken", access.csrf(session)));
  }

  @PostMapping("/login")
  ResponseEntity<Map<String, Object>> login(HttpServletRequest request, @RequestBody Login body) {
    access.requireCsrf(request);
    if (!access.credentialsMatch(body.username(), body.password())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 계정 정보를 확인해 주세요.");
    }
    var session = request.getSession(false);
    request.changeSessionId();
    session.setAttribute("admin", true);
    session.removeAttribute("adminCsrf");
    return session(request);
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    access.requireSession(request);
    request.getSession(false).invalidate();
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  record Login(String username, String password) {}
}
