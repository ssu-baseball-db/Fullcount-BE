package fullcount.domain.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class AdminAccess {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final String username;
  private final String passwordHash;
  private final String bearer;

  AdminAccess(@Value("${fullcount.admin.username:}") String username,
      @Value("${fullcount.admin.password-hash:}") String passwordHash,
      @Value("${fullcount.admin.token:}") String bearer) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.bearer = bearer;
  }

  boolean loggedIn(HttpSession session) {
    return session != null && Boolean.TRUE.equals(session.getAttribute("admin"));
  }

  String csrf(HttpSession session) {
    synchronized (session) {
      if (session.getAttribute("adminCsrf") instanceof String token) return token;
      byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      session.setAttribute("adminCsrf", token);
      return token;
    }
  }

  void requireCsrf(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null || !matches(csrf(session), request.getHeader("X-CSRF-Token"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "요청 검증에 실패했습니다. 페이지를 다시 열어 주세요.");
    }
  }

  void requireSession(HttpServletRequest request) {
    if (!loggedIn(request.getSession(false))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다.");
    }
    requireCsrf(request);
  }

  void requireWrite(HttpServletRequest request) {
    if (!bearer.isBlank() && matches("Bearer " + bearer, request.getHeader("Authorization"))) return;
    if (!loggedIn(request.getSession(false))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 접근 권한이 필요합니다.");
    }
    requireCsrf(request);
  }

  boolean credentialsMatch(String suppliedUser, String password) {
    if (username.isBlank() || password == null || password.length() > 256) return false;
    String[] parts = passwordHash.split(":", -1);
    if (parts.length != 2) return false;
    PBEKeySpec spec = null;
    try {
      byte[] salt = Base64.getDecoder().decode(parts[0]);
      byte[] expected = Base64.getDecoder().decode(parts[1]);
      if (salt.length != 16 || expected.length != 32) return false;
      spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
      byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      return matches(username, suppliedUser) & MessageDigest.isEqual(expected, actual);
    } catch (IllegalArgumentException | InvalidKeySpecException e) {
      return false;
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } finally {
      if (spec != null) spec.clearPassword();
    }
  }

  private static boolean matches(String expected, String actual) {
    return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8));
  }
}
