package fullcount.domain.standings.repository;

import fullcount.domain.standings.dto.StandingResponse;
import java.util.List;

public interface StandingRepository {
  List<StandingResponse> findLatestStandingsBySeason(int year);
}
