package fullcount.global;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  public static int season(int year) {
    if (year < 2015 || year > 2026) throw bad("시즌은 2015~2026년이어야 합니다.");
    return year;
  }

  public static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public static ResponseStatusException missing(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
  }

  @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class,
      HttpMessageNotReadableException.class})
  ResponseEntity<Map<String, String>> badInput(Exception exception) {
    String message = exception instanceof MethodArgumentTypeMismatchException mismatch
        ? mismatch.getName() + " 값의 형식이 올바르지 않습니다." : "요청에 필요한 값과 형식을 확인해 주세요.";
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }
}
