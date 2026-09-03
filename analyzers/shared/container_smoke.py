"""Build-independent OCI acceptance test; all generated files stay in shared/.test-output."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from jsonschema import Draft202012Validator
from test_support import SHARED, SCHEMAS, request, assert_evidence


def execute(command):
    return subprocess.run(command, capture_output=True, text=True, timeout=120)


def prepare_output_directory(root, attempt):
    output = root / f"output-{attempt}"
    output.mkdir()
    assert output.resolve().parent == root.resolve() and not output.is_symlink()
    # Only this fresh fixture mount needs to be writable by a different container UID.
    if os.name == "posix":
        output.chmod(0o777)
    return output


def smoke(analyzer):
    image = f"semanticmap/{analyzer}:0.1.0"
    inspect = execute(["docker", "image", "inspect", "--format", "{{.Id}}", image])
    if inspect.returncode:
        raise RuntimeError(inspect.stderr)
    image_id = inspect.stdout.strip()
    security = ["docker", "run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--pids-limit", "64", "--memory", "512m", "--cpus", "1"]
    described = execute([*security, image_id, "describe"])
    if described.returncode:
        raise RuntimeError(described.stderr)
    descriptor = json.loads(described.stdout)
    Draft202012Validator(json.loads((SCHEMAS / "describe.schema.json").read_text())).validate(descriptor)
    assert descriptor["id"] == analyzer
    assert descriptor["version"] == "0.1.0"
    fixtures = SHARED.parent / analyzer / "tests/fixtures"
    initial = {f.relative_to(fixtures): f.read_bytes() for f in fixtures.rglob("*") if f.is_file()}
    output_root = SHARED / ".test-output"
    output_root.mkdir(exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix=f"container-{analyzer}-", dir=output_root))
    input_dir = root / "input"
    input_dir.mkdir()
    req = request(capabilities=descriptor["capabilities"])
    req["component"]["languages"] = descriptor["languages"]
    (input_dir / "request.json").write_text(json.dumps(req), encoding="utf-8")
    previous = None
    for attempt in (1, 2):
        output = prepare_output_directory(root, attempt)
        result = execute([*security,
                          "--mount", f"type=bind,source={fixtures},target=/workspace,readonly",
                          "--mount", f"type=bind,source={input_dir},target=/input,readonly",
                          "--mount", f"type=bind,source={output},target=/output",
                          "--env", "SEMANTIC_IMAGE_DIGEST=" + image_id,
                          image_id, "analyze", "--request", "/input/request.json", "--output", "/output"])
        if result.returncode not in (0, 10):
            raise AssertionError(f"{image}: exit {result.returncode}: {result.stderr}; output {output}")
        names = {file.name for file in output.iterdir()}
        assert names == {"manifest.json", "facts.ndjson", "diagnostics.ndjson", "coverage.json", "statistics.json"}, names
        artifacts = {}
        for file in output.iterdir():
            content = file.read_text(encoding="utf-8")
            artifacts[file.name] = [json.loads(line) for line in content.splitlines()] if file.suffix == ".ndjson" else json.loads(content)
        assert_evidence(unittest.TestCase(), artifacts, fixtures)
        assert artifacts["manifest.json"]["analyzer"]["imageDigest"] == image_id
        assert artifacts["manifest.json"]["factCount"] == len(artifacts["facts.ndjson"]) > 0
        if previous is not None:
            assert previous == artifacts["facts.ndjson"], "OCI fact output was not deterministic"
        previous = artifacts["facts.ndjson"]
    assert initial == {f.relative_to(fixtures): f.read_bytes() for f in fixtures.rglob("*") if f.is_file()}
    summary = {"image": image, "imageId": image_id, "status": artifacts["manifest.json"]["status"],
               "facts": len(previous), "diagnostics": len(artifacts["diagnostics.ndjson"]), "output": str(root),
               "checks": ["non-root-image", "network-none", "read-only-root", "read-only-workspace", "canonical-v1-schemas", "evidence-hashes", "determinism"]}
    user = execute(["docker", "image", "inspect", "--format", "{{.Config.User}}", image_id]).stdout.strip()
    assert user and user not in {"0", "root", "0:0"}
    print(json.dumps(summary))
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("analyzer", choices=["tree-sitter", "postgresql", "typescript-node"])
    smoke(parser.parse_args().analyzer)
