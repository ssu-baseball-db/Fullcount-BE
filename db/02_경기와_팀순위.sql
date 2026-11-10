PRAGMA foreign_keys = ON;
BEGIN;

CREATE TABLE game (
    game_id TEXT PRIMARY KEY CHECK (length(trim(game_id)) > 0),
    game_date TEXT NOT NULL CHECK (length(game_date)=10 AND game_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' AND COALESCE(date(game_date,'+0 days')=game_date,0)),
    season_year INTEGER GENERATED ALWAYS AS (CAST(substr(game_date,1,4) AS INTEGER)) VIRTUAL,
    game_no INTEGER NOT NULL DEFAULT 1 CHECK (game_no IN (1,2)),
    home_team_id TEXT NOT NULL,
    away_team_id TEXT NOT NULL,
    stadium TEXT CHECK (stadium IS NULL OR length(trim(stadium)) > 0),
    game_status TEXT NOT NULL CHECK (game_status IN ('SCHEDULED','COMPLETED','CANCELLED','POSTPONED')),
    home_score INTEGER CHECK (home_score >= 0),
    away_score INTEGER CHECK (away_score >= 0),
    source_id INTEGER NOT NULL REFERENCES data_source(source_id) ON DELETE RESTRICT,
    UNIQUE (game_date, home_team_id, away_team_id, game_no),
    CHECK (home_team_id <> away_team_id),
    CHECK ((game_status='COMPLETED' AND home_score IS NOT NULL AND away_score IS NOT NULL) OR (game_status<>'COMPLETED' AND home_score IS NULL AND away_score IS NULL)),
    FOREIGN KEY (home_team_id, season_year) REFERENCES team_season_name(team_id, season_year) ON DELETE RESTRICT,
    FOREIGN KEY (away_team_id, season_year) REFERENCES team_season_name(team_id, season_year) ON DELETE RESTRICT
) STRICT;

CREATE TABLE team_standing_snapshot (
    team_id TEXT NOT NULL,
    season_year INTEGER NOT NULL,
    as_of_date TEXT NOT NULL CHECK (
        as_of_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND COALESCE(date(as_of_date,'+0 days')=as_of_date,0)
        AND CAST(substr(as_of_date,1,4) AS INTEGER)=season_year
    ),
    rank INTEGER NOT NULL CHECK (rank > 0),
    games INTEGER NOT NULL CHECK (games >= 0),
    wins INTEGER NOT NULL CHECK (wins >= 0),
    losses INTEGER NOT NULL CHECK (losses >= 0),
    draws INTEGER NOT NULL CHECK (draws >= 0),
    winning_pct REAL CHECK (winning_pct BETWEEN 0 AND 1),
    games_behind REAL CHECK (games_behind >= 0),
    source_id INTEGER NOT NULL REFERENCES data_source(source_id) ON DELETE RESTRICT,
    PRIMARY KEY (team_id, season_year, as_of_date),
    CHECK (games = wins + losses + draws),
    FOREIGN KEY (team_id, season_year) REFERENCES team_season_name(team_id, season_year) ON DELETE RESTRICT
) STRICT;

CREATE INDEX idx_game_home_date ON game(home_team_id, game_date);
CREATE INDEX idx_game_away_date ON game(away_team_id, game_date);
CREATE INDEX idx_game_date ON game(game_date);
CREATE INDEX idx_standing_season_date_rank ON team_standing_snapshot(season_year, as_of_date, rank);

COMMIT;
