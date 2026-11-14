"""Serve game details from the Datapopcorn research archive on demand."""
import argparse
from contextlib import closing
from functools import cache
import gzip
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
import json
import re
import sqlite3
import tarfile
from urllib.request import urlopen

ARCHIVE = ("https://raw.githubusercontent.com/team-datapopcorn/datapopcorn-assets/main/"
           "kbo-pitch-clock/data/raw_kbo_2019_2026.tar.gz")


@cache
def archive_bytes():
    with urlopen(ARCHIVE, timeout=10) as response:
        return response.read()


def table(raw, section="rows"):
    return [[cell["Text"].strip() for cell in row["row"]]
            for row in json.loads(raw)[section]]


def outs(innings):
    if " " in innings:
        count, fraction = innings.split()
        return 3 * int(count) + int(fraction[0])
    return int(innings[0]) if "/" in innings else 3 * int(innings)


def detail(db, game_id):
    game = db.execute("""
        SELECT game_id AS gameId, game_date AS date, home_team_id AS homeTeamId,
               away_team_id AS awayTeamId, home_score AS homeScore, away_score AS awayScore
        FROM game WHERE game_id=?
        """, (game_id,)).fetchone()
    if game is None:
        raise KeyError(game_id)
    payload = dict(game)
    print(f"Reading archive for {game_id}", flush=True)
    with tarfile.open(fileobj=io.BytesIO(archive_bytes()), mode="r:gz") as archive:
        with archive.extractfile(f"raw/{game_id[:4]}/{game_id}.json.gz") as entry:
            raw = json.loads(gzip.decompress(entry.read()))
    score, box = raw["scoreboard"], raw["boxscore"]
    if (score["G_ID"], score["G_DT"], int(score["T_SCORE_CN"]), int(score["B_SCORE_CN"])) != (
            game_id, game["date"], game["awayScore"], game["homeScore"]):
        raise ValueError("Archive game or score does not match")
    if score["AWAY_ID"] != game_id[8:10] or score["HOME_ID"] != game_id[10:12]:
        raise ValueError("Archive team codes do not match")
    payload.update(inningScores=[], battingStats=[], pitchingStats=[], events=[])
    player_sides = {}
    names = {}
    for side, team in (("T", game["awayTeamId"]), ("B", game["homeTeamId"])):
        names[side] = {}
        for row in db.execute("""
            SELECT p.player_id, p.player_name
            FROM player p JOIN (
              SELECT player_id FROM batter_season WHERE season_year=? AND team_id=?
              UNION SELECT player_id FROM pitcher_season WHERE season_year=? AND team_id=?
            ) s USING(player_id)
            """, (int(game_id[:4]), team, int(game_id[:4]), team)):
            names[side].setdefault(row["player_name"], []).append(row["player_id"])

    for side, scores, hitter, pitcher in zip(
            ("T", "B"), table(score["table2"]), box["arrHitter"], box["arrPitcher"], strict=True):
        team_side = "A" if side == "T" else "H"
        payload["inningScores"].extend(dict(inning=i, half=side, runs=int(value))
                                       for i, value in enumerate(scores, 1) if value.isdecimal())
        for lineup, totals in zip(table(hitter["table1"]), table(hitter["table3"]), strict=True):
            name = lineup[2]
            player_sides.setdefault(name, set()).add(side)
            ids = names[side].get(name, [])
            if len(ids) == 1:
                stats = dict(zip(("atBats", "hits", "runsBattedIn", "runs"), map(int, totals[:4]), strict=True))
                payload["battingStats"].append(dict(playerId=ids[0], teamSide=team_side, stats=stats))
        for row in table(pitcher["table"]):
            ids = names[side].get(row[0], [])
            if len(ids) == 1:
                fields = {"battersFaced": 7, "hitsAllowed": 10,
                          "homeRunsAllowed": 11, "strikeouts": 13, "runsAllowed": 14, "earnedRuns": 15}
                stats = {field: int(row[index]) for field, index in fields.items() if row[index].isdecimal()}
                stats.update(wins=int(row[2] == "승"), losses=int(row[2] == "패"),
                             saves=int(row[2] == "세"), outsPitched=outs(row[6]))
                payload["pitchingStats"].append(dict(playerId=ids[0], teamSide=team_side, stats=stats))

    for label, value, *_ in table(box["tableEtc"]):
        if label not in ("결승타", "홈런"):
            continue
        for match in re.finditer(r"([^\s(]+)\((\d+)회([^)]*)\)", value):
            name = re.sub(r"\d+호$", "", match[1])
            sides = player_sides.get(name, set())
            if len(sides) != 1:
                continue
            payload["events"].append(dict(sequence=len(payload["events"]) + 1, inning=int(match[2]),
                                          half=next(iter(sides)), description=f"{label} {name} ({match[2]}회{match[3]})"))
            if label == "홈런":
                player_id = names[next(iter(sides))].get(name, [])
                if len(player_id) == 1:
                    for line in payload["battingStats"]:
                        if line["playerId"] == player_id[0]:
                            line["stats"]["homeRuns"] = line["stats"].get("homeRuns", 0) + 1
    return payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("database")
    parser.add_argument("--port", type=int, default=18085)
    args = parser.parse_args()
    from pathlib import Path
    database = Path(args.database).resolve().as_uri() + "?mode=ro"

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            match = re.fullmatch(r"/games/(\d{8}[A-Z]{4}\d)", self.path)
            if match is None:
                self.send_error(404)
                return
            try:
                with closing(sqlite3.connect(database, uri=True)) as db:
                    db.row_factory = sqlite3.Row
                    payload = detail(db, match[1])
            except KeyError:
                self.send_error(404)
                return
            except (ValueError, OSError, tarfile.TarError, sqlite3.Error) as error:
                print(f"{match[1]}: {error}", flush=True)
                self.send_error(502)
                return
            body = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

    HTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
