package fullcount.domain.standings.repository;

import fullcount.domain.standings.dto.StandingResponse;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class StandingRepositoryImpl implements StandingRepository {
  private final EntityManager entityManager;

  public StandingRepositoryImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public List<StandingResponse> findLatestStandingsBySeason(int year) {
    String sql = """
        SELECT s.rank, s.team_id, n.team_name,
               s.wins, s.losses, s.draws, s.winning_pct, s.as_of_date, s.games, s.games_behind
        FROM team_standing_snapshot s
        JOIN team_season_name n
          ON n.team_id = s.team_id
         AND n.season_year = s.season_year
        WHERE s.season_year = :year
          AND s.as_of_date = (
              SELECT MAX(as_of_date)
              FROM team_standing_snapshot
              WHERE season_year = :year
          )
        ORDER BY s.rank, s.team_id
        """;
    List<?> rows = entityManager.createNativeQuery(sql)
        .setParameter("year", year)
        .getResultList();

    List<StandingResponse> standings = new ArrayList<>();
    for (Object rawRow : rows) {
      Object[] row = (Object[]) rawRow;
      standings.add(new StandingResponse(
          ((Number) row[0]).intValue(),
          (String) row[1],
          (String) row[2],
          ((Number) row[3]).intValue(),
          ((Number) row[4]).intValue(),
          ((Number) row[5]).intValue(),
          (Number) row[6],
          (String) row[7],
          ((Number) row[8]).intValue(),
          (Number) row[9]
      ));
    }

    return standings;
  }
}
