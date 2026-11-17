"""Execute one SQL statement against a disposable copy of the demo database."""
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import math
from pathlib import Path
import signal
import sqlite3
import sys
import time

MAX_SQL = 8192
MAX_RESPONSE = 65536
MAX_ROWS = 100
TABLES = frozenset("""
season team team_season_name player data_source batter_season pitcher_season
game team_standing_snapshot inning_score batter_game pitcher_game game_summary game_event
""".split())
FUNCTIONS = frozenset("""
abs avg coalesce count date datetime glob group_concat hex ifnull instr length like
lower ltrim max min nullif replace round rtrim strftime substr sum time total trim typeof upper
""".split())
WRITES = {sqlite3.SQLITE_INSERT, sqlite3.SQLITE_UPDATE, sqlite3.SQLITE_DELETE}
READS = {sqlite3.SQLITE_SELECT, sqlite3.SQLITE_RECURSIVE}


class QueryTimeout(Exception):
    pass


def expire(*_):
    raise QueryTimeout()


def json_bytes(value):
    return json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode()


def json_cell(value):
    if isinstance(value, bytes):
        return {"hex": value.hex()}
    if isinstance(value, float) and not math.isfinite(value):
        return str(value)
    return value


def execute(snapshot, sql):
    wrote = False
    deadline = time.monotonic() + 2

    def authorize(action, name, detail, database, _trigger):
        nonlocal wrote
        if action in WRITES and database == "main" and name in TABLES:
            wrote = True
            return sqlite3.SQLITE_OK
        if action == sqlite3.SQLITE_READ:
            # SQLite reports column-free reads, including CTEs and COUNT(*), without a database name.
            allowed = database == "main" and name in TABLES or database is None and detail == ""
            return sqlite3.SQLITE_OK if allowed else sqlite3.SQLITE_DENY
        if action in READS or action == sqlite3.SQLITE_FUNCTION and detail in FUNCTIONS:
            return sqlite3.SQLITE_OK
        return sqlite3.SQLITE_DENY

    with closing(sqlite3.connect(":memory:", isolation_level=None)) as db:
        with closing(sqlite3.connect(snapshot, uri=True, timeout=0.25)) as source:
            source.backup(db, sleep=0.01)
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA trusted_schema = OFF")
        db.execute("PRAGMA temp_store = MEMORY")
        db.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, MAX_RESPONSE)
        db.setlimit(sqlite3.SQLITE_LIMIT_COLUMN, 128)
        db.set_progress_handler(lambda: int(time.monotonic() >= deadline), 1000)
        db.set_authorizer(authorize)
        cursor = db.execute(sql)
        result = {"columns": [col[0] for col in cursor.description or ()],
                  "rows": [], "affectedRows": None, "truncated": False}
        remaining = MAX_RESPONSE - len(json_bytes(result)) - 32
        if remaining < 0:
            raise ValueError("결과 컬럼 크기가 64KB 제한을 초과했습니다.")
        for row in cursor:
            if not result["truncated"]:
                cells = [json_cell(value) for value in row]
                remaining -= len(json_bytes(cells)) + 1
                if len(result["rows"]) == MAX_ROWS or remaining < 0:
                    result["truncated"] = True
                else:
                    result["rows"].append(cells)
            if result["truncated"] and not wrote:
                break
        if wrote:
            db.set_authorizer(None)
            result["affectedRows"] = db.execute("SELECT changes()").fetchone()[0]
        return json_bytes(result)


class PreviewHandler(BaseHTTPRequestHandler):
    timeout = 3

    def do_POST(self):
        if self.path != "/preview":
            self.respond(404, json_bytes({"message": "요청 경로를 확인해 주세요."}))
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 65536:
                raise ValueError()
            sql = json.loads(self.rfile.read(size))["sql"]
            if not isinstance(sql, str) or not sql.strip() or len(sql.encode()) > MAX_SQL:
                raise ValueError()
        except (ValueError, KeyError, TypeError, OSError):
            self.respond(400, json_bytes({"message": "SQL을 8KB 이내로 입력해 주세요."}))
            return

        signal.setitimer(signal.ITIMER_REAL, 2)
        try:
            body = execute(self.server.snapshot, sql)
            status = 200
        except QueryTimeout:
            status, body = 408, json_bytes({"message": "SQL 실행 시간이 2초를 초과했습니다."})
        except sqlite3.Error as error:
            if getattr(error, "sqlite_errorcode", None) == sqlite3.SQLITE_INTERRUPT:
                status, body = 408, json_bytes({"message": "SQL 실행 시간이 2초를 초과했습니다."})
            else:
                status, body = 422, json_bytes({"message": str(error)[:512]})
        except ValueError as error:
            status, body = 422, json_bytes({"message": str(error)[:512]})
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
        self.respond(status, body)

    def respond(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    signal.signal(signal.SIGALRM, expire)
    server = HTTPServer(("0.0.0.0", 18087), PreviewHandler)
    server.snapshot = Path(sys.argv[1]).resolve(strict=True).as_uri() + "?mode=ro&immutable=1"
    server.serve_forever()
