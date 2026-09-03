from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "shared"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_support import run_cli, request, assert_evidence, fixture_files


class TreeSitterTests(unittest.TestCase):
    def test_java_and_typescript_are_real_syntax_trees(self):
        files = fixture_files(Path(__file__).parent / "fixtures")
        result, output, workspace = run_cli("tree-sitter", files)
        self.assertEqual(result.returncode, 0, (result.stderr, output))
        kinds = {fact["kind"] for fact in output["facts.ndjson"]}
        self.assertTrue({"CLASS", "INTERFACE", "METHOD", "PARAMETER", "IMPORT", "ANNOTATION", "CONDITION", "LOOP", "ASSIGNMENT", "LITERAL", "CALL"} <= kinds)
        classes = [f["properties"]["name"] for f in output["facts.ndjson"] if f["kind"] == "CLASS"]
        self.assertEqual(sorted(classes), ["Order", "Service"])
        self.assertEqual({f["origin"] for f in output["facts.ndjson"]}, {"STATIC_SYNTAX"})
        self.assertFalse(any(f["kind"] == "RELATION" and f["properties"]["edgeKind"] == "CALLS" for f in output["facts.ndjson"]))
        assert_evidence(self, output, workspace)
        repeated, again, _ = run_cli("tree-sitter", files)
        self.assertEqual(repeated.returncode, 0)
        self.assertEqual(output["facts.ndjson"], again["facts.ndjson"])

    def test_malformed_partial_and_absent_language_not_loaded(self):
        import analyzer
        from runtime import Run, Source
        analyzer.PARSERS.clear()
        run = Run(analyzer.DESCRIPTOR)
        analyzer.extract(run, Source("Broken.java", "class Broken { void x( {"))
        self.assertEqual(set(analyzer.PARSERS), {"java"})
        self.assertEqual(run.counts["filesFailed"], 1)
        self.assertTrue(any(d["code"] == "SYNTAX_ERROR" for d in run.diagnostics))

    def test_keys_survive_threshold_and_line_changes(self):
        a = "class A { int f(int n) { if (n < 500) return 1; return 0; } }"
        _, first, _ = run_cli("tree-sitter", {"A.java": a})
        _, second, _ = run_cli("tree-sitter", {"A.java": "\n\n" + a.replace("500", "300")})
        key = lambda value: [f["stableKey"] for f in value["facts.ndjson"] if f["kind"] == "CONDITION"]
        self.assertEqual(key(first), key(second))

    def test_unknown_capability_and_nonascii_hashes(self):
        result, output, workspace = run_cli("tree-sitter", {"src/A.java": 'class A { String s = "\u00e9\U0001f600"; }\r\n'}, request(capabilities=["CALL_GRAPH"]))
        self.assertEqual(result.returncode, 10)
        self.assertIn("CALL_GRAPH", output["manifest.json"]["capabilitiesPartial"])
        assert_evidence(self, output, workspace)


if __name__ == "__main__":
    unittest.main()
