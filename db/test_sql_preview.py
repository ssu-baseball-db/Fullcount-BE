from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from sql_preview import execute, MAX_RESPONSE


class SqlPreviewTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "demo.db"
        with closing(sqlite3.connect(path)) as db:
            db.executescript("""
                CREATE TABLE player (player_id TEXT PRIMARY KEY, player_name TEXT NOT NULL);
                CREATE TABLE batter_season (
                    player_id TEXT REFERENCES player(player_id),
                    home_runs INTEGER CHECK (home_runs >= 0)
                );
                INSERT INTO player VALUES ('75847', '최정');
                INSERT INTO batter_season VALUES ('75847', 37);
            """)
        self.snapshot = path.as_uri() + "?mode=ro&immutable=1"

    def run_sql(self, sql):
        return json.loads(execute(self.snapshot, sql))

    def test_returning_and_isolation(self):
        result = self.run_sql("""
            UPDATE player SET player_name = '최정 (시연)'
            WHERE player_id = '75847' RETURNING player_name
        """)
        self.assertEqual(result["affectedRows"], 1)
        self.assertEqual(result["rows"], [["최정 (시연)"]])
        self.assertEqual(self.run_sql("SELECT player_name FROM player")["rows"], [["최정"]])

    def test_constraints_and_statement_restrictions(self):
        for sql in (
            "UPDATE batter_season SET home_runs = -1",
            "DELETE FROM player",
            "SELECT 1; SELECT 2",
            "PRAGMA foreign_keys",
            "ATTACH DATABASE ':memory:' AS other",
            "SELECT load_extension('module')",
            "DROP TABLE player",
        ):
            with self.subTest(sql=sql), self.assertRaises(sqlite3.Error):
                self.run_sql(sql)

    def test_truncation(self):
        prefix = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<110) "
        result = self.run_sql(prefix + "SELECT i FROM n")
        self.assertEqual(len(result["rows"]), 100)
        self.assertTrue(result["truncated"])
        self.assertEqual(self.run_sql(prefix + "SELECT count(*) FROM n")["rows"], [[110]])
        large_value = "a" * 600
        payload = execute(self.snapshot, prefix + f"SELECT hex('{large_value}' || i) FROM n")
        self.assertLessEqual(len(payload), MAX_RESPONSE)
        result = json.loads(payload)
        self.assertTrue(result["truncated"])
        self.assertLess(len(result["rows"]), 100)


if __name__ == "__main__":
    unittest.main()
