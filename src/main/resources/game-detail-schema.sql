CREATE TABLE IF NOT EXISTS inning_score (
    game_id TEXT NOT NULL REFERENCES game(game_id) ON DELETE RESTRICT,
    inning_no INTEGER NOT NULL CHECK (inning_no >= 1),
    half TEXT NOT NULL CHECK (half IN ('T','B')),
    runs INTEGER NOT NULL CHECK (runs >= 0),
    PRIMARY KEY (game_id, inning_no, half)
) STRICT;
CREATE TABLE IF NOT EXISTS batter_game (
    game_id TEXT NOT NULL REFERENCES game(game_id) ON DELETE RESTRICT,
    player_id TEXT NOT NULL REFERENCES player(player_id) ON DELETE RESTRICT,
    team_side TEXT NOT NULL CHECK (team_side IN ('H','A')),
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
    PRIMARY KEY (game_id, player_id),
    CHECK (games IS NULL OR games=1),
    CHECK (at_bats <= plate_appearances),
    CHECK (hits <= at_bats),
    CHECK (doubles <= hits),
    CHECK (triples <= hits),
    CHECK (home_runs <= hits),
    CHECK (COALESCE(doubles,0) + COALESCE(triples,0) + COALESCE(home_runs,0) <= hits)
) STRICT;
CREATE TABLE IF NOT EXISTS pitcher_game (
    game_id TEXT NOT NULL REFERENCES game(game_id) ON DELETE RESTRICT,
    player_id TEXT NOT NULL REFERENCES player(player_id) ON DELETE RESTRICT,
    team_side TEXT NOT NULL CHECK (team_side IN ('H','A')),
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
    PRIMARY KEY (game_id, player_id),
    CHECK (games IS NULL OR games=1),
    CHECK (earned_runs <= runs_allowed),
    CHECK (home_runs_allowed <= hits_allowed)
) STRICT;
CREATE TABLE IF NOT EXISTS game_summary (
    game_id TEXT PRIMARY KEY REFERENCES game(game_id) ON DELETE RESTRICT,
    summary_text TEXT NOT NULL CHECK (length(trim(summary_text)) > 0),
    model_name TEXT NOT NULL CHECK (length(trim(model_name)) > 0),
    generated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
) STRICT;
CREATE TABLE IF NOT EXISTS game_event (
    game_id TEXT NOT NULL REFERENCES game(game_id) ON DELETE RESTRICT,
    sequence INTEGER NOT NULL CHECK (sequence >= 1),
    inning_no INTEGER NOT NULL CHECK (inning_no >= 1),
    half TEXT NOT NULL CHECK (half IN ('T','B')),
    description TEXT NOT NULL CHECK (length(trim(description)) > 0),
    PRIMARY KEY (game_id, sequence)
) STRICT;
