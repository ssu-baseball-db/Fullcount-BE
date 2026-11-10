-- 풀카운트 기본 데이터 모델 v2 / 2026-11-08
-- 새 데이터베이스에 한 번만 실행한다. 기존 테이블을 삭제하지 않는다.
-- SQLite 3.37 이상. 모든 연결에서 트랜잭션 시작 전에 외래키를 활성화한다.
PRAGMA foreign_keys = ON;
BEGIN;
CREATE TABLE season (
    season_year INTEGER PRIMARY KEY CHECK (season_year BETWEEN 2015 AND 2026),
    games_per_team INTEGER NOT NULL CHECK (games_per_team > 0)
) STRICT;
CREATE TABLE team (
    team_id TEXT PRIMARY KEY CHECK (team_id IN ('KIA','KT','LG','NC','SSG','DOOSAN','LOTTE','SAMSUNG','KIWOOM','HANWHA'))
) STRICT;
CREATE TABLE team_season_name (
    team_id TEXT NOT NULL REFERENCES team(team_id) ON DELETE RESTRICT,
    season_year INTEGER NOT NULL REFERENCES season(season_year) ON DELETE RESTRICT,
    team_name TEXT NOT NULL CHECK (length(trim(team_name)) > 0),
    PRIMARY KEY (team_id, season_year),
    UNIQUE (season_year, team_name)
) STRICT;
CREATE TABLE player (
    player_id TEXT PRIMARY KEY CHECK (length(trim(player_id)) > 0),
    player_name TEXT NOT NULL CHECK (length(trim(player_name)) > 0),
    birth_date TEXT CHECK (birth_date IS NULL OR (length(birth_date)=10 AND birth_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' AND COALESCE(date(birth_date,'+0 days')=birth_date,0))),
    height_cm REAL CHECK (height_cm > 0),
    weight_kg REAL CHECK (weight_kg > 0),
    throw_hand TEXT CHECK (throw_hand IN ('R','L')),
    bat_hand TEXT CHECK (bat_hand IN ('R','L','S'))
) STRICT;
CREATE TABLE data_source (
    source_id INTEGER PRIMARY KEY,
    source_name TEXT NOT NULL CHECK (length(trim(source_name)) > 0),
    source_uri TEXT CHECK (source_uri IS NULL OR length(trim(source_uri)) > 0),
    loaded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
) STRICT;
CREATE TABLE batter_season (
    player_id TEXT NOT NULL REFERENCES player(player_id) ON DELETE RESTRICT,
    season_year INTEGER NOT NULL,
    team_id TEXT NOT NULL,
    source_id INTEGER NOT NULL REFERENCES data_source(source_id) ON DELETE RESTRICT,
    season_position TEXT CHECK (season_position IN ('P','C','1B','2B','3B','SS','OF','DH')),
    games INTEGER CHECK (games >= 0),
    plate_appearances INTEGER CHECK (plate_appearances >= 0),
    at_bats INTEGER CHECK (at_bats >= 0),
    runs INTEGER CHECK (runs >= 0),
    hits INTEGER CHECK (hits >= 0),
    doubles INTEGER CHECK (doubles >= 0),
    triples INTEGER CHECK (triples >= 0),
    home_runs INTEGER CHECK (home_runs >= 0),
    runs_batted_in INTEGER CHECK (runs_batted_in >= 0),
    stolen_bases INTEGER CHECK (stolen_bases >= 0),
    caught_stealing INTEGER CHECK (caught_stealing >= 0),
    walks INTEGER CHECK (walks >= 0),
    hit_by_pitch INTEGER CHECK (hit_by_pitch >= 0),
    strikeouts INTEGER CHECK (strikeouts >= 0),
    sacrifice_hits INTEGER CHECK (sacrifice_hits >= 0),
    sacrifice_flies INTEGER CHECK (sacrifice_flies >= 0),
    grounded_into_double_plays INTEGER CHECK (grounded_into_double_plays >= 0),
    PRIMARY KEY (player_id, season_year),
    FOREIGN KEY (team_id, season_year) REFERENCES team_season_name(team_id, season_year) ON DELETE RESTRICT,
    CHECK (at_bats <= plate_appearances),
    CHECK (hits <= at_bats),
    CHECK (doubles <= hits),
    CHECK (triples <= hits),
    CHECK (home_runs <= hits),
    CHECK (COALESCE(doubles,0) + COALESCE(triples,0) + COALESCE(home_runs,0) <= hits)
) STRICT;
CREATE TABLE pitcher_season (
    player_id TEXT NOT NULL REFERENCES player(player_id) ON DELETE RESTRICT,
    season_year INTEGER NOT NULL,
    team_id TEXT NOT NULL,
    source_id INTEGER NOT NULL REFERENCES data_source(source_id) ON DELETE RESTRICT,
    season_position TEXT CHECK (season_position IN ('P','C','1B','2B','3B','SS','OF','DH')),
    games INTEGER CHECK (games >= 0),
    wins INTEGER CHECK (wins >= 0),
    losses INTEGER CHECK (losses >= 0),
    saves INTEGER CHECK (saves >= 0),
    holds INTEGER CHECK (holds >= 0),
    outs_pitched INTEGER CHECK (outs_pitched >= 0),
    hits_allowed INTEGER CHECK (hits_allowed >= 0),
    home_runs_allowed INTEGER CHECK (home_runs_allowed >= 0),
    walks_allowed INTEGER CHECK (walks_allowed >= 0),
    hit_batters INTEGER CHECK (hit_batters >= 0),
    strikeouts INTEGER CHECK (strikeouts >= 0),
    runs_allowed INTEGER CHECK (runs_allowed >= 0),
    earned_runs INTEGER CHECK (earned_runs >= 0),
    batters_faced INTEGER CHECK (batters_faced >= 0),
    PRIMARY KEY (player_id, season_year),
    FOREIGN KEY (team_id, season_year) REFERENCES team_season_name(team_id, season_year) ON DELETE RESTRICT,
    CHECK (earned_runs <= runs_allowed),
    CHECK (home_runs_allowed <= hits_allowed)
) STRICT;
CREATE INDEX idx_player_name ON player(player_name);
CREATE INDEX idx_batter_season_team ON batter_season(season_year, team_id);
CREATE INDEX idx_pitcher_season_team ON pitcher_season(season_year, team_id);
PRAGMA user_version = 1;
COMMIT;
