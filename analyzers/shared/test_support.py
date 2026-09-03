"""Local CLI harness shared by parser acceptance tests; never used in production."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from jsonschema import Draft202012Validator

SHARED = Path(__file__).resolve().parent
SCHEMAS = SHARED.parents[1] / "analyzer-contract/schemas/v1"


def request(root=".", capabilities=None):
    return {"contractVersion": "1.0", "analysisId": "test-analysis", "organizationId": "test-org",
            "projectId": "test-project", "revision": {"commitSha": "fixture", "branch": "test"},
            "component": {"rootPath": root, "languages": ["SQL"], "frameworks": [], "buildSystem": "NONE"},
            "requestedCapabilities": capabilities or ["SYMBOLS"], "changedFiles": [],
            "policy": {"networkAccess": "DENY", "executeBuildScripts": False, "resolveDependencies": False,
                       "includeTests": True, "includeGeneratedSources": False,
                       "maxDurationSeconds": 60, "maxOutputBytes": 8 * 1024 * 1024}}


def run_cli(analyzer, files, req=None, prepare=None):
    directory = SHARED.parent / analyzer / ".test-output"
    directory.mkdir(exist_ok=True)
    test_root = Path(tempfile.mkdtemp(dir=directory))
    workspace = test_root / "workspace"
    workspace.mkdir()
    for name, value in files.items():
        target = workspace / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(value, bytes):
            target.write_bytes(value)
        else:
            target.write_text(value, encoding="utf-8", newline="")
    if prepare:
        prepare(workspace, test_root)
    input_path = test_root / "request.json"
    input_path.write_text(json.dumps(req or request()), encoding="utf-8")
    environment = {**os.environ, "SEMANTIC_WORKSPACE": str(workspace)}
    environment["PYTHONPATH"] = os.pathsep.join([str(SHARED / ".deps"), str(SHARED), environment.get("PYTHONPATH", "")])
    result = subprocess.run([sys.executable, str(SHARED.parent / analyzer / "semantic-analyzer"), "analyze",
                             "--request", str(input_path), "--output", str(test_root / "output")],
                            capture_output=True, text=True, env=environment, timeout=90)
    outputs = {}
    for file in (test_root / "output").glob("*"):
        text = file.read_text(encoding="utf-8")
        outputs[file.name] = [json.loads(line) for line in text.splitlines()] if file.suffix == ".ndjson" else json.loads(text)
    return result, outputs, workspace


def assert_evidence(test, outputs, workspace):
    for artifact, name in (("manifest.json", "manifest"), ("coverage.json", "coverage"), ("statistics.json", "statistics")):
        Draft202012Validator(json.loads((SCHEMAS / f"{name}.schema.json").read_text())).validate(outputs[artifact])
    fact_schema = Draft202012Validator(json.loads((SCHEMAS / "fact.schema.json").read_text()))
    diagnostic_schema = Draft202012Validator(json.loads((SCHEMAS / "diagnostic.schema.json").read_text()))
    for diagnostic in outputs["diagnostics.ndjson"]:
        diagnostic_schema.validate(diagnostic)
    for fact in outputs["facts.ndjson"]:
        fact_schema.validate(fact)
        test.assertEqual(fact["contractVersion"], "1.0")
        test.assertIsInstance(fact["properties"]["ownerKey"], str)
        for evidence in fact["evidence"]:
            source = (workspace / evidence["filePath"]).read_text(encoding="utf-8").splitlines()
            # Empty files still have a valid empty first line.
            source = source or [""]
            test.assertGreaterEqual(evidence["startLine"], 1)
            test.assertGreaterEqual(evidence["endLine"], evidence["startLine"])
            test.assertLessEqual(evidence["endLine"], len(source))
            test.assertLessEqual(evidence["endColumn"], len(source[evidence["endLine"] - 1]) + 1)
            value = "\n".join(source[evidence["startLine"] - 1:evidence["endLine"]])
            test.assertEqual(evidence["snippetHash"], "sha256:" + hashlib.sha256(value.encode()).hexdigest())
        if fact["kind"] == "RELATION":
            test.assertTrue(all(fact["properties"].get(key) for key in ("sourceKey", "targetKey", "edgeKind")))


def fixture_files(directory):
    return {file.relative_to(directory).as_posix(): file.read_text(encoding="utf-8") for file in sorted(directory.rglob("*")) if file.is_file()}
