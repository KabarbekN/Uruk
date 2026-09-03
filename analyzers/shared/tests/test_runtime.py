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
from test_support import run_cli, request, SHARED, SCHEMAS, assert_evidence
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
        os.symlink(external, workspace / "linked", target_is_directory=True)
        os.symlink(external / "Hidden.java", workspace / "link.java")
        with patch.dict(os.environ, {"SEMANTIC_WORKSPACE": str(workspace)}):
            run = Run(DESCRIPTOR)
            run.load(req)
            self.assertEqual([s.path for s in run.sources({".java"})], ["Good.java"])
            self.assertEqual(sum(d["code"] == "SYMLINK_REJECTED" for d in run.diagnostics), 2)
            changed = request(root="linked")
            req.write_text(json.dumps(changed))
            with self.assertRaises(Rejected):
                Run(DESCRIPTOR).load(req)

    def test_file_budget_output_budget_and_output_symlinks(self):
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
            output = root / "output"
            output.mkdir()
            outside = root / "important.txt"
            outside.write_text("unchanged")
            os.symlink(outside, output / "facts.ndjson")
            with self.assertRaises(Rejected):
                run.write(output)
            self.assertEqual(outside.read_text(), "unchanged")
            run.output_limit = 262144
            with self.assertRaises(Rejected) as error:
                for i in range(1000):
                    run.fact("LITERAL", f"key:{i}", "a" * 1000, "", Source("A.java", "a").evidence())
            self.assertEqual(error.exception.code, "OUTPUT_LIMIT")

    def test_describe_matches_schema_for_both_python_analyzers(self):
        schema = Draft202012Validator(json.loads((SCHEMAS / "describe.schema.json").read_text()))
        env = {**os.environ, "PYTHONPATH": str(SHARED / ".deps") + os.pathsep + os.environ.get("PYTHONPATH", "")}
        for analyzer in ("tree-sitter", "postgresql"):
            result = subprocess.run([sys.executable, str(SHARED.parent / analyzer / "semantic-analyzer"), "describe"], capture_output=True, text=True, env=env, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            schema.validate(json.loads(result.stdout))


if __name__ == "__main__":
    unittest.main()
