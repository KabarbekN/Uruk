import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "shared"))
from test_support import run_cli, request, fixture_files, assert_evidence, symlink_or_skip


class ExtendedPostgreSQLTests(unittest.TestCase):
    def analyze(self, files, prepare=None):
        return run_cli("postgresql", files, request(capabilities=["TABLES", "DEFAULTS", "FUNCTIONS", "DATABASE_ACCESS"]), prepare)

    def test_xml_yaml_json_includes_and_original_evidence(self):
        files = fixture_files(Path(__file__).parent / "fixtures/liquibase")
        result, outputs, workspace = self.analyze(files)
        self.assertEqual(result.returncode, 10, outputs)
        facts = outputs["facts.ndjson"]
        tables = {f["stableKey"] for f in facts if f["kind"] == "TABLE"}
        self.assertEqual(tables, {"db:table:public." + name for name in ("xml_orders", "yaml_orders", "json_orders", "included_sql")})
        column = next(f for f in facts if f["stableKey"] == "db:column:public.xml_orders.created_at")
        self.assertEqual(column["origin"], "FRAMEWORK_DERIVED")
        primary = next(f for f in facts if f["stableKey"] == "db:column:public.yaml_orders.id")
        self.assertFalse(primary["properties"]["nullable"])
        self.assertEqual(column["evidence"][0]["filePath"], "root.xml")
        self.assertEqual(column["evidence"][0]["startLine"], 8)
        self.assertEqual(column["evidence"][0]["endLine"], 8)
        default = next(f for f in facts if f["properties"].get("ownerKey") == column["stableKey"] and f["properties"].get("ruleType") == "DEFAULT")
        self.assertEqual(default["properties"]["expression"], "now()")
        included = next(f for f in facts if f["stableKey"] == "db:table:public.included_sql")
        self.assertEqual(included["evidence"][0]["filePath"], "sql/extra.sql")
        self.assertEqual(included["origin"], "DATABASE_DERIVED")
        self.assertEqual(outputs["coverage.json"]["filesParsed"], 4)
        self.assertEqual(outputs["coverage.json"]["filesFailed"], 0)
        assert_evidence(self, outputs, workspace)
        self.assertEqual(facts, self.analyze(files)[1]["facts.ndjson"])

    def test_xml_entities_and_json_escaped_sql_keep_original_spans(self):
        files = {"changelog.xml": '<databaseChangeLog>\n<changeSet id="x" author="a">\n<sql>SELECT id FROM t WHERE amount &gt; 5;</sql>\n</changeSet>\n</databaseChangeLog>',
                 "changelog.json": json.dumps({"databaseChangeLog": [{"changeSet": {"id": "j", "author": "a", "changes": [{"sql": {"sql": "SELECT '\u00e9' AS label\nFROM other_table\nWHERE id > 1;"}}]}}]}, indent=2)}
        result, outputs, workspace = self.analyze(files)
        self.assertEqual(result.returncode, 0, outputs)
        xml_filter = next(f for f in outputs["facts.ndjson"] if f["kind"] == "FILTER" and f["evidence"][0]["filePath"] == "changelog.xml")
        self.assertEqual(xml_filter["evidence"][0]["startLine"], 3)
        self.assertEqual(xml_filter["evidence"][0]["endLine"], 3)
        assert_evidence(self, outputs, workspace)

    def test_procedural_assignments_branches_static_sql_and_dynamic_unknown(self):
        source = (Path(__file__).parent / "fixtures/procedural.sql").read_text()
        result, outputs, workspace = self.analyze({"procedure.sql": source})
        self.assertEqual(result.returncode, 10, outputs)
        facts = outputs["facts.ndjson"]
        self.assertTrue({"CONDITION", "LOOP", "DATA_TRANSFORMATION", "DATABASE_WRITE", "RETURN_OUTCOME", "EXCEPTION"} <= {f["kind"] for f in facts})
        assignment = next(f for f in facts if f["properties"].get("expression") == "total := n * 2")
        self.assertEqual(assignment["evidence"][0]["startLine"], 6)
        self.assertEqual(assignment["evidence"][0]["endLine"], 6)
        write = next(f for f in facts if f["kind"] == "DATABASE_WRITE")
        self.assertEqual(write["evidence"][0]["startLine"], 7)
        self.assertEqual(write["evidence"][0]["endLine"], 7)
        self.assertTrue(write["properties"]["guardedByConditions"])
        self.assertTrue(any(f["properties"].get("edgeKind") == "BRANCH_TRUE" for f in facts))
        targets = {f["properties"].get("targetKey") for f in facts if f["properties"].get("edgeKind") in {"READS", "WRITES"}}
        self.assertEqual(targets, {"db:table:public.orders"})
        self.assertTrue(any(d["code"] == "PARTIAL_SQL_COVERAGE" and "Dynamic SQL" in d["message"] for d in outputs["diagnostics.ndjson"]))
        assert_evidence(self, outputs, workspace)

    def test_multiline_function_header_and_same_line_statements(self):
        sql = "-- prefix\nCREATE FUNCTION public.calc(\n n integer\n) RETURNS integer\nLANGUAGE plpgsql\nAS $tag$\nBEGIN\n IF n > 1 THEN n := 2; n := n + 3; END IF;\n RETURN n;\nEND;\n$tag$;"
        result, outputs, workspace = self.analyze({"body.sql": sql})
        self.assertEqual(result.returncode, 0, outputs)
        assignments = [f for f in outputs["facts.ndjson"] if f["properties"].get("transformationKind") == "PLPGSQL_ASSIGNMENT"]
        self.assertEqual(len(assignments), 2)
        self.assertEqual([f["evidence"][0]["startLine"] for f in assignments], [8, 8])
        spans = [(f["evidence"][0]["startColumn"], f["evidence"][0]["endColumn"]) for f in assignments]
        self.assertLessEqual(spans[0][1], spans[1][0])
        assert_evidence(self, outputs, workspace)

    def test_sql_query_clause_ranges_and_aggregate_syntax(self):
        sql = "SELECT o.customer_id, count(DISTINCT o.id) FILTER (WHERE o.amount > 100) AS count,\n       o.amount * 0.9 AS discounted\nFROM orders o JOIN customers c ON c.id = o.customer_id\nWHERE o.amount >= 500\nGROUP BY o.customer_id, o.amount\nHAVING count(*) > 1\nORDER BY o.customer_id;"
        result, outputs, workspace = self.analyze({"query.sql": sql})
        self.assertEqual(result.returncode, 0, outputs)
        facts = outputs["facts.ndjson"]
        clause = next(f for f in facts if f["kind"] == "FILTER" and f["properties"]["name"] == "whereClause")
        self.assertEqual(clause["evidence"][0]["startLine"], 4)
        self.assertEqual(clause["evidence"][0]["endLine"], 4)
        aggregates = [f for f in facts if f["kind"] == "AGGREGATION" and f["properties"].get("aggregateSyntax")]
        self.assertEqual(len(aggregates), 2)
        self.assertTrue(any(f["properties"].get("distinct") for f in aggregates))
        self.assertTrue(any(f["properties"].get("targetColumn") == "discounted" for f in facts))
        self.assertTrue(any(f["properties"].get("name") == "JOIN" for f in facts))
        assert_evidence(self, outputs, workspace)

    def test_include_cycles_and_traversal_are_bounded(self):
        files = {"a.yaml": "databaseChangeLog:\n  - include: {file: b.yaml, relativeToChangelogFile: true}\n  - changeSet:\n      id: unsafe\n      author: test\n      changes:\n        - sqlFile: {path: ../outside.sql, relativeToChangelogFile: true}\n",
                 "b.yaml": "databaseChangeLog:\n  - include: {file: a.yaml, relativeToChangelogFile: true}\n"}
        result, outputs, workspace = self.analyze(files)
        self.assertEqual(result.returncode, 10, outputs)
        codes = {d["code"] for d in outputs["diagnostics.ndjson"]}
        self.assertTrue({"INCLUDE_CYCLE", "INCLUDE_REJECTED"} <= codes)
        self.assertFalse(any(f["kind"] == "TABLE" for f in outputs["facts.ndjson"]))
        assert_evidence(self, outputs, workspace)

    def test_symlink_includes_never_read_the_target(self):
        def prepare(workspace, root):
            external = root / "outside.sql"
            external.write_text("CREATE TABLE escaped(id int);")
            symlink_or_skip(external, workspace / "linked.sql")
        files = {"a.yaml": "databaseChangeLog:\n  - changeSet:\n      id: unsafe\n      author: test\n      changes:\n        - sqlFile: {path: linked.sql, relativeToChangelogFile: true}\n"}
        result, outputs, workspace = self.analyze(files, prepare)
        self.assertEqual(result.returncode, 10, outputs)
        codes = {d["code"] for d in outputs["diagnostics.ndjson"]}
        self.assertTrue({"INCLUDE_REJECTED", "SYMLINK_REJECTED"} <= codes)
        self.assertFalse(any(f["kind"] == "TABLE" for f in outputs["facts.ndjson"]))
        assert_evidence(self, outputs, workspace)

    def test_include_all_is_sorted_and_dbms_exclusions_stay_excluded(self):
        files = {"root.xml": '<databaseChangeLog><includeAll path="sql" relativeToChangelogFile="true"/><changeSet id="mysql" author="a" dbms="mysql"><sqlFile path="mysql.sql" relativeToChangelogFile="true"/></changeSet></databaseChangeLog>',
                 "sql/002.sql": "CREATE TABLE second_table(id int);", "sql/001.sql": "CREATE TABLE first_table(id int);",
                 "mysql.sql": "CREATE TABLE should_not_exist(id int);"}
        result, outputs, workspace = self.analyze(files)
        self.assertEqual(result.returncode, 0, outputs)
        self.assertEqual({f["properties"]["name"] for f in outputs["facts.ndjson"] if f["kind"] == "TABLE"}, {"first_table", "second_table"})
        self.assertEqual(outputs["coverage.json"]["metadata"]["filesSkipped"], 1)
        assert_evidence(self, outputs, workspace)

    def test_unknown_changes_malformed_documents_and_native_body_errors(self):
        files = {"changelog.yaml": "databaseChangeLog:\n - changeSet:\n     id: x\n     author: a\n     changes:\n       - customChange: {class: NeverExecute}\n       - createTable: {tableName: '${dynamic}'}\n       - sql: {sql: 'CREATE TABLE known(id int);'}\n",
                 "broken-changelog.json": '{"databaseChangeLog": [',
                 "bad.sql": "CREATE FUNCTION bad() RETURNS int LANGUAGE plpgsql AS $$ BEGIN IF THEN RETURN 1; END; $$;"}
        result, outputs, _ = self.analyze(files)
        self.assertEqual(result.returncode, 10, outputs)
        codes = {d["code"] for d in outputs["diagnostics.ndjson"]}
        self.assertTrue({"CHANGELOG_PARSE_ERROR", "LIQUIBASE_UNSUPPORTED_CHANGE", "LIQUIBASE_DYNAMIC_PROPERTY", "PLPGSQL_PARSE_ERROR"} <= codes)
        self.assertEqual([f["properties"]["name"] for f in outputs["facts.ndjson"] if f["kind"] == "TABLE"], ["known"])

    def test_yaml_aliases_duplicate_keys_and_xml_entities_are_rejected(self):
        files = {"alias-changelog.yaml": "databaseChangeLog: &a [*a]", "duplicate-changelog.json": '{"databaseChangeLog": [], "databaseChangeLog": []}',
                 "entity-changelog.xml": '<!DOCTYPE databaseChangeLog [<!ENTITY x SYSTEM "file:///etc/passwd">]><databaseChangeLog>&x;</databaseChangeLog>'}
        result, outputs, _ = self.analyze(files)
        self.assertEqual(result.returncode, 10, outputs)
        self.assertEqual(outputs["coverage.json"]["filesFailed"], 3)
        self.assertFalse(any(f["kind"] != "FILE" for f in outputs["facts.ndjson"]))

    def test_nested_join_never_uses_unrelated_outer_from_evidence(self):
        sql = "SELECT\n (SELECT count(*) FROM child c JOIN other_child o ON o.id = c.id) AS amount\nFROM parent\nWHERE id > 1\n    ORDER BY id;"
        result, outputs, workspace = self.analyze({"nested.sql": sql})
        self.assertEqual(result.returncode, 0, outputs)
        facts = outputs["facts.ndjson"]
        join = next(f for f in facts if f["properties"].get("name") == "JOIN")
        self.assertLessEqual(join["evidence"][0]["startLine"], 2)
        self.assertGreaterEqual(join["evidence"][0]["endLine"], 2)
        where = next(f for f in facts if f["kind"] == "FILTER")
        self.assertEqual(where["evidence"][0]["endLine"], 4)
        assert_evidence(self, outputs, workspace)

    def test_multiline_initializer_and_sql_language_body_evidence(self):
        sql = "CREATE FUNCTION p() RETURNS int LANGUAGE plpgsql AS $$\nDECLARE\n total integer :=\n   (1 + 2);\nBEGIN RETURN total; END;\n$$;\nCREATE FUNCTION s() RETURNS int LANGUAGE sql AS $q$\n SELECT amount * 2\n FROM public.orders\n WHERE id > 1;\n$q$;"
        result, outputs, workspace = self.analyze({"bodies.sql": sql})
        self.assertEqual(result.returncode, 0, outputs)
        facts = outputs["facts.ndjson"]
        initializer = next(f for f in facts if f["properties"].get("name") == "INITIALIZE")
        self.assertEqual(initializer["evidence"][0]["startLine"], 3)
        self.assertEqual(initializer["evidence"][0]["endLine"], 4)
        read = next(f for f in facts if f["kind"] == "DATABASE_READ")
        self.assertEqual(read["evidence"][0]["startLine"], 8)
        self.assertEqual(read["evidence"][0]["endLine"], 10)
        assert_evidence(self, outputs, workspace)

    def test_disconnected_include_cycles_are_diagnosed(self):
        files = {"root.yaml": "databaseChangeLog: []",
                 "a.yaml": "databaseChangeLog:\n - include: {file: b.yaml}\n",
                 "b.yaml": "databaseChangeLog:\n - include: {file: a.yaml}\n",
                 "c.yaml": "databaseChangeLog:\n - include: {file: d.yaml}\n",
                 "d.yaml": "databaseChangeLog:\n - include: {file: c.yaml}\n"}
        result, outputs, _ = self.analyze(files)
        self.assertEqual(result.returncode, 10, outputs)
        self.assertEqual(sum(d["code"] == "INCLUDE_CYCLE" for d in outputs["diagnostics.ndjson"]), 2)
        self.assertEqual(outputs["coverage.json"]["filesParsed"], 5)

    def test_unsupported_native_statements_remain_explicitly_partial(self):
        sql = "CREATE FUNCTION p(n int) RETURNS int LANGUAGE plpgsql AS $$ BEGIN CASE n WHEN 1 THEN UPDATE hidden_table SET n = 2; ELSE NULL; END CASE; RETURN n; END; $$;"
        result, outputs, workspace = self.analyze({"case.sql": sql})
        self.assertEqual(result.returncode, 10, outputs)
        self.assertTrue(any("PLpgSQL_stmt_case" in d["message"] for d in outputs["diagnostics.ndjson"]))
        self.assertFalse(any(f["properties"].get("edgeKind") in {"READS", "WRITES"} for f in outputs["facts.ndjson"]))
        assert_evidence(self, outputs, workspace)

    def test_procedural_utility_sql_does_not_become_a_table_read(self):
        sql = "CREATE FUNCTION p() RETURNS void LANGUAGE plpgsql AS $$ BEGIN CREATE TABLE future_table(id int); END; $$;"
        result, outputs, workspace = self.analyze({"utility.sql": sql})
        self.assertEqual(result.returncode, 10, outputs)
        self.assertFalse(any(f["properties"].get("edgeKind") in {"READS", "WRITES"} for f in outputs["facts.ndjson"]))
        self.assertTrue(any("utility SQL" in d["message"] for d in outputs["diagnostics.ndjson"]))
        assert_evidence(self, outputs, workspace)

    def test_xml_self_closing_ranges_and_foreign_namespaces(self):
        xml = '<databaseChangeLog xmlns:custom="urn:custom"><changeSet id="x" author="a">\n<createTable tableName="known"><column name="id" type="int"/></createTable\n>\n<custom:createTable tableName="not_core"/>\n</changeSet></databaseChangeLog>'
        result, outputs, workspace = self.analyze({"changelog.xml": xml})
        self.assertEqual(result.returncode, 10, outputs)
        column = next(f for f in outputs["facts.ndjson"] if f["kind"] == "COLUMN")
        self.assertEqual(column["evidence"][0]["startLine"], 2)
        self.assertEqual(column["evidence"][0]["endLine"], 2)
        self.assertEqual([f["properties"]["name"] for f in outputs["facts.ndjson"] if f["kind"] == "TABLE"], ["known"])
        self.assertTrue(any(d["code"] == "LIQUIBASE_UNSUPPORTED_CHANGE" for d in outputs["diagnostics.ndjson"]))
        assert_evidence(self, outputs, workspace)


if __name__ == "__main__":
    unittest.main()
