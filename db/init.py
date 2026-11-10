"""Create the local database without replacing an existing database."""

from contextlib import closing
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory


def main():
    sql_dir = Path(__file__).resolve().parent
    target = sql_dir.parent / "data" / "fullcount.db"
    if sqlite3.sqlite_version_info < (3, 37):
        raise SystemExit("SQLite 3.37 이상이 포함된 Python이 필요합니다.")
    if target.exists():
        raise SystemExit(f"기존 DB를 보존했습니다. {target}")
    target.parent.mkdir(exist_ok=True)
    with TemporaryDirectory(dir=target.parent) as temporary_dir:
        staged = Path(temporary_dir) / target.name
        with closing(sqlite3.connect(staged)) as db:
            for script in sorted(sql_dir.glob("0[1-5]_*.sql")):
                db.executescript(script.read_text(encoding="utf-8"))
            counts = db.execute("""
                SELECT (SELECT count(*) FROM team_standing_snapshot),
                       (SELECT count(*) FROM game)
                """).fetchone()
            if counts != (120, 8640) or db.execute("PRAGMA foreign_key_check").fetchone():
                raise SystemExit("초기 데이터 또는 외래키 검증에 실패했습니다.")
        try:
            target.hardlink_to(staged)
        except FileExistsError:
            raise SystemExit(f"기존 DB를 보존했습니다. {target}") from None
    print(f"초기화 완료. {target}")


if __name__ == "__main__":
    main()
