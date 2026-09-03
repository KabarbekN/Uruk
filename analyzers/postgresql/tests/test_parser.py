from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "shared"))
from test_support import run_cli, request, assert_evidence, fixture_files


class PostgreSQLTests(unittest.TestCase):
    def test_migrations_queries_and_ownership(self):
        files = fixture_files(Path(__file__).parent / "fixtures")
        result, output, workspace = run_cli("postgresql", files, request(capabilities=["DATABASE_ACCESS", "DATA_OWNERSHIP"]))
        self.assertIn(result.returncode, (0, 10), (result.stderr, output))
        facts = output["facts.ndjson"]
        kinds = {fact["kind"] for fact in facts}
        self.assertTrue({"TABLE", "COLUMN", "DATA_RULE", "DATABASE_TRIGGER", "AUTHORIZATION_RULE", "DATABASE_VIEW", "DATABASE_FUNCTION", "DATABASE_READ", "DATABASE_WRITE", "FILTER", "SORT", "AGGREGATION", "DATA_TRANSFORMATION"} <= kinds)
        ownership = {f["properties"].get("ownership") for f in facts}
        self.assertTrue({"DATABASE_DEFAULT", "DATABASE_GENERATED", "DATABASE_CONSTRAINT", "DATABASE_TRIGGER", "DATABASE_POLICY"} <= ownership)
        trigger = next(f for f in facts if f["kind"] == "DATABASE_TRIGGER")
        self.assertEqual(trigger["properties"]["events"], ["UPDATE"])
        self.assertEqual(trigger["properties"]["timing"], "AFTER")
        self.assertTrue(any(f["properties"].get("edgeKind") == "WRITES" and f["properties"].get("targetKey") == "db:table:sales.audit" for f in facts))
        self.assertTrue(any(f["stableKey"] == "db:column:sales.orders.created_at" for f in facts))
        assert_evidence(self, output, workspace)
        _, again, _ = run_cli("postgresql", files, request(capabilities=["DATABASE_ACCESS", "DATA_OWNERSHIP"]))
        self.assertEqual(facts, again["facts.ndjson"])

    def test_malformed_sql_does_not_invent_facts(self):
        result, output, _ = run_cli("postgresql", {"broken.sql": "CREATE TABLE x (", "good.sql": "CREATE TABLE actual (id int);"}, request(capabilities=["TABLES"]))
        self.assertEqual(result.returncode, 10, output)
        tables = [f["properties"]["name"] for f in output["facts.ndjson"] if f["kind"] == "TABLE"]
        self.assertEqual(tables, ["actual"])
        self.assertEqual(output["coverage.json"]["filesFailed"], 1)

    def test_comments_strings_and_ctes_do_not_become_tables(self):
        sql = "-- CREATE TABLE fake (id int);\nWITH selected AS (SELECT id FROM real_table) SELECT 'CREATE TABLE invented()' FROM selected;"
        result, output, workspace = run_cli("postgresql", {"query.sql": sql}, request(capabilities=["DATABASE_ACCESS"]))
        self.assertEqual(result.returncode, 0, output)
        self.assertFalse(any(f["kind"] == "TABLE" for f in output["facts.ndjson"]))
        targets = {f["properties"].get("targetKey") for f in output["facts.ndjson"]}
        self.assertIn("db:table:real_table", targets)
        self.assertNotIn("db:table:selected", targets)
        assert_evidence(self, output, workspace)

    def test_alter_table_and_liquibase_embedded_sql(self):
        files = {"migration.sql": "CREATE TABLE t (id int); ALTER TABLE t ADD COLUMN x int DEFAULT 7; ALTER TABLE t ALTER COLUMN x SET DEFAULT 9;",
                 "changelog.xml": '<databaseChangeLog><changeSet id="1"><sql>CREATE TABLE embedded (value int);</sql></changeSet></databaseChangeLog>'}
        result, output, workspace = run_cli("postgresql", files, request(capabilities=["TABLES", "DEFAULTS"]))
        self.assertIn(result.returncode, (0, 10), output)
        self.assertTrue(any(f["kind"] == "TABLE" and f["properties"]["name"] == "embedded" for f in output["facts.ndjson"]))
        expressions = {f["properties"].get("expression") for f in output["facts.ndjson"] if f["kind"] == "DATA_RULE"}
        self.assertTrue({"7", "9"} <= expressions)
        assert_evidence(self, output, workspace)

    def test_dollar_quoted_and_unicode_ranges(self):
        sql = "SELECT '\u00e9;\U0001f600';\r\nCREATE TABLE quoted (text text DEFAULT $$a;b$$);\r\n"
        result, output, workspace = run_cli("postgresql", {"unicode.sql": sql}, request(capabilities=["TABLES", "DEFAULTS"]))
        self.assertEqual(result.returncode, 0, output)
        assert_evidence(self, output, workspace)


if __name__ == "__main__":
    unittest.main()
