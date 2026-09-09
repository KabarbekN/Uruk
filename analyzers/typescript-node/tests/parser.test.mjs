import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import Ajv from "ajv/dist/2020.js";
import { hash, Source, Run, Rejected } from "../../shared/runtime.mjs";

const analyzerRoot = path.resolve(
  fileURLToPath(new URL("..", import.meta.url)),
);
const outputRoot = path.join(analyzerRoot, ".test-output");
fs.mkdirSync(outputRoot, { recursive: true });
const schemaRoot = path.resolve(
  analyzerRoot,
  "../../analyzer-contract/schemas/v1",
);
const validator = new Ajv({ strict: false, validateFormats: false });
const schemas = Object.fromEntries(
  [
    "fact",
    "request",
    "manifest",
    "describe",
    "diagnostic",
    "coverage",
    "statistics",
  ].map((name) => [
    name,
    validator.compile(
      JSON.parse(
        fs.readFileSync(path.join(schemaRoot, `${name}.schema.json`), "utf8"),
      ),
    ),
  ]),
);
const request = () => ({
  contractVersion: "1.0",
  analysisId: "test",
  organizationId: "org",
  projectId: "project",
  revision: { commitSha: "fixture", branch: "main" },
  component: {
    rootPath: ".",
    languages: ["TYPESCRIPT"],
    frameworks: ["NESTJS"],
    buildSystem: "NPM",
  },
  requestedCapabilities: ["SYMBOLS", "ENDPOINTS", "VALIDATIONS", "CALL_GRAPH"],
  changedFiles: [],
  policy: {
    networkAccess: "DENY",
    executeBuildScripts: false,
    resolveDependencies: false,
    includeTests: true,
    includeGeneratedSources: false,
    maxDurationSeconds: 60,
    maxOutputBytes: 8388608,
  },
});

function run(files, req = request(), prepare = () => {}) {
  const root = fs.mkdtempSync(path.join(outputRoot, "run-")),
    workspace = path.join(root, "workspace"),
    output = path.join(root, "output");
  fs.mkdirSync(workspace);
  for (const [name, contents] of Object.entries(files)) {
    const target = path.join(workspace, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, contents);
  }
  prepare(workspace, root);
  const input = path.join(root, "request.json");
  fs.writeFileSync(input, JSON.stringify(req));
  const result = spawnSync(
    process.execPath,
    [
      path.join(analyzerRoot, "semantic-analyzer.mjs"),
      "analyze",
      "--request",
      input,
      "--output",
      output,
    ],
    {
      encoding: "utf8",
      env: { ...process.env, SEMANTIC_WORKSPACE: workspace },
      timeout: 90000,
    },
  );
  const artifacts = {};
  if (fs.existsSync(output))
    for (const name of fs.readdirSync(output)) {
      const data = fs.readFileSync(path.join(output, name), "utf8");
      artifacts[name] = name.endsWith(".ndjson")
        ? data
            .trim()
            .split("\n")
            .filter(Boolean)
            .map((line) => JSON.parse(line))
        : JSON.parse(data);
    }
  return { result, artifacts, workspace };
}

function checkArtifacts(artifacts, workspace) {
  for (const [file, schema] of [
    ["manifest.json", "manifest"],
    ["coverage.json", "coverage"],
    ["statistics.json", "statistics"],
  ]) {
    assert.ok(
      schemas[schema](artifacts[file]),
      JSON.stringify(schemas[schema].errors),
    );
  }
  for (const fact of artifacts["facts.ndjson"]) {
    assert.ok(schemas.fact(fact), JSON.stringify(schemas.fact.errors));
    for (const evidence of fact.evidence) {
      const lines = fs
        .readFileSync(path.join(workspace, evidence.filePath), "utf8")
        .replace(/\r\n?/g, "\n")
        .split("\n");
      assert.equal(
        evidence.snippetHash,
        hash(lines.slice(evidence.startLine - 1, evidence.endLine).join("\n")),
      );
    }
  }
  for (const diagnostic of artifacts["diagnostics.ndjson"])
    assert.ok(
      schemas.diagnostic(diagnostic),
      JSON.stringify(schemas.diagnostic.errors),
    );
}

test("compiler resolves local calls; imported Nest aliases, guards and validations retain evidence", () => {
  const fixture = path.join(analyzerRoot, "tests/fixtures");
  const files = Object.fromEntries(
    fs
      .readdirSync(fixture)
      .map((name) => [name, fs.readFileSync(path.join(fixture, name), "utf8")]),
  );
  const { result, artifacts, workspace } = run(files);
  assert.equal(result.status, 10, result.stderr + JSON.stringify(artifacts));
  const facts = artifacts["facts.ndjson"];
  assert.ok(
    facts.some(
      (f) =>
        f.kind === "ENDPOINT" &&
        f.properties.httpMethod === "POST" &&
        f.properties.path === "/orders",
    ),
  );
  assert.ok(
    facts.some(
      (f) =>
        f.kind === "VALIDATION_RULE" &&
        f.properties.constraint === "Min" &&
        f.properties.arguments[0] === 500,
    ),
  );
  assert.ok(
    facts.some(
      (f) =>
        f.kind === "AUTHORIZATION_RULE" &&
        f.properties.guardBehavior === "UNKNOWN",
    ),
  );
  assert.ok(
    facts.some(
      (f) =>
        f.kind === "RELATION" &&
        f.properties.edgeKind === "CALLS" &&
        f.properties.targetKey.includes("method:create"),
    ),
  );
  checkArtifacts(artifacts, workspace);
  assert.deepEqual(facts, run(files).artifacts["facts.ndjson"]);
});

test("lookalike decorators, comments and strings do not fabricate endpoints or classes", () => {
  const { artifacts } = run({
    "fake.ts":
      "function Controller(x: string) { return (n: any) => n; } function Get(x: string) { return (n: any) => n; }\n// class Ghost {}\n@Controller('fake') class Local { @Get('fake') run() { return 'class Imaginary {}'; } }",
  });
  assert.equal(
    artifacts["facts.ndjson"].filter((f) => f.kind === "ENDPOINT").length,
    0,
  );
  assert.deepEqual(
    artifacts["facts.ndjson"]
      .filter((f) => f.kind === "CLASS")
      .map((f) => f.properties.name),
    ["Local"],
  );
});

test("React event props become UI entry points linked to named and inline arrow handlers", () => {
  const req = request();
  req.component.frameworks = ["REACT"];
  req.requestedCapabilities.push("UI_ACTIONS");
  const { artifacts, workspace } = run(
    {
      "screen.tsx": `
    function saveOrder() { submit(); }
    function submit() {}
    export function Screen() {
      return <><Button onPress={saveOrder} /><button onClick={() => submit()}>Save</button></>;
    }
  `,
    },
    req,
  );
  const facts = artifacts["facts.ndjson"];
  const actions = facts.filter((fact) => fact.kind === "UI_ACTION");
  assert.equal(actions.length, 2);
  assert.ok(
    actions.every(
      (action) => action.properties.targetResolution === "DECLARED_HANDLER",
    ),
  );
  assert.equal(
    facts.filter(
      (fact) =>
        fact.kind === "RELATION" && fact.properties.edgeKind === "ENTRY_TO",
    ).length,
    2,
  );
  assert.ok(
    facts.some(
      (fact) =>
        fact.kind === "RELATION" && fact.properties.edgeKind === "CALLS",
    ),
  );
  checkArtifacts(artifacts, workspace);
});

test("malformed sources and dynamic routes report partial coverage", () => {
  const { result, artifacts } = run({
    "broken.ts": "export class Broken { run( {",
    "route.ts":
      "import { Controller, Get } from '@nestjs/common'; @Controller(process.env.ROOT) class A { @Get() run() {} }",
  });
  assert.equal(result.status, 10);
  assert.ok(
    artifacts["diagnostics.ndjson"].some(
      (d) => d.code === "TYPESCRIPT_PARSE_ERROR",
    ),
  );
  assert.ok(
    artifacts["diagnostics.ndjson"].some((d) => d.code === "DYNAMIC_ROUTE"),
  );
  assert.equal(
    artifacts["facts.ndjson"].filter((f) => f.kind === "ENDPOINT").length,
    0,
  );
});

test("ignore patterns, directory exclusions and component boundaries are enforced", () => {
  const req = request();
  req.component.rootPath = "component";
  const { artifacts, workspace } = run(
    {
      ".semanticmapignore": "component/skip.ts\ncomponent/ignored/**\n",
      "component/keep.ts": "export class Keep {}",
      "component/skip.ts": "class Skip {}",
      "component/node_modules/bad.ts": "class Bad {}",
      "component/target/no.ts": "class Target {}",
      "component/ignored/no.ts": "class Ignored {}",
      "outside.ts": "class Outside {}",
    },
    req,
  );
  assert.deepEqual(
    artifacts["facts.ndjson"]
      .filter((f) => f.kind === "CLASS")
      .map((f) => f.properties.name),
    ["Keep"],
  );
  checkArtifacts(artifacts, workspace);
  req.component.rootPath = "../escape";
  assert.equal(run({}, req).result.status, 50);
});

test("directory symlinks and junctions are not followed", () => {
  const { result, artifacts } = run(
    { "ok.ts": "class Ok {}" },
    request(),
    (workspace, root) => {
      const outside = path.join(root, "outside");
      fs.mkdirSync(outside);
      fs.writeFileSync(path.join(outside, "secret.ts"), "class Secret {}");
      fs.symlinkSync(
        outside,
        path.join(workspace, "linked"),
        os.platform() === "win32" ? "junction" : "dir",
      );
    },
  );
  assert.equal(result.status, 10);
  assert.ok(
    artifacts["diagnostics.ndjson"].some((d) => d.code === "SYMLINK_REJECTED"),
  );
  assert.deepEqual(
    artifacts["facts.ndjson"]
      .filter((f) => f.kind === "CLASS")
      .map((f) => f.properties.name),
    ["Ok"],
  );
});

test("schema rejects incomplete and unknown requests; no sources is unsupported", () => {
  assert.equal(run({}, {}).result.status, 20);
  const invalid = request();
  invalid.extra = true;
  assert.equal(run({}, invalid).result.status, 20);
  assert.equal(run({}).result.status, 30);
});

test("evidence handles CRLF, BOM and supplementary Unicode in full source lines", () => {
  const text =
    '\uFEFFconst message = "\u00e9\u{1F600}";\r\nexport function echo() { return message; }\r\n';
  const { artifacts, workspace } = run({ "unicode.ts": text });
  checkArtifacts(artifacts, workspace);
  const source = new Source("unicode.ts", "a\u{1F600}b");
  assert.equal(source.evidence(3, 4).startColumn, 3);
});

test("describe follows the canonical v1 schema", () => {
  const result = spawnSync(
    process.execPath,
    [path.join(analyzerRoot, "semantic-analyzer.mjs"), "describe"],
    { encoding: "utf8" },
  );
  assert.equal(result.status, 0, result.stderr);
  assert.ok(
    schemas.describe(JSON.parse(result.stdout)),
    JSON.stringify(schemas.describe.errors),
  );
});

test("repeated local calls retain every distinct evidence location on one call edge", () => {
  const { artifacts, workspace } = run({
    "calls.ts":
      "function target() {}\nfunction caller() {\n  target();\n  target();\n}\n",
  });
  const edges = artifacts["facts.ndjson"].filter(
    (fact) => fact.kind === "RELATION" && fact.properties.edgeKind === "CALLS",
  );
  assert.equal(edges.length, 1);
  assert.deepEqual(
    edges[0].evidence.map((item) => item.startLine),
    [3, 4],
  );
  checkArtifacts(artifacts, workspace);
});

test("relation evidence deduplicates identical locations and respects output byte limits", () => {
  const run = new Run({
    id: "test",
    version: "0.1.0",
    capabilities: ["CALL_GRAPH"],
  });
  const source = new Source("calls.ts", "target();\ntarget();");
  const first = source.evidence(0, 9),
    second = source.evidence(10, 19);
  run.relation("caller", "target", "CALLS", first);
  const before = run.bytesUsed;
  run.relation("caller", "target", "CALLS", second);
  assert.ok(run.bytesUsed > before);
  const after = run.bytesUsed;
  run.relation("caller", "target", "CALLS", { ...second });
  assert.equal(run.bytesUsed, after);
  run.outputLimit = after + 131072;
  assert.throws(
    () =>
      run.relation(
        "caller",
        "target",
        "CALLS",
        new Source("other.ts", "target();").evidence(),
      ),
    (error) => error instanceof Rejected && error.code === "OUTPUT_LIMIT",
  );
  assert.equal(run.facts[0].evidence.length, 2);
});

test("SAFE_STATIC rejects build or dependency execution requests instead of claiming completion", () => {
  for (const field of ["executeBuildScripts", "resolveDependencies"]) {
    const req = request();
    req.policy[field] = true;
    const { result, artifacts } = run({ "A.ts": "class A {}" }, req);
    assert.equal(result.status, 50);
    assert.deepEqual(artifacts["facts.ndjson"], []);
    assert.equal(artifacts["manifest.json"].status, "FAILED");
    assert.equal(
      artifacts["diagnostics.ndjson"][0].code,
      "UNSUPPORTED_EXECUTION_POLICY",
    );
  }
});

test("large repeated-call edges remain within ingestion limits and report partial evidence", () => {
  const { result, artifacts, workspace } = run({
    "many.ts":
      "function target() {}\nfunction caller() {\n" +
      "  target();\n".repeat(40) +
      "}\n",
  });
  const edges = artifacts["facts.ndjson"].filter(
    (fact) => fact.kind === "RELATION" && fact.properties.edgeKind === "CALLS",
  );
  assert.equal(result.status, 10);
  assert.equal(edges.length, 1);
  assert.equal(edges[0].evidence.length, 32);
  assert.equal(
    artifacts["diagnostics.ndjson"].filter(
      (item) => item.code === "RELATION_EVIDENCE_LIMIT",
    ).length,
    1,
  );
  assert.ok(
    artifacts["coverage.json"].metadata.unknown.includes(
      "RELATION_EVIDENCE_LIMIT",
    ),
  );
  checkArtifacts(artifacts, workspace);
});

test("failed request cannot overwrite source files with failure artifacts", () => {
  const invalidPolicy = request();
  invalidPolicy.policy.executeBuildScripts = true;
  for (const invalid of [{}, invalidPolicy]) {
    const root = fs.mkdtempSync(path.join(outputRoot, "invalid-output-"));
    const workspace = path.join(root, "workspace");
    fs.mkdirSync(workspace);
    const input = path.join(root, "request.json");
    fs.writeFileSync(input, JSON.stringify(invalid));
    const sentinel = path.join(workspace, "facts.ndjson");
    fs.writeFileSync(sentinel, "original source");
    const result = spawnSync(
      process.execPath,
      [
        path.join(analyzerRoot, "semantic-analyzer.mjs"),
        "analyze",
        "--request",
        input,
        "--output",
        workspace,
      ],
      {
        encoding: "utf8",
        env: { ...process.env, SEMANTIC_WORKSPACE: workspace },
        timeout: 30000,
      },
    );
    assert.equal(result.status, 50, result.stderr);
    assert.equal(fs.readFileSync(sentinel, "utf8"), "original source");
    assert.deepEqual(fs.readdirSync(workspace), ["facts.ndjson"]);
  }
});

test("component selection cannot bypass excluded ancestor directories", () => {
  for (const selected of [
    "hidden/nested",
    "node_modules/nested",
    "target/generated-sources",
    "tests/nested",
  ]) {
    const req = request();
    req.component.rootPath = selected;
    req.policy.includeTests = false;
    const { result, artifacts } = run(
      {
        ".semanticmapignore": "hidden/\n!hidden/nested/keep.ts\n",
        [`${selected}/keep.ts`]: "class Keep {}",
      },
      req,
    );
    assert.equal(result.status, 30, result.stderr + JSON.stringify(artifacts));
    assert.deepEqual(artifacts["facts.ndjson"], []);
  }
});

test("generated source policy includes target output while dependencies stay excluded", () => {
  const req = request();
  req.policy.includeGeneratedSources = true;
  const { artifacts, workspace } = run(
    {
      "target/generated-sources/generated.ts": "class Generated {}",
      "node_modules/hidden.ts": "class Hidden {}",
    },
    req,
  );
  assert.deepEqual(
    artifacts["facts.ndjson"]
      .filter((f) => f.kind === "CLASS")
      .map((f) => f.properties.name),
    ["Generated"],
  );
  checkArtifacts(artifacts, workspace);
});
