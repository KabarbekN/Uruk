"""Contract v1 transport and bounded, read-only source access for Python analyzers."""

import argparse
import bisect
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import time
from collections import Counter
from datetime import datetime, timezone

import pathspec
from jsonschema import Draft202012Validator

VERSION = "1.0"
MAX_FILES = 10000
MAX_ENTRIES = 100000
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_SOURCE_BYTES = 64 * 1024 * 1024
IGNORED = {".git", "target", "node_modules", ".deps", "__pycache__"}
GENERATED = {"dist", "build", "generated", "generated-sources"}


def digest(value):
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def linked(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


class Rejected(Exception):
    def __init__(self, code, message, exit_code=50):
        super().__init__(message)
        self.code, self.exit_code = code, exit_code


class Source:
    def __init__(self, path, text):
        self.path = path
        self.text = text.replace("\r\n", "\n").replace("\r", "\n")
        self.data = self.text.encode("utf-8")
        self.lines = self.text.split("\n")
        self.offsets = [0]
        for line in self.lines[:-1]:
            self.offsets.append(self.offsets[-1] + len(line) + 1)

    def evidence(self, start=0, end=None):
        end = len(self.text) if end is None else end
        start = max(0, min(start, len(self.text)))
        end = max(start, min(end, len(self.text)))
        first = bisect.bisect_right(self.offsets, start) - 1
        last = bisect.bisect_right(self.offsets, max(start, end - 1)) - 1
        return {"filePath": self.path, "startLine": first + 1,
                "startColumn": start - self.offsets[first] + 1,
                "endLine": last + 1, "endColumn": min(end - self.offsets[last], len(self.lines[last])) + 1,
                "snippetHash": digest("\n".join(self.lines[first:last + 1]))}

    def byte_evidence(self, start, end):
        return self.evidence(len(self.data[:start].decode("utf-8")),
                             len(self.data[:end].decode("utf-8")))


class Run:
    def __init__(self, descriptor):
        self.descriptor = descriptor
        self.request = {}
        self.facts, self.diagnostics = [], []
        self.fact_ids = set()
        self.partial = set()
        self.unknown = set()
        self.counts = {"filesDiscovered": 0, "filesParsed": 0, "filesFailed": 0,
                       "filesSkipped": 0, "sourceBytes": 0, "entriesVisited": 0}
        self.started = datetime.now(timezone.utc).isoformat()
        self.deadline = time.monotonic() + 600
        self.output_limit = 64 * 1024 * 1024
        self.bytes_used = 0

    def tick(self):
        if time.monotonic() > self.deadline:
            raise Rejected("DURATION_LIMIT", "Analysis duration limit reached")

    def diagnostic(self, code, message, path=None, line=None, severity="WARNING", metadata=None):
        if len(self.diagnostics) >= 100:
            self.unknown.add("DIAGNOSTICS_TRUNCATED")
            return
        value = {"code": code, "severity": severity, "message": message[:800],
                 "filePath": path, "startLine": line, "metadata": metadata or {}}
        self.diagnostics.append(value)

    def fact(self, kind, key, name, owner, evidence, origin="STATIC_SYNTAX", confidence=0.8,
             subject=None, **properties):
        self.tick()
        fact_id = digest(key)[7:]
        if fact_id in self.fact_ids:
            return key
        value = {"contractVersion": VERSION, "factId": fact_id, "kind": kind,
                 "stableKey": key, "subject": subject or {"kind": kind, "stableKey": key},
                 "properties": {"name": name, "ownerKey": owner or "", **properties},
                 "origin": origin, "confidence": confidence, "evidence": [evidence]}
        size = len(encode(value).encode("utf-8")) + 1
        if self.bytes_used + size > self.output_limit - 131072:
            raise Rejected("OUTPUT_LIMIT", "Fact output byte budget reached")
        self.bytes_used += size
        self.fact_ids.add(fact_id)
        self.facts.append(value)
        return key

    def relation(self, source, target, edge, evidence, origin="STATIC_SYNTAX", confidence=0.8):
        return self.fact("RELATION", f"relation:{edge}:{source}->{target}", edge, source,
                         evidence, origin, confidence, sourceKey=source, targetKey=target, edgeKind=edge)

    def load(self, request_path):
        if request_path.stat().st_size > 1024 * 1024:
            raise Rejected("INVALID_REQUEST", "Request exceeds 1 MiB", 20)
        try:
            req = json.loads(request_path.read_text(encoding="utf-8-sig"))
        except (ValueError, UnicodeError) as error:
            raise Rejected("INVALID_REQUEST", str(error), 20) from error
        schema_path = Path(__file__).resolve().parents[2] / "analyzer-contract/schemas/v1/request.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        error = next(Draft202012Validator(schema).iter_errors(req), None)
        if error:
            raise Rejected("INVALID_REQUEST", error.message, 20)
        if not isinstance(req, dict) or req.get("contractVersion") != VERSION:
            raise Rejected("INVALID_REQUEST", "contractVersion must be 1.0", 20)
        for field in ("analysisId", "organizationId", "projectId"):
            if not isinstance(req.get(field), str) or not req[field]:
                raise Rejected("INVALID_REQUEST", f"{field} must be a nonempty string", 20)
        component, policy = req.get("component"), req.get("policy", {})
        if not isinstance(component, dict) or not isinstance(component.get("rootPath"), str):
            raise Rejected("INVALID_REQUEST", "component.rootPath must be a string", 20)
        if not isinstance(policy, dict):
            raise Rejected("INVALID_REQUEST", "policy must be an object", 20)
        for field in ("requestedCapabilities", "changedFiles"):
            values = req.get(field, [])
            if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
                raise Rejected("INVALID_REQUEST", f"{field} must be a string array", 20)
        for field in ("includeTests", "includeGeneratedSources", "executeBuildScripts", "resolveDependencies"):
            if field in policy and not isinstance(policy[field], bool):
                raise Rejected("INVALID_REQUEST", f"policy.{field} must be boolean", 20)
        for field, default in (("maxDurationSeconds", 600), ("maxOutputBytes", 64 * 1024 * 1024)):
            if type(policy.get(field, default)) is not int or policy.get(field, default) <= 0:
                raise Rejected("INVALID_REQUEST", f"policy.{field} must be a positive integer", 20)
        self.request = req
        self.deadline = time.monotonic() + min(600, policy.get("maxDurationSeconds", 600))
        self.output_limit = min(256 * 1024 * 1024, policy.get("maxOutputBytes", self.output_limit))
        if self.output_limit < 262144:
            raise Rejected("OUTPUT_LIMIT", "At least 262144 output bytes are required")
        workspace_path = Path(os.environ.get("SEMANTIC_WORKSPACE", "/workspace")).absolute()
        if linked(workspace_path):
            raise Rejected("SYMLINK_REJECTED", "Workspace cannot be a symlink or reparse point")
        self.workspace = workspace_path.resolve(strict=True)
        root = component["rootPath"].replace("\\", "/")
        rel = PurePosixPath(root)
        if rel.is_absolute() or ":" in root or ".." in rel.parts:
            raise Rejected("PATH_ESCAPE", "component.rootPath must remain within /workspace")
        self.root = self.workspace
        for part in rel.parts:
            self.root /= part
            if linked(self.root):
                raise Rejected("SYMLINK_REJECTED", "Component traverses a symlink or reparse point")
        if not self.root.is_dir():
            raise Rejected("INVALID_REQUEST", "Component root is not a directory", 20)
        supported = set(self.descriptor["capabilities"])
        for cap in req.get("requestedCapabilities", []):
            if cap not in supported:
                self.partial.add(cap)
                self.unknown.add(cap)
                self.diagnostic("UNSUPPORTED_CAPABILITY", f"Capability {cap} is not supported")
        if req.get("changedFiles"):
            self.diagnostic("FULL_COMPONENT_ANALYSIS", "Incremental input is analyzed as a full component", severity="INFO")

    def sources(self, extensions):
        policy = self.request.get("policy", {})
        patterns = []
        ignore_path = self.workspace / ".semanticmapignore"
        if ignore_path.exists() or ignore_path.is_symlink():
            if linked(ignore_path):
                raise Rejected("SYMLINK_REJECTED", ".semanticmapignore cannot be a symlink")
            if ignore_path.stat().st_size > 65536:
                raise Rejected("IGNORE_LIMIT", ".semanticmapignore exceeds 64 KiB")
            patterns = ignore_path.read_text(encoding="utf-8-sig").splitlines()
        ignore = pathspec.GitIgnoreSpec.from_lines(patterns)
        pending = [(self.root, 0)]
        while pending:
            directory, depth = pending.pop()
            self.tick()
            if depth > 64:
                raise Rejected("DEPTH_LIMIT", "Directory nesting exceeds 64 levels")
            with os.scandir(directory) as scan:
                entries = []
                for item in scan:
                    self.counts["entriesVisited"] += 1
                    if self.counts["entriesVisited"] > MAX_ENTRIES:
                        raise Rejected("ENTRY_LIMIT", "Repository exceeds entry budget")
                    entries.append(item)
            for item in sorted(entries, key=lambda entry: entry.name):
                self.tick()
                path = Path(item.path)
                relative = path.relative_to(self.workspace).as_posix()
                if linked(path):
                    self.counts["filesSkipped"] += 1
                    self.unknown.add("REJECTED_PATHS")
                    self.diagnostic("SYMLINK_REJECTED", "Symlink/reparse point was not followed", relative)
                    continue
                is_dir = item.is_dir(follow_symlinks=False)
                if item.name in IGNORED or ignore.match_file(relative + ("/" if is_dir else "")):
                    continue
                if not policy.get("includeGeneratedSources", False) and item.name in GENERATED:
                    continue
                if not policy.get("includeTests", True) and (item.name in {"test", "tests", "__tests__"} or ".test." in item.name or ".spec." in item.name):
                    continue
                if is_dir:
                    pending.append((path, depth + 1))
                    continue
                if path.suffix.lower() not in extensions:
                    continue
                if not stat.S_ISREG(path.lstat().st_mode):
                    raise Rejected("SPECIAL_FILE_REJECTED", "Only regular source files can be read")
                self.counts["filesDiscovered"] += 1
                if self.counts["filesDiscovered"] > MAX_FILES:
                    raise Rejected("FILE_LIMIT", "Component exceeds file budget")
                if path.stat().st_size > MAX_FILE_BYTES:
                    self.counts["filesSkipped"] += 1
                    self.unknown.add("OVERSIZE_FILES")
                    self.diagnostic("FILE_TOO_LARGE", "Source exceeds 2 MiB", relative)
                    continue
                flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
                fd = os.open(path, flags)
                with os.fdopen(fd, "rb") as handle:
                    data = handle.read(MAX_FILE_BYTES + 1)
                self.counts["sourceBytes"] += len(data)
                if len(data) > MAX_FILE_BYTES or self.counts["sourceBytes"] > MAX_SOURCE_BYTES:
                    raise Rejected("SOURCE_LIMIT", "Source byte budget reached")
                try:
                    value = data.decode("utf-8")
                    if "\0" in value:
                        raise ValueError("NUL byte in source")
                    yield Source(relative, value)
                except (UnicodeError, ValueError) as error:
                    self.counts["filesFailed"] += 1
                    self.unknown.add("MALFORMED_SOURCE")
                    self.diagnostic("INVALID_ENCODING", str(error), relative, severity="ERROR")

    def write(self, output, exit_code=0):
        output = Path(output).absolute()
        for parent in [output, *output.parents]:
            if parent.exists() and linked(parent):
                raise Rejected("SYMLINK_REJECTED", "Output traverses a symlink")
        if hasattr(self, "workspace") and (output == self.workspace or self.workspace in output.parents):
            raise Rejected("OUTPUT_IN_WORKSPACE", "Output must be outside the read-only workspace")
        output.mkdir(parents=True, exist_ok=True)
        partial = bool(self.partial or self.unknown or self.counts["filesFailed"])
        if not exit_code:
            exit_code = 10 if partial else 0
        status = "SUCCEEDED" if exit_code == 0 else "PARTIAL" if exit_code == 10 else "FAILED"
        available = set(self.descriptor["capabilities"])
        requested = set(self.request.get("requestedCapabilities") or available)
        if partial:
            self.partial.update(requested & available)
        manifest = {"contractVersion": VERSION,
                    "analyzer": {"id": self.descriptor["id"], "version": self.descriptor["version"],
                                 "imageDigest": os.environ.get("SEMANTIC_IMAGE_DIGEST")},
                    "status": status, "capabilitiesCompleted": sorted(requested - self.partial) if exit_code in (0, 10) else [],
                    "capabilitiesPartial": sorted(self.partial),
                    "capabilitiesFailed": sorted(requested) if exit_code not in (0, 10) else [],
                    "factCount": len(self.facts), "diagnosticCount": len(self.diagnostics),
                    "startedAt": self.started, "finishedAt": datetime.now(timezone.utc).isoformat()}
        payloads = {"facts.ndjson": "".join(encode(f) + "\n" for f in sorted(self.facts, key=lambda f: f["stableKey"])),
                    "diagnostics.ndjson": "".join(encode(d) + "\n" for d in self.diagnostics),
                    "coverage.json": encode({"contractVersion": VERSION,
                                             **{key: self.counts[key] for key in ("filesDiscovered", "filesParsed", "filesFailed")},
                                             "capabilities": [{"name": cap, "status": "FAILED" if exit_code > 10 else "PARTIAL" if cap in self.partial else "COMPLETED",
                                                               "limitations": sorted(self.unknown) if cap in self.partial else []} for cap in sorted(requested)],
                                             "metadata": {"status": status, "unknown": sorted(self.unknown), **self.counts}}) + "\n",
                    "statistics.json": encode({"contractVersion": VERSION,
                                               "factCount": len(self.facts), "diagnosticCount": len(self.diagnostics),
                                               "factsByKind": dict(Counter(f["kind"] for f in self.facts)), "metadata": self.counts}) + "\n",
                    "manifest.json": encode(manifest) + "\n"}
        if sum(len(data.encode("utf-8")) for data in payloads.values()) > self.output_limit:
            raise Rejected("OUTPUT_LIMIT", "Result exceeds output budget")
        for name, content in payloads.items():
            target = output / name
            if target.exists() or target.is_symlink():
                if linked(target):
                    raise Rejected("SYMLINK_REJECTED", "Output file cannot be a symlink")
                target.unlink()
            with target.open("x", encoding="utf-8", newline="\n") as handle:
                handle.write(content)
        return exit_code


def cli(descriptor, analyze):
    parser = argparse.ArgumentParser(prog="semantic-analyzer")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("describe")
    command = commands.add_parser("analyze")
    command.add_argument("--request", required=True, type=Path)
    command.add_argument("--output", required=True, type=Path)
    try:
        args = parser.parse_args()
    except SystemExit as error:
        return 20 if error.code else 0
    if args.command == "describe":
        print(encode(descriptor))
        return 0
    run, exit_code = Run(descriptor), 0
    try:
        run.load(args.request)
        analyze(run)
        if not run.counts["filesDiscovered"]:
            raise Rejected("UNSUPPORTED_COMPONENT", "No supported source files were found", 30)
    except Rejected as error:
        exit_code = error.exit_code
        run.diagnostic(error.code, str(error), severity="ERROR")
    except (OSError, ValueError) as error:
        exit_code = 20
        run.diagnostic("INVALID_REQUEST", str(error), severity="ERROR")
    except Exception as error:
        exit_code = 60
        run.diagnostic("INTERNAL_ERROR", f"{type(error).__name__}: {error}", severity="FATAL")
    try:
        return run.write(args.output, exit_code)
    except (Rejected, OSError) as error:
        print(str(error), file=sys.stderr)
        return error.exit_code if isinstance(error, Rejected) else 60
