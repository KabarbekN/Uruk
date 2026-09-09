import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime import Run, Source, Rejected, digest
from test_support import run_cli, request, SHARED, SCHEMAS, assert_evidence, symlink_or_skip
from jsonschema import Draft202012Validator

DESCRIPTOR = {"id": "test", "version": "0.1.0", "capabilities": ["SYMBOLS"]}


class RuntimeTests(unittest.TestCase):
    def test_ignore_component_and_default_exclusions(self):
        files = {".semanticmapignore": "component/skip.java\n", "component/A.java": "class A {}",
                 "component/skip.java": "class Skip {}", "component/node_modules/X.java": "class X {}",
                 "component/.git/X.java": "class X {}", "component/target/X.java": "class X {}",
                 "other/Y.java": "class Y {}"}
        result, outputs, workspace = run_cli("tree-sitter", files, request(root="component"))
        self.assertEqual(result.returncode, 0, outputs)
        self.assertEqual([f["properties"]["name"] for f in outputs["facts.ndjson"] if f["kind"] == "CLASS"], ["A"])
        assert_evidence(self, outputs, workspace)

    def test_request_schema_and_path_traversal(self):
        for root in ("../outside", "C:/outside", "/etc", "a/../../outside"):
            with self.subTest(root=root):
                result, output, _ = run_cli("tree-sitter", {}, request(root=root))
                self.assertEqual(result.returncode, 50, output)
        req = request()
        del req["revision"]
        self.assertEqual(run_cli("tree-sitter", {}, req)[0].returncode, 20)
        req = request()
        req["unknown"] = True
        self.assertEqual(run_cli("tree-sitter", {}, req)[0].returncode, 20)
        self.assertEqual(run_cli("tree-sitter", {})[0].returncode, 30)

    def test_oversized_and_binary_sources_are_partial(self):
        result, outputs, _ = run_cli("tree-sitter", {"huge.java": "a" * (2 * 1024 * 1024 + 1), "binary.java": b"\xff\0"})
        self.assertEqual(result.returncode, 10, outputs)
        self.assertEqual({d["code"] for d in outputs["diagnostics.ndjson"]}, {"FILE_TOO_LARGE", "INVALID_ENCODING"})

    def test_source_hash_and_columns_include_bom_and_unicode(self):
        source = Source("x.ts", "\ufeffconst x = '\u00e9\U0001f600';\r\nnext\r\n")
        evidence = source.evidence(3, len(source.text))
        self.assertEqual(evidence["endLine"], 2)
        self.assertEqual(evidence["snippetHash"], digest("\ufeffconst x = '\u00e9\U0001f600';\nnext"))

    def test_relations_preserve_distinct_evidence_within_the_output_budget(self):
        run = Run(DESCRIPTOR)
        source = Source("A.java", "first();\nsecond();")
        first, second = source.evidence(0, 8), source.evidence(9, 18)
        key = run.relation("caller", "callee", "CALLS", first)
        before = run.bytes_used
        self.assertEqual(key, run.relation("caller", "callee", "CALLS", second))
        self.assertEqual(len(run.facts), 1)
        self.assertEqual(run.facts[0]["evidence"], [first, second])
        self.assertGreater(run.bytes_used, before)
        after = run.bytes_used
        run.relation("caller", "callee", "CALLS", dict(second))
        self.assertEqual(run.bytes_used, after)
        run.output_limit = after + 131072
        with self.assertRaises(Rejected) as error:
            run.relation("caller", "callee", "CALLS", Source("B.java", "third();").evidence())
        self.assertEqual(error.exception.code, "OUTPUT_LIMIT")
        self.assertEqual(len(run.facts[0]["evidence"]), 2)

    def test_build_and_dependency_execution_requests_are_explicitly_rejected(self):
        for field in ("executeBuildScripts", "resolveDependencies"):
            req = request()
            req["policy"][field] = True
            result, artifacts, _ = run_cli("tree-sitter", {"A.java": "class A {}"}, req)
            self.assertEqual(result.returncode, 50, artifacts)
            self.assertEqual(artifacts["facts.ndjson"], [])
            self.assertEqual(artifacts["manifest.json"]["status"], "FAILED")
            self.assertEqual(artifacts["diagnostics.ndjson"][0]["code"], "UNSUPPORTED_EXECUTION_POLICY")

    def test_relation_evidence_limit_is_explicit_and_remains_ingestible(self):
        run = Run(DESCRIPTOR)
        for index in range(40):
            run.relation("caller", "callee", "CALLS", Source(f"A{index}.java", "call();").evidence())
        self.assertEqual(len(run.facts[0]["evidence"]), 32)
        self.assertIn("RELATION_EVIDENCE_LIMIT", run.unknown)
        self.assertEqual(len(run.diagnostics), 1)
        self.assertEqual(run.diagnostics[0]["code"], "RELATION_EVIDENCE_LIMIT")

    def test_output_traversal_cannot_disguise_a_directory_inside_the_workspace(self):
        root, workspace, req = self.sandbox()
        outside = root / "outside"
        outside.mkdir()
        with patch.dict(os.environ, {"SEMANTIC_WORKSPACE": str(workspace)}):
            run = Run(DESCRIPTOR)
            run.load(req)
            with self.assertRaises(Rejected) as error:
                run.write(outside / ".." / "workspace" / "output")
            self.assertEqual(error.exception.code, "OUTPUT_IN_WORKSPACE")
            self.assertFalse((workspace / "output").exists())

    def test_failed_request_cannot_write_failure_artifacts_inside_source(self):
        for invalid in ({}, {**request(), "policy": {**request()["policy"], "executeBuildScripts": True}}):
            root, workspace, req = self.sandbox()
            req.write_text(json.dumps(invalid), encoding="utf-8")
            sentinel = workspace / "facts.ndjson"
            sentinel.write_text("original source", encoding="utf-8")
            with patch.dict(os.environ, {"SEMANTIC_WORKSPACE": str(workspace)}):
                run = Run(DESCRIPTOR)
                with self.assertRaises(Rejected):
                    run.load(req)
                with self.assertRaises(Rejected) as error:
                    run.write(workspace, 20)
                self.assertEqual(error.exception.code, "OUTPUT_IN_WORKSPACE")
            self.assertEqual(sentinel.read_text(encoding="utf-8"), "original source")
            self.assertEqual(list(workspace.iterdir()), [sentinel])

    def test_component_selection_cannot_bypass_parent_exclusions(self):
        for selected in ("hidden/nested", "node_modules/nested", "target/generated-sources", "tests/nested"):
            req = request(root=selected)
            req["policy"]["includeTests"] = False
            result, output, _ = run_cli("tree-sitter", {
                ".semanticmapignore": "hidden/\n!hidden/nested/Keep.java\n",
                f"{selected}/Keep.java": "class Keep {}"
            }, req)
            self.assertEqual(result.returncode, 30, output)
            self.assertEqual(output["facts.ndjson"], [])

    def test_generated_source_policy_includes_maven_target_sources(self):
        req = request()
        req["policy"]["includeGeneratedSources"] = True
        result, output, workspace = run_cli("tree-sitter", {
            "target/generated-sources/Generated.java": "class Generated {}",
            "node_modules/Hidden.java": "class Hidden {}"
        }, req)
        self.assertEqual(result.returncode, 0, output)
        self.assertEqual([f["properties"]["name"] for f in output["facts.ndjson"] if f["kind"] == "CLASS"], ["Generated"])
        assert_evidence(self, output, workspace)

    def sandbox(self):
        output_root = SHARED / ".test-output"
        output_root.mkdir(exist_ok=True)
        root = Path(tempfile.mkdtemp(dir=output_root))
        workspace = root / "workspace"
        workspace.mkdir()
        req = root / "request.json"
        req.write_text(json.dumps(request()), encoding="utf-8")
        return root, workspace, req

    def test_symlink_files_and_directories_never_followed(self):
        root, workspace, req = self.sandbox()
        external = root / "external"
        external.mkdir()
        (external / "Hidden.java").write_text("class Hidden {}")
        (workspace / "Good.java").write_text("class Good {}")
        symlink_or_skip(external, workspace / "linked", target_is_directory=True)
        symlink_or_skip(external / "Hidden.java", workspace / "link.java")
        with patch.dict(os.environ, {"SEMANTIC_WORKSPACE": str(workspace)}):
            run = Run(DESCRIPTOR)
            run.load(req)
            self.assertEqual([s.path for s in run.sources({".java"})], ["Good.java"])
            self.assertEqual(sum(d["code"] == "SYMLINK_REJECTED" for d in run.diagnostics), 2)
            changed = request(root="linked")
            req.write_text(json.dumps(changed))
            with self.assertRaises(Rejected):
                Run(DESCRIPTOR).load(req)

    def test_file_and_output_byte_budgets(self):
        root, workspace, req = self.sandbox()
        for name in ("A.java", "B.java"):
            (workspace / name).write_text("class A {}")
        with patch.dict(os.environ, {"SEMANTIC_WORKSPACE": str(workspace)}):
            run = Run(DESCRIPTOR)
            run.load(req)
            with patch("runtime.MAX_FILES", 1), self.assertRaises(Rejected) as error:
                list(run.sources({".java"}))
            self.assertEqual(error.exception.code, "FILE_LIMIT")
            with self.assertRaises(Rejected):
                run.write(workspace / "output")
            run.output_limit = 262144
            with self.assertRaises(Rejected) as error:
                for i in range(1000):
                    run.fact("LITERAL", f"key:{i}", "a" * 1000, "", Source("A.java", "a").evidence())
            self.assertEqual(error.exception.code, "OUTPUT_LIMIT")

    def test_output_symlinks_never_overwrite_the_target(self):
        root, workspace, req = self.sandbox()
        output = root / "output"
        output.mkdir()
        outside = root / "important.txt"
        outside.write_text("unchanged")
        symlink_or_skip(outside, output / "facts.ndjson")
        with patch.dict(os.environ, {"SEMANTIC_WORKSPACE": str(workspace)}):
            run = Run(DESCRIPTOR)
            run.load(req)
            with self.assertRaises(Rejected):
                run.write(output)
            self.assertEqual(outside.read_text(), "unchanged")

    def test_describe_matches_schema_for_both_python_analyzers(self):
        schema = Draft202012Validator(json.loads((SCHEMAS / "describe.schema.json").read_text()))
        env = {**os.environ, "PYTHONPATH": str(SHARED / ".deps") + os.pathsep + os.environ.get("PYTHONPATH", "")}
        for analyzer in ("tree-sitter", "postgresql"):
            result = subprocess.run([sys.executable, str(SHARED.parent / analyzer / "semantic-analyzer"), "describe"], capture_output=True, text=True, env=env, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            schema.validate(json.loads(result.stdout))


if __name__ == "__main__":
    unittest.main()
