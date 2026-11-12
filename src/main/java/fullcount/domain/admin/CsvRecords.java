package fullcount.domain.admin;

import fullcount.global.ApiErrors;
import java.util.ArrayList;
import java.util.List;

final class CsvRecords {
  private CsvRecords() {}

  static List<List<String>> parse(String content) {
    List<List<String>> records = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    boolean closed = false;
    for (int index = content.startsWith("\uFEFF") ? 1 : 0; index < content.length(); index++) {
      char character = content.charAt(index);
      if (quoted) {
        if (character != '"') {
          field.append(character);
        } else if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
          field.append('"');
          index++;
        } else {
          quoted = false;
          closed = true;
        }
      } else if (character == ',' || character == '\r' || character == '\n') {
        record.add(field.toString());
        field.setLength(0);
        closed = false;
        if (character != ',') {
          records.add(record);
          record = new ArrayList<>();
          if (character == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
        }
      } else if (character == '"' && field.isEmpty() && !closed) {
        quoted = true;
      } else if (character == '"' || closed) {
        throw ApiErrors.bad("CSV 따옴표 형식이 올바르지 않습니다.");
      } else {
        field.append(character);
      }
    }
    if (quoted) throw ApiErrors.bad("CSV에 닫히지 않은 따옴표가 있습니다.");
    if (!record.isEmpty() || !field.isEmpty() || closed) {
      record.add(field.toString());
      records.add(record);
    }
    return records;
  }
}
